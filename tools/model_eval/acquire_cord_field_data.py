import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request


REVISION = "7f0115a4b758a71d6473b8d085751692da2fef98"
REPOSITORY = "naver-clova-ix/cord-v2"
LICENSE = "CC BY 4.0"
FILES = {
    "data/train-00000-of-00004-b4aaeceff1d90ecb.parquet": {
        "bytes": 490224630,
        "sha256": "da3994eee1bf9bd3c57f0d53a72c3a6812c8696c5ba26245987949ddf73483cc",
        "split": "train",
        "rows_expected": 200,
    },
    "data/validation-00000-of-00001-cc3c5779fe22e8ca.parquet": {
        "bytes": 242080800,
        "sha256": "0d0f6dac11fdcc549de2746aa9f53136a3bc22a2a1aff2b0b847f7622ad60c15",
        "split": "validation",
        "rows_expected": 100,
    },
    "data/test-00000-of-00001-9c204eb3f4e11791.parquet": {
        "bytes": 234202795,
        "sha256": "51c65f1788faff392abe2a0b55b023eb23e9be551c509138eaa3a832514224e7",
        "split": "test",
        "rows_expected": 100,
    },
}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def acquire(root):
    root.mkdir(parents=True, exist_ok=True)
    files = []
    for name, expected in FILES.items():
        destination = root / Path(name).name
        if destination.is_file() and destination.stat().st_size == expected["bytes"] and digest(destination) == expected["sha256"]:
            files.append({"path": destination.name, **expected, "reused": True})
            continue
        temporary = destination.with_suffix(destination.suffix + ".part")
        temporary.unlink(missing_ok=True)
        url = f"https://huggingface.co/datasets/{REPOSITORY}/resolve/{REVISION}/{name}?download=true"
        request = urllib.request.Request(url, headers={"User-Agent": "photo-library-field-evaluation/1.0"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                output.write(block)
        if temporary.stat().st_size != expected["bytes"] or digest(temporary) != expected["sha256"]:
            temporary.unlink(missing_ok=True)
            raise ValueError(f"Dataset file integrity check failed: {name}")
        os.replace(temporary, destination)
        files.append({"path": destination.name, **expected, "reused": False})
        print(f"Verified {destination.name} ({expected['bytes']} bytes)", flush=True)
    receipt = {
        "repository": REPOSITORY,
        "revision": REVISION,
        "license": LICENSE,
        "attribution": "CORD v2 by NAVER CLOVA; Park et al., CORD: A Consolidated Receipt Dataset for Post-OCR Parsing, 2019",
        "scope": "One pinned train shard plus the complete official validation and test splits. The other three train shards are deliberately not downloaded.",
        "files": files,
        "total_bytes": sum(row["bytes"] for row in files),
    }
    (root / "acquisition.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path(".benchmark-data/document-field-cord-001/raw"))
    args = parser.parse_args()
    print(json.dumps(acquire(args.output), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
