import argparse
import json
from pathlib import Path

import numpy as np

from tools.model_eval.core import sha256_file
from tools.model_eval.encoders import AppClipOnnxEncoder, resolve_device
from tools.model_eval.train_docci_reranker import (
    encode_split, evaluation_backgrounds, fixed_candidates, measure, train, validate_dataset,
)


def pilot_indices(pilot, expanded):
    for split in ("qual_dev", "qual_test"):
        if pilot[split] != expanded[split]:
            raise ValueError(f"The two runs must use identical {split} photos, captions and hashes")
    expanded_by_id = {row["id"]: (index, row) for index, row in enumerate(expanded["train"])}
    positions = []
    for row in pilot["train"]:
        match = expanded_by_id.get(row["id"])
        if match is None or match[1] != row:
            raise ValueError("The smaller training split must be an exact subset of the larger split")
        positions.append(match[0])
    if len(positions) >= len(expanded_by_id):
        raise ValueError("The experiment requires two different training sizes")
    return positions


def run(args):
    import onnxruntime as ort
    import torch

    pilot = validate_dataset(args.pilot)
    expanded = validate_dataset(args.expanded)
    indices = pilot_indices(pilot, expanded)
    backgrounds, background_hash = evaluation_backgrounds(args.background_fixture, args.regression, args.expanded)
    encoder = AppClipOnnxEncoder("appclip", "app-clip", args.model, ort)
    device = resolve_device(args.device, torch)
    seed = 91871
    args.output.mkdir(parents=True, exist_ok=False)

    def features(split):
        rows = expanded[split]
        print(f"Encoding {split}: {len(rows)} photos", flush=True)
        images, queries = encode_split(rows, args.expanded.parent, encoder, args.text_graph, ort)
        if split in backgrounds:
            images = np.concatenate((images, backgrounds[split]))
        return tuple(torch.from_numpy(array).to(device) for array in (images, queries))

    full = features("train")
    dev = features("qual_dev")
    runs = {}
    heads = {}
    for name, data in (("pilot", tuple(matrix[indices] for matrix in full)), ("expanded", full)):
        torch.manual_seed(seed)
        print(f"Training {name}: {len(data[1])} photos, {args.matched_updates} optimizer updates", flush=True)
        scores, weights, receipt = train(data, dev, args, torch, device)
        heads[name] = scores
        weights_file = args.output / f"{name}-weights.npz"
        np.savez(weights_file, projection=weights[0].cpu().numpy(), residual=weights[1].cpu().numpy())
        runs[name] = {**receipt, "weightsSha256": sha256_file(weights_file)}
    test_images, test_queries = features("qual_test")
    for name, scores in heads.items():
        with torch.no_grad():
            candidates = fixed_candidates(test_images, test_queries, args.depth, torch)
            runs[name]["comparison"] = measure(scores(test_images, test_queries, candidates), candidates, torch)
    result = {
        "pilotDatasetSha256": sha256_file(args.pilot), "expandedDatasetSha256": sha256_file(args.expanded),
        "textGraphSha256": sha256_file(args.text_graph), "encoderSha256": encoder.model_sha256,
        "backgroundVectorsSha256": background_hash, "device": str(device), "torch": torch.__version__,
        "seed": seed, "rank": args.rank, "depth": args.depth, "checkpointUpdates": args.checkpoint_updates,
        "scope": "Matched optimizer budget and common development/comparison galleries. The comparison gallery was used in earlier experiments.",
        "runs": runs,
    }
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: {"bestUpdates": row["bestOptimizerUpdates"], "comparison": row["comparison"]}
                      for name, row in runs.items()}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    for name in ("pilot", "expanded", "model", "text-graph", "background-fixture", "regression", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--matched-updates", type=int, default=48)
    parser.add_argument("--checkpoint-updates", type=int, default=4)
    parser.set_defaults(rank=32, depth=25)
    args = parser.parse_args()
    if not 1 <= args.checkpoint_updates <= args.matched_updates <= 4096:
        parser.error("Choose a checkpoint interval within the update budget (maximum 4096)")
    run(args)


if __name__ == "__main__":
    main()
