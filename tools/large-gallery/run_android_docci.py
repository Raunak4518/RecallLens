import argparse
import json
from pathlib import Path
import statistics

import numpy as np

from tools.model_eval.clip_text_precision_sweep import load_cached_app_gallery
from tools.model_eval.core import EmbeddingCache, evaluate_ranking, load_manifest, sha256_file
from tools.model_eval.encoders import AppClipOnnxEncoder

from .run_android_cache10k import run_fixture


MANIFEST_SHA256 = "0a7221f23d2f39cd7af6a4e613c4ad2b075e855cc8e274e23d276e75a9775d9e"


def prepare(args, manifest, digest):
    import onnxruntime

    encoder = AppClipOnnxEncoder("appclip", "app-clip", args.model, onnxruntime)
    ids, matrix, cache_receipt = load_cached_app_gallery(
        manifest, digest, encoder, EmbeddingCache(args.cache), manifest["images"], 8)
    if matrix.shape != (len(ids), 512):
        raise ValueError("Unexpected gallery dimensions")
    args.fixture.mkdir(parents=True, exist_ok=False)
    vectors = args.fixture / "scene-vectors-f32le.bin"
    ids_file = args.fixture / "scene-vector-ids.txt"
    matrix.astype("<f4", copy=False).tofile(vectors)
    ids_file.write_bytes(("\n".join(ids) + "\n").encode("ascii"))
    receipt = {
        "manifestSha256": digest,
        "cacheReceiptSha256": cache_receipt,
        "vectorsSha256": sha256_file(vectors),
        "idsSha256": sha256_file(ids_file),
        "queries": [row["query"] for row in manifest["queries"]],
        "depth": 100,
    }
    (args.fixture / "input.json").write_text(json.dumps(receipt), encoding="utf-8")


def evaluate(args, manifest, digest):
    receipt = json.loads((args.fixture / "input.json").read_text(encoding="utf-8"))
    vectors = args.fixture / "scene-vectors-f32le.bin"
    ids_file = args.fixture / "scene-vector-ids.txt"
    ids = [row["id"] for row in manifest["images"]]
    queries = manifest["queries"]
    if (receipt["manifestSha256"] != digest or receipt["queries"] != [row["query"] for row in queries]
            or receipt["depth"] != 100 or vectors.stat().st_size != len(ids) * 2048
            or ids_file.read_text(encoding="ascii").splitlines() != ids
            or sha256_file(vectors) != receipt["vectorsSha256"] or sha256_file(ids_file) != receipt["idsSha256"]):
        raise ValueError("Prepared fixture differs from its manifest or receipt")
    payload = {**receipt, "writeBatchSize": args.write_batch_size}
    data = run_fixture(args.adb, [vectors, ids_file], payload, len(ids), args.output)
    if data["textGraph"] != args.graph:
        raise ValueError(f"Expected {args.graph}, emulator ran {data['textGraph']}")
    if data.get("writeBatchSize", 1) != args.write_batch_size:
        raise ValueError("Emulator used a different write batch size")
    gallery_ids = set(ids)
    route_metrics = {"baseline": []}
    for row, query in zip(data["queries"], queries, strict=True):
        ranked = row["topIds"]
        if len(ranked) != receipt["depth"] or len(set(ranked)) != len(ranked) or not set(ranked) <= gallery_ids:
            raise ValueError("Invalid result IDs from emulator")
        route_metrics["baseline"].append(evaluate_ranking(ranked, query, cutoff=5))
        if "rerankedIds" in row:
            reranked = row["rerankedIds"]
            if reranked[25:] != ranked[25:] or sorted(reranked[:25]) != sorted(ranked[:25]):
                raise ValueError("Unexpected change in the visual shortlist")
            route_metrics.setdefault("reranked", []).append(evaluate_ranking(reranked, query, cutoff=5))
    keys = ("known_positive_hit_at_1", "known_positive_hit_at_5", "known_positive_candidate_coverage_at_depth")
    if any(len(rows) != len(queries) for rows in route_metrics.values()):
        raise ValueError("Incomplete route results")
    scores = {route: {key: sum(row[key] for row in rows) / len(rows) for key in keys}
              for route, rows in route_metrics.items()}
    metrics = scores["baseline"]
    (args.output / "quality.json").write_text(json.dumps({
        "textGraph": data["textGraph"], "manifestSha256": digest,
        "vectorsSha256": receipt["vectorsSha256"], "queries": len(queries), "photos": len(ids),
        "metrics": metrics,
        **({"rerankedMetrics": scores["reranked"]} if "reranked" in scores else {}),
    }, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metrics))
    if "reranked" in scores:
        print(json.dumps({"rerankedMetrics": scores["reranked"]}))


