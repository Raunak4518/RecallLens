import argparse
import csv
import json
from collections import Counter
from pathlib import Path

from benchmark_public_datasets import BUILD, digest, write_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    args = parser.parse_args()
    manifest_path = BUILD / "public-evaluation" / "selective-ocr" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    report = json.loads(args.result.read_text(encoding="utf-8"))
    images = {row["image_id"]: row for row in manifest["images"]}
    samples = {row["id"]: row for row in report["images"]}
    expected = {row["id"]: row for row in manifest["queries"]}
    if images.keys() != samples.keys() or expected.keys() != {row["id"] for row in report["queries"]}:
        raise ValueError("The emulator report does not match the frozen manifest")
    total_before = sum(row["exhaustive_ms"] for row in samples.values())
    total_after = sum(row["selective_ms"] for row in samples.values())
    totals = Counter()
    misses = []
    rows = []
    for result in report["queries"]:
        query = expected[result["id"]]
        if any(result[key] != value for key, value in query.items()):
            raise ValueError(f"Mismatched query {query['id']}")
        target = query["target"]
        before = target in result["exhaustive_matches"]
        after = target in result["selective_matches"]
        totals[f"{query['kind']}_queries"] += 1
        totals[f"{query['kind']}_exhaustive_hits"] += before
        totals[f"{query['kind']}_selective_hits"] += after
        lost = before and not after
        row = {
            "query_id": query["id"], "query": query["text"], "kind": query["kind"], "target": target,
            "exhaustive_target_found": before, "selective_target_found": after,
            "target_sent_to_ocr": samples[target]["selected"], "new_miss": lost,
            "other_selective_hits": sum(photo_id != target for photo_id in result["selective_matches"]),
            "source": images[target]["source"], "author": images[target]["author"],
            "license": images[target]["image_license"],
        }
        rows.append(row)
        if lost:
            misses.append(row)

    for name, data in (("remembered-queries.csv", rows), ("newly-missed-queries.csv", misses)):
        with args.result.with_name(name).open("w", newline="", encoding="utf-8-sig") as output:
            writer = csv.DictWriter(output, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(data)
    summary = {
        "manifest_sha256": digest(manifest_path),
        "photos": len(samples),
        "queries": len(rows),
        "ocr_photos": sum(row["selected"] for row in samples.values()),
        "skipped_by_cohort": dict(Counter(images[key]["cohort"] for key, row in samples.items() if not row["selected"])),
        "exhaustive_ms": total_before,
        "selective_ms": total_after,
        "preview_and_selection_ms": sum(row["gate_ms"] for row in samples.values()),
        "saved_ms": total_before - total_after,
        "saved_percent": 100 * (1 - total_after / total_before),
        "exhaustive_target_hits": sum(row["exhaustive_target_found"] for row in rows),
        "selective_target_hits": sum(row["selective_target_found"] for row in rows),
        "new_misses": len(misses),
        "missed_target_photos": len({row["target"] for row in misses}),
        "new_misses_with_no_selective_result": sum(row["other_selective_hits"] == 0 for row in misses),
        "by_query_kind": dict(totals),
    }
    write_json(args.result.with_name("summary.json"), summary)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
