import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[2]


def build_metrics(manifest, report, manifest_sha):
    if not report.get("complete") or report.get("manifest_sha256") != manifest_sha:
        raise ValueError("Need a complete result for the exact frozen manifest")
    image_ids = {row["id"] for row in manifest["images"]}
    if len(image_ids) != len(manifest["images"]):
        raise ValueError("Duplicate manifest image IDs")
    judgments = {row["query"]: row for row in manifest["queries"]}
    if len(judgments) != len(manifest["queries"]):
        raise ValueError("Duplicate query judgments")
    results = report["queries"]
    if len(results) != len(judgments) or {row["query"] for row in results} != set(judgments):
        raise ValueError("Result queries do not match the manifest")
    metrics = []
    for result in results:
        if any(not isinstance(result[key], (int, float)) or isinstance(result[key], bool) or
               not math.isfinite(result[key]) or result[key] < 0 for key in ("encode_ms", "rank_ms")):
            raise ValueError("Timing components must be finite nonnegative numbers")
        labels = judgments[result["query"]]
        positive, negative = set(labels["positive"]), set(labels["explicit_negative"])
        if positive & negative or not (positive | negative) <= image_ids:
            raise ValueError("Conflicting or unknown judgments")
        ids = [row["id"] for row in result["ranking"]]
        if len(ids) != len(image_ids) or set(ids) != image_ids:
            raise ValueError("Ranking must contain every image exactly once")
        top5 = ids[:5]
        hits = sum(value in positive for value in top5)
        false_alerts = sum(value in negative for value in top5)
        judged_count = hits + false_alerts
        metrics.append({
            "query": result["query"], "type": labels["type"],
            "positive_count": len(positive), "explicit_negative_count": len(negative),
            "top1_known_positive_hit": bool(ids and ids[0] in positive),
            "top5_known_positive_count": hits, "top5_explicit_false_alerts": false_alerts,
            "top5_unjudged": len(top5) - judged_count,
            "judged_top5_precision": hits / judged_count if negative and judged_count else None,
            "encode_ms": result["encode_ms"], "rank_ms": result["rank_ms"],
        })
    return {"images": len(image_ids), "manifest_sha256": manifest_sha, "metrics": metrics,
        "scope": "Known positives and explicit negatives; unjudged results are not false positives. No threshold tuned.",
        "partition": "Aggregate descriptive results; not an independently reviewed held-out natural-prompt evaluation."}


def summarize(report, metrics):
    summary = {key: report[key] for key in (
        "import_ms", "index_ms", "restore_ms", "index_bytes", "first_decodes", "first_model_opens",
        "repeat_decodes", "preexisting_cache_entries", "manifest_sha256", "complete")}
    summary.update(images=metrics["images"], families={})
    for family in sorted({row["type"] for row in metrics["metrics"]}):
        rows = [row for row in metrics["metrics"] if row["type"] == family]
        summary["families"][family] = {
            "queries": len(rows),
            "top1_known_positive_hits": sum(row["top1_known_positive_hit"] for row in rows),
            "top5_any_known_positive_hits": sum(row["top5_known_positive_count"] > 0 for row in rows),
            "top5_explicit_negative_results": sum(row["top5_explicit_false_alerts"] for row in rows),
            "top5_unjudged_results": sum(row["top5_unjudged"] for row in rows),
            "median_encode_ms": statistics.median(row["encode_ms"] for row in rows),
            "median_rank_ms": statistics.median(row["rank_ms"] for row in rows),
        }
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=ROOT / ".benchmark-data/gallery-v1")
    parser.add_argument("--results", type=Path, default=ROOT / "app/build/large-gallery-benchmark")
    args = parser.parse_args()
    frozen = (args.corpus / "manifest.json").read_bytes()
    report = json.loads((args.results / "emulator.json").read_text(encoding="utf-8"))
    metrics = build_metrics(json.loads(frozen), report, hashlib.sha256(frozen).hexdigest())
    for name, value in (("metrics.json", metrics), ("summary.json", summarize(report, metrics))):
        (args.results / name).write_text(json.dumps(value, indent=2), encoding="utf-8")
    print(json.dumps({"images": metrics["images"], "queries": len(metrics["metrics"])}))


if __name__ == "__main__":
    main()
