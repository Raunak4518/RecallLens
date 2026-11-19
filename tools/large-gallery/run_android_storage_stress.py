import argparse
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np

from tools.model_eval.core import sha256_file

from .run_android_cache10k import run_fixture


def run(args):
    fixture = args.fixture
    source = json.loads((fixture / "input.json").read_bytes())
    vectors = fixture / "scene-vectors-f32le.bin"
    ids = fixture / "scene-vector-ids.txt"
    if sha256_file(vectors) != source["vectorsSha256"] or sha256_file(ids) != source["idsSha256"]:
        raise ValueError("The original fixture vectors or IDs changed")
    originals = vectors.read_bytes()
    count = len(ids.read_text(encoding="ascii").splitlines())
    if count != 10_910 or len(originals) != count * 2048 or len(source["queries"]) != 200:
        raise ValueError("Expected the fixed 10,910-photo DOCCI fixture")
    queries = source["queries"][::10]
    args.output.mkdir(parents=True, exist_ok=False)
    summary = []
    for size in args.rows:
        repeats, tail = divmod(size, count)
        with TemporaryDirectory(dir=args.output) as directory:
            binary = Path(directory) / vectors.name
            identifiers = Path(directory) / ids.name
            with binary.open("wb") as output:
                for _ in range(repeats):
                    output.write(originals)
                output.write(memoryview(originals)[:tail * 2048])
            identifiers.write_bytes("".join(f"{i + 1:016x}\n" for i in range(size)).encode("ascii"))
            payload = {
                "manifestSha256": hashlib.sha256(f"{source['manifestSha256']}:storage-stress-v1:{size}".encode()).hexdigest(),
                "vectorsSha256": sha256_file(binary), "idsSha256": sha256_file(identifiers), "queries": queries,
                "storageStress": True, "depth": 24, "writeBatchSize": 32,
            }
            report = run_fixture(args.adb, [binary, identifiers], payload, size, args.output / str(size), timeout=1200)
        if report["photos"] != size or report["syntheticText"]["rows"] != size:
            raise ValueError("Storage run did not cover the requested row count")
        samples = report["queries"][1:]
        row = {
            "rows": size, "sceneMiB": report["sceneDatabaseBytes"] / 1024**2,
            "encryptedMiB": report["encryptedBytes"] / 1024**2,
            "sceneWriteSeconds": report["writeMs"] / 1000, "sceneRestoreSeconds": report["restoreMs"] / 1000,
            "textWriteSeconds": report["syntheticText"]["writeMs"] / 1000,
            "sceneRankP95Ms": float(np.percentile([item["rankMs"] for item in samples], 95)),
            "encodeThenRankP95Ms": float(np.percentile([item["encodeMs"] + item["rankMs"] for item in samples], 95)),
            "pageStats": report["pageStats"],
        }
        summary.append(row)
        (args.output / "summary.json").write_text(json.dumps({
            "sourceVectorsSha256": source["vectorsSha256"], "queryCount": len(queries), "runs": summary,
            "scope": "Synthetic storage stress. Scene vectors repeat and text is generated; ranking accuracy is unmeasured.",
        }, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(row), flush=True)


def main():
    parser = argparse.ArgumentParser()
    for name in ("adb", "fixture", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--rows", type=int, nargs="+", choices=(25_000, 50_000), default=[25_000, 50_000])
    args = parser.parse_args()
    if len(set(args.rows)) != len(args.rows):
        parser.error("Each row count must be unique")
    run(args)


if __name__ == "__main__":
    main()
