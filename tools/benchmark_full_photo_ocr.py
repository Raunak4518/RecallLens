import argparse
import gzip
import json
import math
import re
import shutil
import statistics
import subprocess
import tarfile
import unicodedata
import zipfile
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import PurePosixPath

from PIL import Image

from benchmark_public_datasets import (
    BUILD, DATA, IMAGE_LICENSE, SOURCES, attribution, csv_records, digest, fetch, metadata, order_key, write_json,
)
from compare_ocr_engines import APP, RUNNER

ASSETS = BUILD / "public-evaluation" / "hiertext-full"
FROZEN = DATA / "hiertext-full-reference.json"
PHOTO_COUNT = 60
HIER_TEXT_ARCHIVE = "https://open-images-dataset.s3.amazonaws.com/ocr/validation.tgz"
TEST_METADATA = "https://storage.googleapis.com/openimages/2018_04/test/test-images-with-rotation.csv"


def lines(entry):
    return [line for paragraph in entry["paragraphs"] for line in paragraph["lines"]]


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).lower().split())


def tokens(text):
    value = unicodedata.normalize("NFKC", text).lower()
    return "".join(char if unicodedata.category(char)[0] in "LNM" else " " for char in value).split()


def snippets(text, size):
    words = tokens(text)
    for index in range(len(words) - size + 1):
        sample = words[index:index + size]
        if any(re.fullmatch(r"[a-z]{4,}", word) for word in sample):
            yield " ".join(sample)


def eligible(line):
    return (line["legible"] and not line["handwritten"] and not line["vertical"]
            and re.fullmatch(r"[ -~]+", line["text"].strip()) is not None
            and bool(re.search(r"[A-Za-z]", line["text"])))


def bounds(vertices):
    xs, ys = zip(*vertices)
    return [min(xs), min(ys), max(xs), max(ys)]


def ordered(values, group):
    return sorted(values, key=lambda value: order_key(f"full-ocr-v1/{group}/{value}"))


def photo_metadata(ids, test_file):
    known = {image_id: row for image_id, row in metadata().items() if image_id in ids}
    for row in csv_records(test_file):
        photo_id = row["ImageID"]
        if (photo_id in ids and row["License"] == IMAGE_LICENSE and row["Author"]
                and row["OriginalLandingURL"].startswith("https://")):
            known[photo_id] = row
    return known


def stage_images(selected, archive_path):
    paths = {}
    wanted = {f"{photo_id}.jpg" for photo_id in selected}
    with tarfile.open(archive_path, "r|gz") as archive:
        for member in archive:
            name = PurePosixPath(member.name).name
            if not member.isfile() or name not in wanted:
                continue
            source = archive.extractfile(member)
            path = ASSETS / name
            with source, path.open("wb") as stream:
                shutil.copyfileobj(source, stream)
            paths[path.stem] = (path, member.name)
            wanted.remove(name)
            if not wanted:
                return paths
    raise ValueError(f"Missing selected photos in HierText archive: {sorted(wanted)}")


