import argparse
import json
import os
import shutil
from pathlib import Path

from prepare_g0_clip import ARTIFACTS, ROOT, sha256

Q4_NAME = "text_model_q4.onnx"
Q4_SHA256 = "e4ccd15d806b8af841a036884b42034535d58e04c52df00f56e73f72d3c166d5"

FIXTURES = (
    "5a59f862b23f55f2",
    "0888c56158b208f7",
    "57baad16deda0a69",
    "aa10494564a33c4d",
    "c1f76f9c5ec9d48c",
)


def stage_fixtures(project: Path) -> None:
    root = project / "app" / "build"
    corpus = root / "datasets"
    attribution = json.loads((corpus / "open-images-retrieval.json").read_text(encoding="utf-8"))
    images = {row["image_id"]: row for row in attribution["images"]}
    destination = root / "public-evaluation" / "clip-local"
    destination.mkdir(parents=True, exist_ok=True)
    for index, image in enumerate(FIXTURES, start=1):
        source = corpus / "open-images" / f"{image}.jpg"
        if sha256(source) != images[image]["sha256"]:
            raise SystemExit(f"Unexpected bytes in cached fixture {image}")
        shutil.copyfile(source, destination / f"photo-{index:02}.jpg")
    print(f"Staged {len(FIXTURES)} existing cached photos for the test APK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT)
    parser.add_argument("--q4-source", type=Path)
    parser.add_argument("--fixtures", action="store_true")
    args = parser.parse_args()
    files = {Path(name).name: (args.source / Path(name).name, digest) for name, digest in ARTIFACTS.items()}
    if args.q4_source is not None:
        files[Q4_NAME] = (args.q4_source, Q4_SHA256)
    for source, expected in files.values():
        if not source.is_file() or sha256(source) != expected:
            raise SystemExit(f"Expected pinned local CLIP asset: {source}")
    project = Path(__file__).resolve().parents[1]
    destination = project / ".local-models" / "clip"
    destination.mkdir(parents=True, exist_ok=True)
    for name, (source, expected) in files.items():
        target = destination / name
        if target.is_file() and sha256(target) == expected:
            continue
        temporary = target.with_suffix(target.suffix + ".part")
        shutil.copyfile(source, temporary)
        if sha256(temporary) != expected:
            raise SystemExit(f"CLIP asset changed while staging: {name}")
        os.replace(temporary, target)
    print(f"Staged {len(files)} verified CLIP files from the local cache")
    if args.fixtures:
        stage_fixtures(project)


if __name__ == "__main__":
    main()
