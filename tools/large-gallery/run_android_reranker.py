import argparse
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

import numpy as np

from tools.model_eval.core import resolve_image_path, sha256_file
from tools.model_eval.encoders import AppClipOnnxEncoder
from tools.model_eval.train_docci_reranker import encode_split, evaluation_backgrounds, validate_dataset

from .run_android_cache10k import run_fixture


def run(args):
    import onnxruntime as ort

    rows = validate_dataset(args.dataset)["qual_test"]
    backgrounds, source_hash = evaluation_backgrounds(args.background_fixture, args.regression, args.dataset)
    encoder = AppClipOnnxEncoder("appclip", "app-clip", args.model, ort)
    images, _ = encode_split(rows, args.dataset.parent, encoder, args.text_graph, ort)
    matrix = np.concatenate((images, backgrounds["qual_test"]))
    ids = [hashlib.sha256(f"reranker-eval:{index}".encode()).hexdigest()[:16] for index in range(len(matrix))]
    queries = [row["caption"] for row in rows]
    with TemporaryDirectory() as directory:
        root = Path(directory)
        vectors = root / "scene-vectors-f32le.bin"
        indexes = root / "scene-vector-ids.txt"
        matrix.astype("<f4", copy=False).tofile(vectors)
        indexes.write_bytes(("\n".join(ids) + "\n").encode("ascii"))
        payload = {
            "manifestSha256": sha256_file(args.dataset), "vectorsSha256": sha256_file(vectors),
            "idsSha256": sha256_file(indexes), "queries": queries, "depth": 100, "writeBatchSize": 8,
        }
        files = [vectors, indexes]
        if args.android_targets:
            archive = root / "target-images.zip"
            with ZipFile(archive, "x") as zip_file:
                for identifier, row in zip(ids[:len(rows)], rows, strict=True):
                    photo = resolve_image_path(row["file"], args.dataset.parent)
                    if sha256_file(photo) != row["sha256"]:
                        raise ValueError(f"Target image changed: {row['id']}")
                    zip_file.write(photo, f"{identifier}.jpg")
            payload["targetImages"] = [{"id": identifier, "sha256": row["sha256"]}
                                       for identifier, row in zip(ids[:len(rows)], rows, strict=True)]
            files.append(archive)
        report = run_fixture(args.adb, files, payload, len(matrix), args.output)
    if report["textGraph"] != args.text_graph.name or report["writeBatchSize"] != 8:
        raise ValueError("Unexpected emulator configuration")
    counts = dict(baselineHit1=0, baselineHit5=0, trainedHit1=0, trainedHit5=0, shortlistHits=0)
    gained = []
    lost = []
    for target, query, result in zip(ids[:len(rows)], rows, report["queries"], strict=True):
        before = result["topIds"]
        after = result["rerankedIds"]
        if len(before) != 100 or after[25:] != before[25:] or sorted(after[:25]) != sorted(before[:25]):
            raise ValueError("The reranker changed the shortlist membership")
        counts["shortlistHits"] += target in before[:25]
        counts["baselineHit1"] += target in before[:1]
        counts["trainedHit1"] += target in after[:1]
        counts["baselineHit5"] += target in before[:5]
        counts["trainedHit5"] += target in after[:5]
        if (target in before[:5]) != (target in after[:5]):
            (gained if target in after[:5] else lost).append(query["id"])
    samples = [row["rerankMs"] for row in report["queries"][1:]]
    cosines = report.get("androidTargetCosines", [])
    if args.android_targets and len(cosines) != len(rows):
        raise ValueError("Incomplete Android image encoding")
    result = {
        "queries": len(rows), "gallery": len(matrix), "counts": counts,
        "gainedTop5": gained, "lostTop5": lost,
        "backgroundVectorsSha256": source_hash, "datasetSha256": payload["manifestSha256"],
        "textGraphSha256": sha256_file(args.text_graph),
        "warmRerankMedianMs": float(np.median(samples)), "warmRerankP95Ms": float(np.percentile(samples, 95)),
        "scope": report["scope"],
        "androidTargets": len(cosines),
        **({"targetCosineMedian": float(np.median(cosines)), "targetCosineMin": min(cosines)} if cosines else {}),
    }
    (args.output / "quality.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result), flush=True)


def main():
    parser = argparse.ArgumentParser(description="Compare the Q4 residual reranker against its own Android baseline.")
    for name in ("adb", "dataset", "model", "text-graph", "background-fixture", "regression", "output"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--android-targets", action="store_true")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