def prepare(cached_only):
    previous = json.loads((DATA / "hiertext-attribution.json").read_text(encoding="utf-8"))
    excluded = {image["image_id"] for image in previous["images"]}
    annotations = DATA / "hiertext-validation.jsonl.gz"
    validation_file = DATA / "open-images-metadata.csv"
    if cached_only and not (annotations.is_file() and validation_file.is_file()):
        raise FileNotFoundError("Cached HierText annotations and Open Images attribution are required")
    if not cached_only:
        fetch(SOURCES[annotations.name], annotations)
    with gzip.open(annotations, "rt", encoding="utf-8") as stream:
        entries = {entry["image_id"]: entry for entry in json.load(stream)["annotations"]}
    if cached_only:
        test_file = None
        source_metadata = metadata()
        available = {path.stem for path in (DATA / "open-images").glob("*.jpg")}
        candidates = ordered((entries.keys() & source_metadata.keys() & available) - excluded, "images")
        distractors = ordered(excluded & available & source_metadata.keys(), "distractors")
    else:
        test_file = fetch(TEST_METADATA, DATA / "open-images-test-metadata.csv")
        source_metadata = photo_metadata(set(entries) - excluded, test_file)
        candidates = ordered(source_metadata.keys(), "images")
        distractors = []
    reference = {}
    selected = []
    for photo_id in candidates:
        photo = entries[photo_id]
        supported = [line for line in lines(photo) if eligible(line)]
        if any(next(snippets(line["text"], 1), None) for line in supported):
            selected.append(photo_id)
            reference[photo_id] = supported
        if len(selected) == PHOTO_COUNT:
            break
    if not selected or (not cached_only and len(selected) != PHOTO_COUNT):
        raise ValueError("Too few held-out images with eligible text")

    gallery = selected + distractors
    relevance = defaultdict(set)
    for photo_id in gallery:
        for line in lines(entries[photo_id]):
            for size in (1, 2):
                for text in snippets(line["text"], size):
                    relevance[text].add(photo_id)
    queries = []

    def add(kind, text, target=None):
        queries.append({
            "id": f"q{len(queries) + 1:03}", "kind": kind, "text": text,
            "target": target, "relevant": sorted(relevance.get(text, ())),
        })

    for size, kind in ((1, "word"), (2, "phrase")):
        used = set()
        for photo_id in selected:
            choices = {text for line in reference[photo_id] for text in snippets(line["text"], size)}
            distinct = choices - used
            if choices:
                text = ordered(distinct or choices, f"{kind}/{photo_id}")[0]
                add(kind, text, photo_id)
                used.add(text)
        absent = set()
        for photo_id in ordered(entries.keys(), f"absent-source/{kind}"):
            if photo_id in gallery:
                continue
            for line in lines(entries[photo_id]):
                if eligible(line):
                    absent.update(text for text in snippets(line["text"], size) if text not in relevance)
            if len(absent) >= 1000:
                break
        for text in ordered(absent, f"absent/{kind}")[:10]:
            add(f"absent_{kind}", text)
    for kind in ("absent_word", "absent_phrase"):
        if sum(query["kind"] == kind for query in queries) != 10:
            raise ValueError(f"Too few {kind} controls")

    ASSETS.mkdir(parents=True, exist_ok=True)
    if cached_only:
        staged = {}
        for photo_id in gallery:
            name = f"{photo_id}.jpg"
            path = ASSETS / name
            shutil.copyfile(DATA / "open-images" / name, path)
            staged[photo_id] = path, None
    else:
        archive = fetch(HIER_TEXT_ARCHIVE, DATA / "hiertext-validation.tgz")
        staged = stage_images(gallery, archive)
    images = []
    for photo_id in gallery:
        path, member = staged[photo_id]
        photo = entries[photo_id]
        width, height = photo["image_width"], photo["image_height"]
        with Image.open(path) as source:
            actual_width, actual_height = source.size
            if source.getexif().get(274, 1) in (5, 6, 7, 8):
                actual_width, actual_height = actual_height, actual_width
            aligned = (actual_width, actual_height) == (width, height)
            if cached_only:
                aligned = abs((actual_width / actual_height) / (width / height) - 1) <= 0.015
            if not aligned:
                raise ValueError(f"Image dimensions or orientation differ from annotations for {photo_id}")
        provenance = attribution(source_metadata[photo_id], path)
        if not cached_only:
            provenance.update(download_url=HIER_TEXT_ARCHIVE, archive_member=member)
        images.append({
            **provenance, "width": width, "height": height,
            "lines": [{"text": line["text"], "box": bounds(line["vertices"])} for line in reference.get(photo_id, ())],
        })

    write_json(ASSETS / "manifest.json", {
        "images": gallery, "queries": [{"id": row["id"], "text": row["text"]} for row in queries],
    })
    write_json(FROZEN, {
        "split": "validation", "annotations_sha256": digest(annotations),
        "annotation_license": "https://creativecommons.org/licenses/by-sa/4.0/",
        "test_metadata_sha256": digest(test_file) if test_file else None,
        "validation_metadata_sha256": digest(validation_file),
        "manifest_sha256": digest(ASSETS / "manifest.json"),
        "cached_only": cached_only, "scored_photo_ids": selected, "distractor_photo_ids": distractors,
        "images": images, "queries": queries,
    })
    print(f"Frozen {len(selected)} targets, {len(distractors)} distractors and {len(queries)} queries: {digest(FROZEN)}", flush=True)


