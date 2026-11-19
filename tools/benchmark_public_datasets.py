import argparse
import csv
import gzip
import hashlib
import json
import re
import shutil
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from time import perf_counter

from PIL import Image, ImageOps

BUILD = Path(__file__).resolve().parents[1] / "app" / "build"
DATA = BUILD / "datasets"
MODELS = BUILD / "feasibility-models" / "clip-6b7c523"
OCR = BUILD / "public-evaluation" / "hiertext"
IMAGE_BASE = "https://open-images-dataset.s3.amazonaws.com/validation/"
IMAGE_LICENSE = "https://creativecommons.org/licenses/by/2.0/"
SOURCES = {
    "open-images-labels.csv": "https://storage.googleapis.com/openimages/v5/validation-annotations-human-imagelabels-boxable.csv",
    "open-images-metadata.csv": "https://storage.googleapis.com/openimages/2018_04/validation/validation-images-with-rotation.csv",
    "open-images-classes.csv": "https://storage.googleapis.com/openimages/v5/class-descriptions-boxable.csv",
    "hiertext-validation.jsonl.gz": "https://raw.githubusercontent.com/google-research-datasets/hiertext/main/gt/validation.jsonl.gz",
}
CATEGORIES = ("Airplane", "Bicycle", "Cat", "Dog", "Flower", "Pizza")


def fetch(url: str, target: Path) -> Path:
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_name(target.name + ".part")
        with urllib.request.urlopen(url, timeout=35) as source, temp.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        temp.replace(target)
    return target


