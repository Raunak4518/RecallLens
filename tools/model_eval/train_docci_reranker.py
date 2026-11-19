import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time

import numpy as np

from tools.model_eval.clip_text_precision_sweep import encode_text_graph
from tools.model_eval.core import resolve_image_path, sha256_file
from tools.model_eval.encoders import AppClipOnnxEncoder, decode_verified_image, resolve_device


def validate_dataset(path):
    payload = json.loads(path.read_bytes())
    if payload["version"] != 1 or payload["imagesAcquired"] is not True:
        raise ValueError("Use a completed DOCCI dataset with verified images")
    splits = payload["splits"]
    if set(splits) != {"train", "qual_dev", "qual_test"} or any(len(rows) < 6 for rows in splits.values()):
        raise ValueError("Need train and two qualification splits")
    for field in ("id", "sha256"):
        values = [row[field] for rows in splits.values() for row in rows]
        if len(values) != len(set(values)):
            raise ValueError(f"Repeated image identity or content: {field}")
    seen = set()
    for rows in splits.values():
        clusters = {row["cluster"] for row in rows}
        if not seen.isdisjoint(clusters):
            raise ValueError("A publisher cluster crosses splits")
        seen.update(clusters)
    return splits


def encode_split(rows, root, encoder, graph, ort):
    def decode(row):
        path = resolve_image_path(row["file"], root)
        return decode_verified_image(path, row["sha256"], 16 * 1024**2, 32_000_000)

    images = []
    with ThreadPoolExecutor(max_workers=4) as workers:
        for start in range(0, len(rows), 16):
            batch = list(workers.map(decode, rows[start:start + 16]))
            try:
                vectors, _, _ = encoder.encode_images(batch)
                images.append(vectors)
            finally:
                for image in batch:
                    image.close()
            if (start + 16) % 160 == 0:
                print(f"Encoded {min(start + 16, len(rows))}/{len(rows)} photos", flush=True)
    queries = [(row["id"], {"query": row["caption"]}) for row in rows]
    text, _ = encode_text_graph(graph, queries, encoder.model_directory / "tokenizer.json", ort, 2)
    return np.concatenate(images), np.stack([text[row["id"]] for row in rows])


def evaluation_backgrounds(fixture, regression_path, dataset_path):
    receipt = json.loads((fixture / "input.json").read_bytes())
    dataset = json.loads(dataset_path.read_bytes())
    if sha256_file(regression_path) != receipt["manifestSha256"] or receipt["manifestSha256"] != dataset["regressionManifestSha256"]:
        raise ValueError("Background vectors and selected examples need the same frozen regression manifest")
    images = json.loads(regression_path.read_bytes())["images"]
    vectors = fixture / "scene-vectors-f32le.bin"
    ids = fixture / "scene-vector-ids.txt"
    if sha256_file(vectors) != receipt["vectorsSha256"] or sha256_file(ids) != receipt["idsSha256"]:
        raise ValueError("Background vector fixture checksum mismatch")
    if ids.read_text(encoding="ascii").splitlines() != [row["id"] for row in images]:
        raise ValueError("Background vector order differs from the manifest")
    if vectors.stat().st_size != len(images) * 2048:
        raise ValueError("Unexpected background vector dimensions")
    indices = [index for index, row in enumerate(images) if row.get("dataset") != "DOCCI"]
    if len(indices) != 10_000:
        raise ValueError("Expected the fixed 10,000 background photos")
    selected = np.memmap(vectors, dtype="<f4", mode="r", shape=(len(images), 512))[indices]
    norms = np.linalg.norm(selected, axis=1)
    if not np.allclose(norms, 1.0, atol=0.001):
        raise ValueError("Background vectors must be finite and normalized")
    return {"qual_dev": selected[::2], "qual_test": selected[1::2]}, receipt["vectorsSha256"]


def fixed_candidates(images, queries, depth, torch):
    scores = queries @ images.T
    return torch.argsort(scores, dim=1, descending=True, stable=True)[:, :depth]


def measure(scores, candidates, torch):
    targets = torch.arange(len(candidates), device=candidates.device)[:, None]
    baseline = candidates == targets
    order = torch.argsort(scores, descending=True, stable=True)
    ranked = candidates.gather(1, order) == targets

    def hits(mask, cutoff):
        return int(mask[:, :cutoff].any(dim=1).sum().item())

    before = baseline[:, :5].any(dim=1)
    after = ranked[:, :5].any(dim=1)
    return {
        "queries": len(candidates), "shortlistHits": hits(baseline, candidates.shape[1]),
        "baselineHit1": hits(baseline, 1), "baselineHit5": hits(baseline, 5),
        "trainedHit1": hits(ranked, 1), "trainedHit5": hits(ranked, 5),
        "top5Gained": int((after & ~before).sum().item()),
        "top5Lost": int((before & ~after).sum().item()),
    }