def iou(left, right):
    a, b, c, d = left
    e, f, g, h = right
    overlap = max(0, min(c, g) - max(a, e)) * max(0, min(d, h) - max(b, f))
    union = (c - a) * (d - b) + (g - e) * (h - f) - overlap
    return overlap / union if union > 0 else 0


def edit_distance(left, right):
    previous = list(range(len(right) + 1))
    for index, a in enumerate(left, 1):
        current = [index]
        for j, b in enumerate(right, 1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (a != b)))
        previous = current
    return previous[-1]


def score_lines(reference, prediction):
    sx = prediction["width"] / reference["width"]
    sy = prediction["height"] / reference["height"]
    targets = reference["lines"]
    observed = prediction["lines"]
    candidates = []
    for i, line in enumerate(targets):
        x1, y1, x2, y2 = line["box"]
        expected_box = (x1 * sx, y1 * sy, x2 * sx, y2 * sy)
        for j, actual in enumerate(observed):
            if actual["box"] is not None:
                overlap = iou(expected_box, actual["box"])
                if overlap >= 0.5:
                    candidates.append((-overlap, i, j))
    matched = {}
    used = set()
    for _, i, j in sorted(candidates):
        if i not in matched and j not in used:
            matched[i] = j
            used.add(j)
    scores = Counter(eligible_lines=len(targets), matched_lines=len(matched), predicted_lines=len(observed))
    for i, target in enumerate(targets):
        expected = normalize(target["text"])
        actual = normalize(observed[matched[i]]["text"]) if i in matched else ""
        edits = edit_distance(expected, actual)
        scores.update(characters=len(expected), edits=edits, exact_lines=int(expected == actual))
        if i in matched:
            scores.update(matched_characters=len(expected), matched_edits=edits)
    return scores


def score_search(queries, observed):
    detail = []
    counts = defaultdict(Counter)
    for query, result in zip(queries, observed, strict=True):
        if result["id"] != query["id"]:
            raise ValueError("Query results are out of order")
        matches = result["matches"]
        relevant = set(query["relevant"])
        target = query["target"]
        rank = matches.index(target) + 1 if target in matches else None
        wrong = [photo_id for photo_id in matches if photo_id not in relevant]
        counts[query["kind"]].update(
            queries=1, top1=int(rank == 1), top5=int(rank is not None and rank <= 5),
            top10=int(rank is not None and rank <= 10), found=int(rank is not None),
            any_relevant=int(bool(relevant.intersection(matches))), no_result=int(not matches),
            queries_with_wrong_match=int(bool(wrong)), irrelevant_returned=len(wrong), returned=len(matches),
        )
        detail.append({**query, **result, "target_rank": rank, "unlabeled_matches": wrong})
    return counts, detail


def latency(values):
    return {"p50_ms": statistics.median(values), "p95_ms": sorted(values)[math.ceil(0.95 * len(values)) - 1]}


