import json
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "app/build/large-ocr-comparison"


def main():
    report = json.loads((DIRECTORY / "emulator.json").read_text(encoding="utf-8-sig"))
    images = {row["id"]: row for row in report["images"]}
    ordered = sorted(images, key=lambda key: sha256(("ocr-route-v1/" + key).encode()).hexdigest())
    tuning = set(ordered[:150])
    held_out = set(ordered[150:])
    policies = {
        "mlkit": lambda image: (True, False),
        "tiny": lambda image: (False, True),
        "both": lambda image: (True, True),
    }
    for threshold in (0, 16, 64, 256):
        policies[f"tiny_then_mlkit_under_{threshold}_characters"] = (
            lambda image, bound=threshold: (sum(len(line) for line in image["tiny"]["lines"]) <= bound, True)
        )
    outcomes = []
    for name, choose in policies.items():
        for cohort, identifiers in (("tuning", tuning), ("held_out", held_out)):
            selected = {key: choose(images[key]) for key in identifiers}
            queries = [query for query in report["queries"] if query["target"] in identifiers]
            hits = sum(
                (selected[query["target"]][0] and query["target"] in query["mlkit"])
                or (selected[query["target"]][1] and query["target"] in query["tiny"])
                for query in queries
            )
            cost = sum(
                images[key]["decode_ms"]
                + (images[key]["mlkit"]["ocr_ms"] if engines[0] else 0)
                + (images[key]["tiny"]["ocr_ms"] if engines[1] else 0)
                for key, engines in selected.items()
            )
            outcomes.append({
                "policy": name, "cohort": cohort, "photos": len(identifiers), "queries": len(queries),
                "target_hits": hits, "summed_recorded_component_ms": cost,
                "mlkit_photos": sum(engines[0] for engines in selected.values()),
                "tiny_photos": sum(engines[1] for engines in selected.values()),
            })
    output = {
        "manifest_sha256": report["manifest_sha256"],
        "method": "Retrospective output simulation with fixed image-level split. Component sums exclude new scheduling, model setup, route evaluation and storage.",
        "tuning_ids": sorted(tuning), "held_out_ids": sorted(held_out), "results": outcomes,
    }
    (DIRECTORY / "routing-analysis.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    for row in outcomes:
        if row["cohort"] == "held_out":
            print(row)


if __name__ == "__main__":
    main()
