import argparse
import json
from pathlib import Path
import struct

import numpy as np

from tools.model_eval.core import sha256_file


def export(run, destination):
    report = json.loads((run / "results.json").read_bytes())
    weights = run / "residual-weights.npz"
    digest = sha256_file(weights)
    if digest != report["weightsSha256"] or report["bestEpoch"] < 1:
        raise ValueError("Expected a verified nonbaseline training checkpoint")
    rank = report["rank"]
    depth = report["depth"]
    if not 1 <= rank <= 128 or not 5 <= depth <= 100:
        raise ValueError("Unsupported reranker rank or depth")
    with np.load(weights, allow_pickle=False) as archive:
        projection = archive["projection"]
        residual = archive["residual"]
    for values, shape in ((projection, (rank, 512)), (residual, (512, rank))):
        if values.dtype != np.float32 or values.shape != shape or not np.isfinite(values).all():
            raise ValueError("Invalid reranker weights")
    text_digest = bytes.fromhex(report["textGraphSha256"])
    if len(text_digest) != 32:
        raise ValueError("Expected SHA-256 provenance for the training text graph")
    header = struct.pack("<4sIII32s32s", b"PLR1", 512, rank, depth, text_digest, bytes.fromhex(digest))
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output:
        output.write(header)
        for matrix in (projection, residual):
            output.write(matrix.astype("<f4", copy=False).tobytes(order="C"))
    return {"bytes": destination.stat().st_size, "sha256": sha256_file(destination), "rank": rank, "depth": depth}


def main():
    parser = argparse.ArgumentParser(description="Export a checked DOCCI reranker checkpoint for Android evaluation.")
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(export(args.run, args.output)))


if __name__ == "__main__":
    main()