def run(adb, serial):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Pass the serial of an Android emulator")

    def call(*args, timeout=90):
        return subprocess.check_output([adb, "-s", serial, *args], stderr=subprocess.STDOUT, timeout=timeout)

    if call("shell", "settings", "get", "global", "airplane_mode_on").strip() != b"1":
        raise ValueError("Enable airplane mode on the test emulator")
    if b"Wifi is disabled" not in call("shell", "cmd", "wifi", "status"):
        raise ValueError("Disable Wi-Fi on the test emulator")
    corpus = json.loads(FROZEN.read_text(encoding="utf-8"))
    test_apk = BUILD / "outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"
    with zipfile.ZipFile(test_apk) as archive:
        manifest = archive.read("assets/hiertext-full/manifest.json")
        if sha256(manifest).hexdigest() != corpus["manifest_sha256"]:
            raise ValueError("Rebuild the test APK with the frozen search fixtures")
        for photo in corpus["images"]:
            asset = archive.read(f"assets/hiertext-full/{photo['image_id']}.jpg")
            if sha256(asset).hexdigest() != photo["sha256"]:
                raise ValueError(f"Unexpected image bytes for {photo['image_id']}")
    for apk in (BUILD / "outputs/apk/feasibility/app-feasibility.apk", test_apk):
        print(call("install", "-r", str(apk)).decode().strip(), flush=True)
    name = "files/hiertext-full-results.json"
    call("shell", "run-as", APP, "rm", "-f", name)
    call("shell", "am", "force-stop", APP)
    test = "org.photolibrary.app.feasibility.FullPhotoOcrTest#searchFullHierTextPhotos"
    result = call("shell", "am", "instrument", "-w", "-e", "class", test, RUNNER, timeout=360).decode()
    print(result.strip(), flush=True)
    if "OK (1 test)" not in result:
        raise RuntimeError("Full-photo instrumentation failed")
    observed = json.loads(call("exec-out", "run-as", APP, "cat", name))
    call("shell", "run-as", APP, "rm", "-f", name)
    scores = Counter()
    image_scores = []
    scored_ids = set(corpus["scored_photo_ids"])
    for photo, prediction in zip(corpus["images"], observed["images"], strict=True):
        if photo["image_id"] != prediction["id"]:
            raise ValueError("Photo results are out of order")
        image_score = score_lines(photo, prediction) if photo["image_id"] in scored_ids else None
        if image_score is not None:
            scores.update(image_score)
        image_scores.append({**prediction, "score": image_score})
    search_counts, search_detail = score_search(corpus["queries"], observed["queries"])
    timing = {
        "adapter_setup_ms": observed["adapter_setup_ms"],
        "first_ocr_ms": observed["images"][0]["ocr_ms"],
        "warm_ocr": latency([image["ocr_ms"] for image in observed["images"][1:]]),
        "decode_plus_ocr_total_ms": sum(image["ocr_ms"] + image["decode_ms"] for image in observed["images"]),
        "search": latency([query["search_ms"] for query in search_detail]),
    }
    summary = {
        "photos": len(image_scores), "scored_photos": len(scored_ids),
        "blank_scored_photos": sum(not image["lines"] for image in image_scores if image["id"] in scored_ids),
        "photos_found_by_any_target_query": len({query["target"] for query in search_detail if query["target_rank"]}),
        "ocr": {**scores, "detection_recall": scores["matched_lines"] / scores["eligible_lines"],
                "cer_including_misses": scores["edits"] / scores["characters"],
                "matched_line_cer": scores["matched_edits"] / scores["matched_characters"]},
        "search": search_counts, "timing": timing,
    }
    destination = DATA / "hiertext-full-ocr-search.json"
    write_json(destination, {
        "device": serial, "android": call("shell", "getprop", "ro.build.version.release").decode().strip(),
        "abi": call("shell", "getprop", "ro.product.cpu.abi").decode().strip(), "mlkit_version": "16.0.1",
        "airplane_mode": True, "wifi_enabled": False, "reference_sha256": digest(FROZEN), "cached_only": corpus["cached_only"],
        "summary": summary, "images": image_scores, "queries": search_detail,
    })
    print(json.dumps(summary, indent=2))
    print(f"Report: {destination}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "run"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--download", action="store_true", help="Allow the larger evaluation archive and attribution download for prepare")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(cached_only=not args.download)
    else:
        run(args.adb, args.serial)


if __name__ == "__main__":
    main()
