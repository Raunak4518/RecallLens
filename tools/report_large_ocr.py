import argparse
import csv
import json
import math
import statistics
from collections import Counter
from pathlib import Path

from benchmark_public_datasets import BUILD, digest, write_json


MANIFEST_SHA = "ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd"


def latency(values):
    ordered = sorted(values)
    return {
        "total_ms": sum(values),
        "p50_ms": statistics.median(values),
        "p95_ms": ordered[math.ceil(.95 * len(ordered)) - 1],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    manifest_file = BUILD / "public-evaluation/selective-ocr/manifest.json"
    if digest(manifest_file) != MANIFEST_SHA:
        raise ValueError("The frozen gallery manifest changed")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    if report["manifest_sha256"] != MANIFEST_SHA:
        raise ValueError("Wrong manifest in emulator output")
    images = {row["image_id"]: row for row in manifest["images"]}
    samples = {row["id"]: row for row in report["images"]}
    expected = {row["id"]: row for row in manifest["queries"]}
    actual = {row["id"]: row for row in report["queries"]}
    if samples.keys() != images.keys() or actual.keys() != expected.keys():
        raise ValueError("Missing image or query results")
    counts = Counter()
    rows = []
    for query in manifest["queries"]:
        result = actual[query["id"]]
        if any(result[key] != value for key, value in query.items()):
            raise ValueError(f"Mismatched query {query['id']}")
        target = query["target"]
        mlkit = target in result["mlkit"]
        tiny = target in result["tiny"]
        counts["mlkit_hits"] += mlkit
        counts["tiny_hits"] += tiny
        counts[f"{query['kind']}_mlkit_hits"] += mlkit
        counts[f"{query['kind']}_tiny_hits"] += tiny
        counts["gained_queries"] += tiny and not mlkit
        counts["lost_queries"] += mlkit and not tiny
        counts["both_missed_queries"] += not mlkit and not tiny
        image = images[target]
        rows.append({
            "id": query["id"], "query": query["text"], "kind": query["kind"], "target": target,
            "mlkit_target_found": mlkit, "tiny_target_found": tiny,
            "outcome": "gained" if tiny and not mlkit else "lost" if mlkit and not tiny else "both_found" if tiny else "both_missed",
            "mlkit_other_matches": len(set(result["mlkit"]) - {target}),
            "tiny_other_matches": len(set(result["tiny"]) - {target}),
            "source": image["source"], "author": image["author"], "license": image["image_license"],
        })
    summaries = {}
    for engine in ("mlkit", "tiny"):
        summaries[engine] = {
            "ocr": latency([sample[engine]["ocr_ms"] for sample in samples.values()]),
            "decode_and_ocr": latency([sample["decode_ms"] + sample[engine]["ocr_ms"] for sample in samples.values()]),
            "incomplete_photos": sum(not sample[engine]["complete"] for sample in samples.values()),
            "cohorts": {
                cohort: latency([sample[engine]["ocr_ms"] for key, sample in samples.items() if images[key]["cohort"] == cohort])
                for cohort in ("hiertext", "mixed")
            },
        }
    baseline_file = BUILD / "selective-ocr-results/emulator.json"
    baseline_comparison = None
    if baseline_file.exists():
        baseline = json.loads(baseline_file.read_text(encoding="utf-8-sig"))
        previous = {row["id"]: row for row in baseline["queries"]}
        if previous.keys() == expected.keys():
            baseline_comparison = {
                "queries_with_different_match_sets": sum(
                    set(previous[key]["exhaustive_matches"]) != set(actual[key]["mlkit"]) for key in expected
                ),
                "target_hits_before": sum(row["target"] in previous[key]["exhaustive_matches"] for key, row in expected.items()),
                "target_hits_now": counts["mlkit_hits"],
            }
    summary = {
        "manifest_sha256": MANIFEST_SHA,
        "images": len(images), "queries": len(rows),
        "counts": dict(counts), "engines": summaries,
        "setup": report["setup"], "warmup_ms": report["warmup_ms"],
        "previous_mlkit_run": baseline_comparison,
    }
    output = args.report.parent
    write_json(output / "summary.json", summary)
    for name, records in (
        ("remembered-queries.csv", rows),
        ("gained-queries.csv", [row for row in rows if row["outcome"] == "gained"]),
        ("lost-queries.csv", [row for row in rows if row["outcome"] == "lost"]),
        ("tiny-missed-queries.csv", [row for row in rows if not row["tiny_target_found"]]),
    ):
        with (output / name).open("w", newline="", encoding="utf-8-sig") as stream:
            writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(records)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
