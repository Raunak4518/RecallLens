import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import statistics

PALETTE = {"RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE", "BLACK", "GRAY"}


def timing(values):
    return {"total_ms": sum(values), "median_ms": statistics.median(values),
            "p95_ms": sorted(values)[math.ceil(len(values) * .95) - 1]} if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    output = args.report.parent
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    manifest_bytes = (output / "device/manifest.json").read_bytes()
    if report["manifest_sha256"] != hashlib.sha256(manifest_bytes).hexdigest():
        raise ValueError("Manifest differs from the evaluated fixture")
    manifest = json.loads(manifest_bytes)
    expected = {row["image_id"]: row for row in manifest["images"]}
    if len(report["images"]) != len(expected) or {row["image_id"] for row in report["images"]} != expected.keys():
        raise ValueError("Missing or duplicate evaluated images")
    counts, missing, unsupported = Counter(), Counter(), Counter()
    query_rows, failures, negative_rows = [], [], []
    for image in report["images"]:
        source = expected[image["image_id"]]
        if source["sha256"] != image["source_sha256"] or len(source["annotations"]) != len(image["outcomes"]):
            raise ValueError("Unexpected image or annotation count")
        counts["images"] += 1
        counts["annotated_people"] += len(image["outcomes"])
        other_colors = [set(row["expected_colors"]) for row in image["outcomes"]]
        if image["negative_control"]:
            counts["negative_control_images"] += 1
            detected = image["detected_evidence"]
            alerts = [row for row in detected if row["state"] == "found" and max(row["colors"].values(), default=0) >= .5]
            counts["negative_images_with_faces"] += image["faces"] > 0
            counts["negative_images_with_color_alerts"] += bool(alerts)
            negative_rows.append({"image_id": image["image_id"], "faces": image["faces"], "color_alerts": len(alerts)})
        for index, outcome in enumerate(image["outcomes"]):
            annotation = source["annotations"][index]
            labels = set(annotation.get("colors", [annotation.get("color")]))
            if labels != set(outcome["expected_colors"]) or annotation["face"] != outcome["face"]:
                raise ValueError("Device outcome does not match its annotation")
            unsupported.update(labels - PALETTE)
            fractions = outcome.get("colors", {})
            found = outcome["state"] == "found"
            counts["people_with_found_evidence"] += found
            counts["people_with_detected_faces"] += outcome.get("reason") != "NO_FACE"
            counts["dominant_color_correct"] += found and outcome["dominant_color"] in labels
            counts["people_with_any_expected_color_at_50pct"] += any(fractions.get(color, 0) >= .5 for color in labels)
            counts["people_with_any_expected_color_at_25pct"] += any(fractions.get(color, 0) >= .25 for color in labels)
            if not found:
                missing[outcome["reason"]] += 1
            other = set().union(*(colors for pos, colors in enumerate(other_colors) if pos != index)) - labels
            for color in sorted(PALETTE | labels):
                positive = color in labels
                predicted = fractions.get(color, 0) >= .5
                related = .25 <= fractions.get(color, 0) < .5
                bucket = "true_positive" if positive and predicted else "false_negative" if positive else "false_positive" if predicted else "true_negative"
                counts["color_queries_" + bucket] += 1
                if color in other:
                    counts["other_person_color_negative_queries"] += 1
                    counts["other_person_color_false_alerts"] += predicted
                query_rows.append({"image_id": image["image_id"], "person": index, "color": color,
                                   "expected": positive, "fraction": fractions.get(color, 0),
                                   "strict_rule": predicted, "related_rule": related, "outcome": bucket,
                                   "other_person_color": color in other})
            if not found or outcome["dominant_color"] not in labels:
                failures.append({"image_id": image["image_id"], **outcome})
    summary = {
        "manifest_sha256": report["manifest_sha256"], "cohort_sha256": manifest["cohort_sha256"],
        "overlap_policy": report.get("overlap_policy", "WITHHOLD"),
        "counts": dict(counts), "missing_reasons": dict(missing), "unsupported_expected_colors": dict(unsupported),
        "setup_ms": report["setup_ms"], "decode": timing([row["decode_ms"] for row in report["images"]]),
        "pipeline": timing([row["elapsed_ms"] for row in report["images"]]),
        "positive_photo_pipeline": timing([row["elapsed_ms"] for row in report["images"] if not row["negative_control"]]),
        "model_sha256": report["model_sha256"], "negative_controls": negative_rows,
        "limits": "Provisional single-reviewer visible-color annotations; no identity retrieval, Named search, independent hold-out or physical-phone accuracy claim. Color queries apply area thresholds to saved pipeline output; they do not measure full app query latency or ranking.",
    }
    for name, data in (("summary.json", summary), ("color-queries.json", query_rows), ("failures.json", failures)):
        (output / name).write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
