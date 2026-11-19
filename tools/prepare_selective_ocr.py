import argparse
import csv
import gzip
import json
import shutil
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from time import sleep
from urllib.error import HTTPError
from uuid import uuid4

from PIL import Image

from benchmark_full_photo_ocr import TEST_METADATA, eligible, lines, photo_metadata, snippets
from benchmark_public_datasets import BUILD, DATA, IMAGE_LICENSE, SOURCES, attribution, digest, fetch, metadata, order_key, write_json


ROOT = BUILD / "public-evaluation" / "selective-ocr"
TEXT_COUNT = 180
GALLERY_COUNT = 120
TRAIN_METADATA = "https://storage.googleapis.com/openimages/2018_04/train/train-images-boxable-with-rotation.csv"
TRAIN_METADATA_BYTES = 638_407_721


def rank(group, value):
    return order_key(f"selective-ocr-v1/{group}/{value}")


def download_photo(url, destination):
    if destination.is_file():
        return destination
    temporary = destination.with_name(f"{destination.stem}-{uuid4().hex}.download")
    for attempt in range(4):
        try:
            fetch(url, temporary).replace(destination)
            return destination
        except OSError as error:
            if attempt == 3 or isinstance(error, HTTPError) and error.code in (401, 403, 404):
                raise
            sleep(attempt + 1)
        finally:
            temporary.unlink(missing_ok=True)
            temporary.with_name(temporary.name + ".part").unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--train-metadata", type=str)
    args = parser.parse_args()
    annotations = fetch(SOURCES["hiertext-validation.jsonl.gz"], DATA / "hiertext-validation.jsonl.gz")
    with gzip.open(annotations, "rt", encoding="utf-8") as source:
        hiertext = {entry["image_id"]: entry for entry in json.load(source)["annotations"]}
    validation = metadata()
    test_metadata = fetch(TEST_METADATA, DATA / "open-images-test-metadata.csv")
    licenses = photo_metadata(hiertext.keys(), test_metadata)
    train_file = Path(args.train_metadata) if args.train_metadata else fetch(TRAIN_METADATA, DATA / "selective-train-metadata.csv")
    if train_file.stat().st_size != TRAIN_METADATA_BYTES:
        raise ValueError(f"Train metadata must supply {TRAIN_METADATA_BYTES} bytes")
    split_metadata = {}
    with train_file.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            photo_id = row["ImageID"]
            if (photo_id in hiertext and row["License"] == IMAGE_LICENSE and row["Author"]
                    and row["OriginalLandingURL"].startswith("https://")):
                split_metadata[photo_id] = row
    licenses.update(split_metadata)
    earlier = set()
    for root in ("hiertext", "hiertext-full", "hybrid-ocr"):
        earlier.update(path.stem for path in (ROOT.parent / root).glob("*.jpg"))
        earlier.update(path.stem for path in (ROOT.parent / root).glob("*.png"))
    candidates = {
        key for key in hiertext.keys() & licenses.keys() - earlier
        if any(eligible(line) and next(snippets(line["text"], 2), None) for line in lines(hiertext[key]))
    }
    targets = sorted(candidates, key=lambda key: rank("targets", key))[:TEXT_COUNT]
    gallery = sorted(validation.keys() - hiertext.keys() - earlier, key=lambda key: rank("gallery", key))[:GALLERY_COUNT]
    if len(targets) != TEXT_COUNT or len(gallery) != GALLERY_COUNT:
        raise ValueError(f"Required {TEXT_COUNT} text and {GALLERY_COUNT} mixed photos, got {len(targets)} and {len(gallery)}")
    ids = sorted(targets + gallery, key=lambda key: rank("paired-order", key))
    ROOT.mkdir(parents=True, exist_ok=True)
    target_set = set(targets)

    def stage(photo_id):
        row = licenses[photo_id] if photo_id in target_set else validation[photo_id]
        split = row["Subset"]
        url = f"https://open-images-dataset.s3.amazonaws.com/{split}/{photo_id}.jpg"
        path = download_photo(url, DATA / f"selective-ocr-{split}" / f"{photo_id}.jpg")
        with Image.open(path) as image:
            image.verify()
        output = ROOT / path.name
        shutil.copyfile(path, output)
        return {**attribution(row, output), "download_url": url,
                "cohort": "hiertext" if photo_id in target_set else "mixed"}

    with ThreadPoolExecutor(max_workers=12) as pool:
        images = list(pool.map(stage, ids))

    queries = []
    known_matches = defaultdict(set)
    for photo_id in targets:
        for line in lines(hiertext[photo_id]):
            for count in (1, 2):
                for snippet in snippets(line["text"], count):
                    known_matches[snippet].add(photo_id)
    for photo_id in targets:
        for count in (1, 2):
            choices = {
                snippet for line in lines(hiertext[photo_id]) if eligible(line)
                for snippet in snippets(line["text"], count)
            }
            text = min(choices, key=lambda value: (len(known_matches[value]), rank(photo_id, value)))
            queries.append({
                "id": f"q{len(queries) + 1:03}", "target": photo_id, "text": text,
                "kind": "word" if count == 1 else "phrase",
                "annotated_matches": sorted(known_matches[text]),
            })
    manifest = {
        "version": 1,
        "annotations_sha256": digest(annotations),
        "metadata_sha256": digest(DATA / "open-images-metadata.csv"),
        "test_metadata_sha256": digest(test_metadata),
        "train_metadata_sha256": digest(train_file),
        "annotation_license": "https://creativecommons.org/licenses/by-sa/4.0/",
        "images": images,
        "queries": queries,
    }
    destination = ROOT / "manifest.json"
    write_json(destination, manifest)
    print(f"{len(images)} images, {len(queries)} queries, {sum((ROOT / (key + '.jpg')).stat().st_size for key in ids)} bytes")
    print(f"Manifest SHA-256: {digest(destination)}")


if __name__ == "__main__":
    main()
