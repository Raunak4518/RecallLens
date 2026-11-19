import csv
import hashlib
import json
import shutil
import threading
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / ".benchmark-data" / "gallery-v1"
METADATA = ROOT / "app/build/datasets"
LIMIT = 2 * 1024**3
LICENSE = "https://creativecommons.org/licenses/by/2.0/"
SOURCES = {
    "relationships.csv": "https://storage.googleapis.com/openimages/v6/oidv6-validation-annotations-vrd.csv",
    "boxes.csv": "https://storage.googleapis.com/openimages/v5/validation-annotations-bbox.csv",
}


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def records(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        yield from csv.DictReader(source)


def order(value):
    return hashlib.sha256(("gallery-v1/" + value).encode()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    images = OUTPUT / "images"
    images.mkdir(exist_ok=True)
    if shutil.disk_usage(OUTPUT).free < LIMIT + 2 * 1024**3:
        raise RuntimeError("Need 4 GiB free before preparing the bounded corpus")
    for filename, url in SOURCES.items():
        target = OUTPUT / filename
        if not target.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                payload = response.read(64 * 1024**2 + 1)
            if len(payload) > 64 * 1024**2:
                raise RuntimeError("Annotation file exceeds metadata bound")
            target.write_bytes(payload)
    metadata = {row["ImageID"]: row for row in records(METADATA / "open-images-metadata.csv")
                if row["License"] == LICENSE and row["Author"] and row["OriginalLandingURL"].startswith("https://")}
    classes = {row[0]: row[1] for row in csv.reader((METADATA / "open-images-classes.csv").open(encoding="utf-8-sig"))}
    relationships = defaultdict(list)
    for row in records(OUTPUT / "relationships.csv"):
        if row["ImageID"] in metadata:
            relationships[row["ImageID"]].append(row)
    labels = defaultdict(lambda: {"positive": set(), "negative": set()})
    for row in records(METADATA / "open-images-labels.csv"):
        if row["ImageID"] in metadata and row["Confidence"] in ("0", "1"):
            labels[row["ImageID"]]["positive" if row["Confidence"] == "1" else "negative"].add(row["LabelName"])
    selected = sorted(relationships, key=order)[:800]
    selected += sorted(metadata.keys() - set(selected), key=order)[:2000 - len(selected)]
    write(OUTPUT / "selection.json", {"seed": "gallery-v1", "selected": selected, "count": len(selected),
        "annotation_sha256": {name: sha(OUTPUT / name) for name in SOURCES}, "no_model_outputs_used": True})
    lock = threading.Lock()
    used = sum(path.stat().st_size for path in OUTPUT.rglob("*") if path.is_file())
    def download(image_id):
        nonlocal used
        target = images / (image_id + ".jpg")
        url = "https://open-images-dataset.s3.amazonaws.com/validation/" + image_id + ".jpg"
        try:
            if target.exists():
                from PIL import Image
                with Image.open(target) as image:
                    image.verify()
                if not 0 < target.stat().st_size <= 8 * 1024**2:
                    raise RuntimeError("Cached image exceeds bounds")
                return image_id, None
            with urllib.request.urlopen(url, timeout=45) as response:
                payload = response.read(8 * 1024**2 + 1)
            if len(payload) > 8 * 1024**2:
                raise RuntimeError("Image exceeds 8 MiB limit")
            from PIL import Image
            import io
            with Image.open(io.BytesIO(payload)) as image:
                image.verify()
            with lock:
                if used + len(payload) > LIMIT:
                    raise RuntimeError("Corpus exceeds 2 GiB budget")
                used += len(payload)
            temporary = target.with_suffix(".download")
            temporary.write_bytes(payload)
            temporary.replace(target)
            return image_id, None
        except Exception as error:
            return image_id, type(error).__name__ + ": " + str(error)[:160]
    completed = []
    failures = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for number, (image_id, error) in enumerate(pool.map(download, selected), 1):
            if error:
                failures.append({"id": image_id, "error": error})
            else:
                row = metadata[image_id]
                completed.append({"id": image_id, "file": "images/" + image_id + ".jpg", "sha256": sha(images / (image_id + ".jpg")),
                    "bytes": (images / (image_id + ".jpg")).stat().st_size, "source": row["OriginalLandingURL"],
                    "author": row["Author"], "title": row["Title"], "license": row["License"],
                    "license_review": "publisher_metadata_only_not_independently_reverified", "rotation": row["Rotation"],
                    "positive_labels": sorted(labels[image_id]["positive"]), "negative_labels": sorted(labels[image_id]["negative"]),
                    "relationships": relationships.get(image_id, []), "partition": "validation" if int(order(image_id)[:8], 16) % 5 == 0 else "test"})
            if number % 100 == 0:
                print(f"Prepared {number}/{len(selected)}; valid={len(completed)} failed={len(failures)} bytes={used}", flush=True)
                write(OUTPUT / "progress.json", {"finished": number, "valid": len(completed), "failures": failures, "bytes": used})
    parent = {photo["id"]: photo["id"] for photo in completed}
    def group(value):
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value
    seen = {}
    for photo in completed:
        for token in ("sha:" + photo["sha256"], "source:" + photo["source"]):
            if token in seen:
                parent[group(photo["id"])] = group(seen[token])
            else:
                seen[token] = photo["id"]
    for photo in completed:
        photo["group_id"] = group(photo["id"])
        photo["partition"] = "validation" if int(order(photo["group_id"])[:8], 16) % 5 == 0 else "test"
    queries = []
    frequency = Counter(label for photo in completed for label in photo["positive_labels"])
    for label, count in frequency.most_common():
        positive = [photo["id"] for photo in completed if label in photo["positive_labels"]]
        negative = [photo["id"] for photo in completed if label in photo["negative_labels"]]
        if count >= 8 and len(negative) >= 8 and label in classes:
            queries.append({"query": "a photo of " + classes[label], "label": label, "positive": positive, "explicit_negative": negative,
                "unjudged": "All other gallery IDs are unknown, not negative", "type": "human_verified_category"})
    write(OUTPUT / "manifest.json", {"version": 1, "images": completed, "failures": failures, "bytes": used,
        "duplicate_group_count": sum(count > 1 for count in Counter(photo["group_id"] for photo in completed).values()),
        "images_without_verified_labels": sum(not photo["positive_labels"] and not photo["negative_labels"] for photo in completed),
        "queries": queries, "class_names": classes, "annotation_license": "https://creativecommons.org/licenses/by/4.0/",
        "status": "downloaded_not_inferred_or_independently_license_reverified", "source_pages": [
            "https://storage.googleapis.com/openimages/web/download_v7.html", "https://storage.googleapis.com/openimages/web/factsfigures_v7.html#licenses"]})
    print(json.dumps({"valid": len(completed), "failed": len(failures), "queries": len(queries), "bytes": used}), flush=True)


if __name__ == "__main__":
    main()