def compare(args, manifest, digest):
    paths = (args.baseline, args.candidate)
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    baseline, candidate = reports
    if any(not report["complete"] or report["manifestSha256"] != digest
           or report["photos"] != len(manifest["images"])
           or len(report["queries"]) != len(manifest["queries"])
           for report in reports) or any(baseline[key] != candidate[key] for key in ("vectorsSha256", "serial")):
        raise ValueError("Expected two completed emulator runs with the same fixture and serial")
    if baseline["textGraph"] == candidate["textGraph"]:
        raise ValueError("Comparison requires two distinct text graphs")
    comparisons = {"top1": {"gained": [], "lost": []}, "top5": {"gained": [], "lost": []}}
    for left, right, query in zip(baseline["queries"], candidate["queries"], manifest["queries"], strict=True):
        if left["query"] != query["query"] or right["query"] != query["query"]:
            raise ValueError("Query order differs from the manifest")
        positives = set(query["positive"])
        for cutoff in (1, 5):
            old_hit = bool(positives.intersection(left["topIds"][:cutoff]))
            new_hit = bool(positives.intersection(right["topIds"][:cutoff]))
            if old_hit != new_hit:
                comparisons[f"top{cutoff}"]["gained" if new_hit else "lost"].append(query["query_id"])
    timing = {}
    for report in reports:
        rows = report["queries"]
        warm = [row["encodeMs"] + row["rankMs"] for row in rows[1:]]
        timing[report["textGraph"]] = {
            "firstQueryMs": rows[0]["encodeMs"] + rows[0]["rankMs"],
            "warmMedianMs": statistics.median(warm),
            "warmP95Ms": float(np.percentile(warm, 95)),
        }
    result = {"manifestSha256": digest, "queryCount": len(manifest["queries"]),
              "baselineSha256": sha256_file(paths[0]), "candidateSha256": sha256_file(paths[1]),
              "timing": timing, "changedQueries": comparisons}
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2)
        output.write("\n")
    print(json.dumps({"timing": timing, "changedQueries": {
        cutoff: {change: len(ids) for change, ids in changes.items()}
        for cutoff, changes in comparisons.items()
    }}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--fixture", type=Path)
    stages = parser.add_subparsers(dest="stage", required=True)
    preparation = stages.add_parser("prepare")
    preparation.add_argument("--model", required=True, type=Path)
    preparation.add_argument("--cache", required=True, type=Path)
    run = stages.add_parser("run")
    run.add_argument("--adb", required=True, type=Path)
    run.add_argument("--graph", required=True, choices=["text_model_q4.onnx", "text_model_quantized.onnx"])
    run.add_argument("--output", required=True, type=Path)
    run.add_argument("--write-batch-size", type=int, choices=range(1, 33), default=1, metavar="1-32")
    comparison = stages.add_parser("compare")
    comparison.add_argument("--baseline", required=True, type=Path)
    comparison.add_argument("--candidate", required=True, type=Path)
    comparison.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.stage != "compare" and args.fixture is None:
        parser.error("--fixture is required to prepare or run a benchmark")
    manifest, digest, _ = load_manifest(args.manifest, MANIFEST_SHA256)
    if args.stage == "prepare":
        prepare(args, manifest, digest)
    elif args.stage == "run":
        evaluate(args, manifest, digest)
    else:
        compare(args, manifest, digest)


if __name__ == "__main__":
    main()