def digest(file: Path) -> str:
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(file: Path, record: dict) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def csv_records(file: Path):
    with file.open(encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


def metadata() -> dict[str, dict]:
    path = fetch(SOURCES["open-images-metadata.csv"], DATA / "open-images-metadata.csv")
    return {
        row["ImageID"]: row for row in csv_records(path)
        if row["License"] == IMAGE_LICENSE and row["Rotation"] == "0.0"
        and row["Author"] and row["OriginalLandingURL"].startswith("https://")
    }


def order_key(value: str) -> str:
    return hashlib.sha256(f"photolibrary-g0-v1/{value}".encode()).hexdigest()


def get_image(image_id: str) -> Path:
    if not re.fullmatch("[0-9a-f]{16}", image_id):
        raise ValueError("Invalid Open Images ID")
    return fetch(f"{IMAGE_BASE}{image_id}.jpg", DATA / "open-images" / f"{image_id}.jpg")


def attribution(row: dict, path: Path) -> dict:
    return {
        "image_id": row["ImageID"], "author": row["Author"], "title": row["Title"],
        "source": row["OriginalLandingURL"], "author_profile": row["AuthorProfileURL"],
        "image_license": row["License"], "download_url": IMAGE_BASE + path.name,
        "sha256": digest(path),
    }


def retrieval(source_metadata: dict) -> None:
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer

    classes_file = fetch(SOURCES["open-images-classes.csv"], DATA / "open-images-classes.csv")
    with classes_file.open(encoding="utf-8-sig", newline="") as stream:
        classes = {key: label for key, label in csv.reader(stream) if label in CATEGORIES}
    labels_file = fetch(SOURCES["open-images-labels.csv"], DATA / "open-images-labels.csv")
    labels = defaultdict(lambda: defaultdict(list))
    for row in csv_records(labels_file):
        if row["LabelName"] in classes and row["ImageID"] in source_metadata:
            labels[classes[row["LabelName"]]][row["Confidence"]].append(row["ImageID"])

    trials = {}
    for label in CATEGORIES:
        trial = {}
        for confidence in ("1", "0"):
            available = set(labels[label][confidence]) - set(labels[label]["0" if confidence == "1" else "1"])
            ordered = sorted(available, key=lambda value: order_key(f"{label}/{confidence}/{value}"))
            if len(ordered) < 8:
                raise ValueError(f"Insufficient human-verified {confidence} labels for {label}")
            trial[confidence] = ordered[:8]
        trials[label] = trial
    ids = sorted({image_id for trial in trials.values() for group in trial.values() for image_id in group})
    with ThreadPoolExecutor(max_workers=8) as pool:
        paths = dict(zip(ids, pool.map(get_image, ids)))

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    config = json.loads((MODELS / "preprocessor_config.json").read_text())
    tokenizer = Tokenizer.from_file(str(MODELS / "tokenizer.json"))
    start = perf_counter()
    vision = ort.InferenceSession(str(MODELS / "vision_model_quantized.onnx"), options, providers=["CPUExecutionProvider"])
    text = ort.InferenceSession(str(MODELS / "text_model_quantized.onnx"), options, providers=["CPUExecutionProvider"])
    means = np.asarray(config["image_mean"], dtype=np.float32)
    deviations = np.asarray(config["image_std"], dtype=np.float32)
    vectors = {}
    for image_id in ids:
        with Image.open(paths[image_id]) as source:
            rgb = ImageOps.exif_transpose(source).convert("RGB")
            width, height = rgb.size
            resized = rgb.resize((int(width * 224 / min(rgb.size)), int(height * 224 / min(rgb.size))), Image.Resampling.BICUBIC)
            left, top = (resized.width - 224) // 2, (resized.height - 224) // 2
            pixels = np.asarray(resized.crop((left, top, left + 224, top + 224)), dtype=np.float32)
        normalized = (pixels * config["rescale_factor"] - means) / deviations
        vector = vision.run(["image_embeds"], {"pixel_values": normalized.transpose(2, 0, 1)[None]})[0][0]
        vectors[image_id] = vector / np.linalg.norm(vector)

    results = []
    for label, trial in trials.items():
        query = f"a photo of a {label.lower()}"
        tokens = np.asarray([tokenizer.encode(query).ids], dtype=np.int64)
        vector = text.run(["text_embeds"], {"input_ids": tokens})[0][0]
        vector /= np.linalg.norm(vector)
        ranked = sorted(trial["1"] + trial["0"], key=lambda image_id: (-float(vector @ vectors[image_id]), image_id))
        relevant = [image_id in trial["1"] for image_id in ranked]
        hits = sum(relevant[:5])
        precision_sum = sum(sum(relevant[: index + 1]) / (index + 1) for index, match in enumerate(relevant) if match)
        results.append({
            "class": label, "query": query, "positive_ids": trial["1"], "negative_ids": trial["0"],
            "top_1_correct": relevant[0], "precision_at_5": hits / 5, "recall_at_5": hits / 8,
            "average_precision": precision_sum / 8, "ranking": ranked,
        })
    write_json(DATA / "open-images-retrieval.json", {
        "annotation_license": "https://creativecommons.org/licenses/by/4.0/",
        "annotation_sha256": digest(labels_file), "image_count": len(ids),
        "metadata_sha256": digest(DATA / "open-images-metadata.csv"),
        "runtime": ort.__version__, "elapsed_seconds": round(perf_counter() - start, 3),
        "trials": results, "images": [attribution(source_metadata[i], paths[i]) for i in ids],
    })
    mean_ap = sum(result["average_precision"] for result in results) / len(results)
    top_1 = sum(result["top_1_correct"] for result in results)
    print(f"Open Images: {len(ids)} images, {top_1}/{len(results)} top-1, mAP={mean_ap:.3f}", flush=True)


def ocr(source_metadata: dict) -> None:
    annotations_path = fetch(SOURCES["hiertext-validation.jsonl.gz"], DATA / "hiertext-validation.jsonl.gz")
    with gzip.open(annotations_path, "rt", encoding="utf-8") as stream:
        annotations = json.load(stream)["annotations"]
    available = [row for row in annotations if row["image_id"] in source_metadata]
    available.sort(key=lambda row: order_key(row["image_id"]))
    manifest = []
    used_images = {}
    OCR.mkdir(parents=True, exist_ok=True)
    for entry in available:
        if len(used_images) >= 12:
            break
        lines = [
            line for paragraph in entry["paragraphs"] for line in paragraph["lines"]
            if line["legible"] and not line["handwritten"] and not line["vertical"]
            and re.fullmatch(r"[A-Za-z0-9 .,!?:'&/()\-]{5,64}", line["text"])
            and re.search("[A-Za-z]", line["text"])
        ]
        if not lines:
            continue
        image_id = entry["image_id"]
        path = get_image(image_id)
        with Image.open(path) as source:
            rgb = ImageOps.exif_transpose(source).convert("RGB")
        scale_x = rgb.width / entry["image_width"]
        scale_y = rgb.height / entry["image_height"]
        if abs(scale_x / scale_y - 1) > 0.015:
            continue
        accepted = 0
        for line in lines:
            xs, ys = zip(*line["vertices"])
            x, y = min(xs), min(ys)
            width, height = max(xs) - x, max(ys) - y
            if width < 2 * height or height * scale_y < 18:
                continue
            margin = 0.1 * height
            crop = rgb.crop((
                max(0, int((x - margin) * scale_x)), max(0, int((y - margin) * scale_y)),
                min(rgb.width, int((x + width + margin) * scale_x + 1)),
                min(rgb.height, int((y + height + margin) * scale_y + 1)),
            ))
            if crop.width < 2 or crop.height < 2:
                continue
            name = f"{image_id}-{accepted}.png"
            crop.thumbnail((1600, 400), Image.Resampling.LANCZOS)
            pad_x, pad_y = max(0, 32 - crop.width), max(0, 32 - crop.height)
            if pad_x or pad_y:
                crop = ImageOps.expand(
                    crop,
                    border=(pad_x // 2, pad_y // 2, pad_x - pad_x // 2, pad_y - pad_y // 2),
                    fill=crop.getpixel((0, 0)),
                )
            crop.save(OCR / name)
            manifest.append({"file": name, "image_id": image_id, "reference": line["text"], "sha256": digest(OCR / name)})
            accepted += 1
            if accepted >= 3:
                break
        if accepted:
            used_images[image_id] = attribution(source_metadata[image_id], path)
    if len(used_images) != 12:
        raise ValueError(f"Only {len(used_images)} usable HierText images")
    write_json(OCR / "manifest.json", {"lines": manifest})
    write_json(DATA / "hiertext-attribution.json", {
        "annotation_license": "https://creativecommons.org/licenses/by-sa/4.0/",
        "annotation_sha256": digest(annotations_path), "images": list(used_images.values()),
        "split": "validation",
    })
    print(f"HierText: staged {len(manifest)} ground-truth line crops from {len(used_images)} real images", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("work", choices=("retrieval", "ocr"))
    args = parser.parse_args()
    source_metadata = metadata()
    if args.work == "retrieval":
        retrieval(source_metadata)
    else:
        ocr(source_metadata)


if __name__ == "__main__":
    main()