def training_windows(available, args, torch, device):
    matched_updates = getattr(args, "matched_updates", 0)
    if not matched_updates:
        for _ in range(args.epochs):
            yield available[torch.randperm(len(available), device=device)].split(64)
        return
    if len(available) < 64:
        raise ValueError("Matched-update training requires at least 64 eligible samples")
    window = []
    updates = 0
    while updates < matched_updates:
        order = available[torch.randperm(len(available), device=device)]
        for batch in order[:len(available) // 64 * 64].split(64):
            window.append(batch)
            updates += 1
            if updates % args.checkpoint_updates == 0 or updates == matched_updates:
                yield window
                window = []
            if updates == matched_updates:
                break


def train(train_features, dev_features, args, torch, device):
    nn = torch.nn
    train_images, train_queries = train_features
    dev_images, dev_queries = dev_features
    first = nn.Linear(512, args.rank, bias=False, device=device)
    second = nn.Linear(args.rank, 512, bias=False, device=device)
    nn.init.zeros_(second.weight)
    optimizer = torch.optim.AdamW([*first.parameters(), *second.parameters()], lr=0.001)

    def scores(images, queries, candidates):
        adjusted = nn.functional.normalize(queries + second(first(queries)), dim=-1)
        return torch.einsum("nd,nkd->nk", adjusted, images[candidates])

    train_candidates = fixed_candidates(train_images, train_queries, args.depth, torch)
    dev_candidates = fixed_candidates(dev_images, dev_queries, args.depth, torch)
    labels = train_candidates == torch.arange(len(train_queries), device=device)[:, None]
    available = labels.any(dim=1).nonzero().flatten()
    if len(available) < 16:
        raise ValueError("Too few training targets appear in the frozen shortlist")
    target_positions = labels.to(torch.int64).argmax(dim=1)
    best_state = (first.weight.detach().clone(), second.weight.detach().clone())
    best_metrics = measure(scores(dev_images, dev_queries, dev_candidates), dev_candidates, torch)
    best_epoch = 0
    best_updates = 0
    updates = 0
    history = []
    for epoch, batches in enumerate(training_windows(available, args, torch, device), 1):
        for batch in batches:
            logits = scores(train_images, train_queries[batch], train_candidates[batch])
            loss = nn.functional.cross_entropy(logits / 0.03, target_positions[batch])
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            updates += 1
        with torch.no_grad():
            metrics = measure(scores(dev_images, dev_queries, dev_candidates), dev_candidates, torch)
        history.append({"epoch": epoch, "optimizerUpdates": updates, **metrics})
        metric = lambda row: (row["trainedHit5"], row["trainedHit1"])
        if metric(metrics) > metric(best_metrics):
            best_metrics = metrics
            best_epoch = epoch
            best_updates = updates
            best_state = (first.weight.detach().clone(), second.weight.detach().clone())
    with torch.no_grad():
        first.weight.copy_(best_state[0])
        second.weight.copy_(best_state[1])
    return scores, best_state, {
        "trainTargetsInShortlist": len(available), "trainQueries": len(train_queries), "bestEpoch": best_epoch,
        "optimizerUpdates": updates, "bestOptimizerUpdates": best_updates,
        "development": best_metrics, "history": history,
    }


def main():
    parser = argparse.ArgumentParser(description="Train a compact residual reranker on frozen CLIP features.")
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--text-graph", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--rank", default=32, type=int)
    parser.add_argument("--depth", default=25, type=int)
    parser.add_argument("--epochs", default=12, type=int)
    parser.add_argument("--background-fixture", type=Path)
    parser.add_argument("--regression", type=Path)
    args = parser.parse_args()
    if not 1 <= args.rank <= 128 or not 5 <= args.depth <= 100 or not 1 <= args.epochs <= 100:
        parser.error("Use rank 1–128, depth 5–100, and epochs 1–100")
    splits = validate_dataset(args.dataset)
    if any(len(rows) < args.depth for rows in splits.values()):
        parser.error("Shortlist depth exceeds a split gallery")
    if (args.background_fixture is None) != (args.regression is None):
        parser.error("--background-fixture and --regression must be provided together")
    backgrounds, background_hash = ({}, None) if args.background_fixture is None else evaluation_backgrounds(
        args.background_fixture, args.regression, args.dataset)
    import onnxruntime as ort
    import torch

    args.output.mkdir(parents=True, exist_ok=False)
    device = resolve_device(args.device, torch)
    seed = 91871
    torch.manual_seed(seed)
    encoder = AppClipOnnxEncoder("appclip", "app-clip", args.model, ort)
    started = time.perf_counter()

    def features(split):
        print(f"Encoding {split}: {len(splits[split])} images", flush=True)
        images, queries = encode_split(splits[split], args.dataset.parent, encoder, args.text_graph, ort)
        if split in backgrounds:
            images = np.concatenate((images, backgrounds[split]))
        return torch.from_numpy(images).to(device), torch.from_numpy(queries).to(device)

    scores, weights, report = train(features("train"), features("qual_dev"), args, torch, device)
    test_images, test_queries = features("qual_test")
    with torch.no_grad():
        candidates = fixed_candidates(test_images, test_queries, args.depth, torch)
        test = measure(scores(test_images, test_queries, candidates), candidates, torch)
    weights_file = args.output / "residual-weights.npz"
    np.savez(weights_file, projection=weights[0].cpu().numpy(), residual=weights[1].cpu().numpy())
    report.update({
        "datasetSha256": sha256_file(args.dataset), "visionArtifactsSha256": encoder.model_sha256,
        "textGraphSha256": sha256_file(args.text_graph), "weightsSha256": sha256_file(weights_file),
        "rank": args.rank, "depth": args.depth, "epochs": args.epochs, "seed": seed,
        "device": str(device), "torch": torch.__version__, "onnxruntime": ort.__version__,
        "backgroundVectorsSha256": background_hash,
        "gallerySizes": {split: len(rows) + len(backgrounds.get(split, ())) for split, rows in splits.items()},
        "test": test, "elapsedSeconds": time.perf_counter() - started,
        "scope": "Exact paired-image retrieval on cluster-disjoint qualification galleries. Frozen host CLIP features and fixed shortlist.",
    })
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"bestEpoch": report["bestEpoch"], "test": test}), flush=True)


if __name__ == "__main__":
    main()
