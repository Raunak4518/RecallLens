import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import tarfile
import urllib.request
from pathlib import Path, PurePosixPath

from PIL import Image


DOCCI_HOME = "https://google.github.io/docci/"
DOCCI_DESCRIPTIONS = "https://storage.googleapis.com/docci/data/docci_descriptions.jsonlines"
DOCCI_METADATA = "https://storage.googleapis.com/docci/data/docci_metadata.jsonlines"
DOCCI_IMAGES = "https://storage.googleapis.com/docci/data/docci_images.tar.gz"
IMAGE_LICENSE = "https://creativecommons.org/licenses/by/4.0/"
MIN_FREE_BYTES = 2 * 1024**3
MAX_SOURCE_IMAGE_BYTES = 16 * 1024**2
MAX_SELECTED_IMAGE_BYTES = 1536 * 1024**2
DOCCI_DESCRIPTION_BYTES = 11000214
DOCCI_DESCRIPTION_ETAG = "7ed51dae0fed79d33564cd0e5b9c2f11"
DOCCI_METADATA_BYTES = 162767377
DOCCI_METADATA_ETAG = "385437ff01c003fcc1ca6a582b54811b"
DOCCI_IMAGES_BYTES = 7592938768
DOCCI_IMAGES_ETAG = "57493de5075fe508d51e77500748f4da"


class HashingReader:
    def __init__(self, source):
        self.source = source
        self.md5 = hashlib.md5()
        self.sha256 = hashlib.sha256()
        self.bytes_read = 0

    def read(self, size=-1):
        value = self.source.read(size)
        if value:
            self.md5.update(value)
            self.sha256.update(value)
            self.bytes_read += len(value)
        return value


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"Expected JSON object at {path}:{line_number}")
            rows.append(value)
    return rows


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def query_complexity(description: str) -> dict:
    words = re.findall(r"\b[\w'-]+\b", description, flags=re.UNICODE)
    relations = re.findall(
        r"\b(?:next to|in front of|behind|beside|between|inside|outside|under|over|near|across|through|around|on top of|wearing|holding|looking|facing|while|whereas|although)\b",
        description,
        flags=re.IGNORECASE,
    )
    count_terms = re.findall(r"\b(?:one|two|three|four|five|several|many|pair|group|multiple|both|each)\b|\b\d+\b", description, flags=re.IGNORECASE)
    appearance_terms = re.findall(
        r"\b(?:red|orange|yellow|green|blue|purple|pink|brown|black|white|gray|grey|striped|spotted|wooden|metal|glass|plastic|small|large|tiny|bright|dark|light|patterned|round|square|curved|text|letter|number|sign|label)\b",
        description,
        flags=re.IGNORECASE,
    )
    score = len(words) + 5 * len(relations) + 3 * len(count_terms) + 2 * len(appearance_terms)
    return {
        "word_count": len(words),
        "relation_markers": len(relations),
        "count_markers": len(count_terms),
        "appearance_or_text_markers": len(appearance_terms),
        "score": score,
        "method": "deterministic_description_length_and_detail_marker_score",
    }


def example_filename(row: dict) -> str:
    value = row.get("image_file")
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"DOCCI example has no image_file: {row.get('example_id')}")
    filename = PurePosixPath(value.replace("\\", "/")).name
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.jpe?g", filename, flags=re.IGNORECASE):
        raise ValueError(f"Invalid DOCCI image filename: {value}")
    return filename


def output_filename(filename: str) -> str:
    return hashlib.sha256(filename.casefold().encode("utf-8")).hexdigest()[:24] + ".jpg"


def image_identifier(filename: str) -> str:
    return "docci:" + hashlib.sha256(filename.casefold().encode("utf-8")).hexdigest()[:24]


def select_examples(description_rows: list[dict], metadata_rows: list[dict], count: int = 200) -> tuple[list[dict], dict[str, str]]:
    if count < 1:
        raise ValueError("Selection count must be positive")
    descriptions = {}
    files_to_ids = {}
    filenames_by_example_id = {}
    output_names = {}
    for row in description_rows:
        example_id = row.get("example_id")
        description = row.get("description")
        if not isinstance(example_id, str) or not example_id.strip() or not isinstance(description, str) or not description.strip():
            raise ValueError("Each DOCCI description needs a nonempty example_id and description")
        if example_id in descriptions:
            raise ValueError(f"Duplicate DOCCI example_id: {example_id}")
        filename = example_filename(row)
        if filename in files_to_ids:
            raise ValueError(f"Duplicate DOCCI image filename: {filename}")
        output_name = output_filename(filename)
        existing_name = output_names.get(output_name.casefold())
        if existing_name is not None:
            raise ValueError(f"DOCCI output filename collision between {existing_name} and {filename}")
        output_names[output_name.casefold()] = filename
        descriptions[example_id] = {**row, "image_file": filename}
        files_to_ids[filename] = example_id
        filenames_by_example_id[example_id] = filename
    candidates = []
    for row in metadata_rows:
        example_id = row.get("example_id")
        descriptor = descriptions.get(example_id)
        if descriptor is None:
            continue
        split = descriptor.get("split", str(example_id).split("_", 1)[0])
        distractors = row.get("distractors")
        if split != "test" or not isinstance(distractors, list) or len(distractors) != 4:
            continue
        distractor_filenames = []
        for value in distractors:
            raw_value = PurePosixPath(str(value).replace("\\", "/")).name
            if raw_value in filenames_by_example_id:
                raw_value = filenames_by_example_id[raw_value]
            elif not PurePosixPath(raw_value).suffix:
                raw_value = filenames_by_example_id.get(raw_value, raw_value + ".jpg")
            distractor_filenames.append(raw_value)
        if len(set(distractor_filenames)) != 4 or descriptor["image_file"] in distractor_filenames:
            continue
        if any(not re.fullmatch(r"[A-Za-z0-9_.-]+\.jpe?g", name, flags=re.IGNORECASE) for name in distractor_filenames):
            continue
        details = query_complexity(descriptor["description"])
        cluster_id = row.get("cluster_id")
        candidates.append({
            "example_id": example_id,
            "image_file": descriptor["image_file"],
            "description": descriptor["description"],
            "cluster_id": str(cluster_id) if cluster_id is not None else None,
            "distractor_files": distractor_filenames,
            "difficulty": details,
        })
    candidates.sort(key=lambda row: (-row["difficulty"]["score"], hashlib.sha256(row["example_id"].encode()).hexdigest()))
    selected = []
    used_clusters = set()
    selected_ids = set()
    for row in candidates:
        cluster = row["cluster_id"]
        if cluster is not None and cluster in used_clusters:
            continue
        selected.append(row)
        selected_ids.add(row["example_id"])
        if cluster is not None:
            used_clusters.add(cluster)
        if len(selected) == count:
            break
    if len(selected) < count:
        remaining = [row for row in candidates if row["example_id"] not in selected_ids]
        selected.extend(remaining[:count - len(selected)])
    if len(selected) != count:
        raise ValueError(f"DOCCI has only {len(selected)} test examples with four valid distractors; requested {count}")
    files = {}
    for row in selected:
        files[row["image_file"]] = "target"
        for filename in row["distractor_files"]:
            files.setdefault(filename, "hard_negative")
    return selected, files


def file_digest(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_small_file(url: str, target: Path, maximum_bytes: int,
                        expected_bytes: int, expected_etag: str) -> dict:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file():
        existing_bytes = target.stat().st_size
        existing_md5 = file_digest(target, "md5")
        if existing_bytes != expected_bytes or existing_md5 != expected_etag:
            raise ValueError(f"Cached DOCCI source does not match the verified publisher object: {target}")
        return {"url": url, "bytes": existing_bytes, "md5": existing_md5,
                "sha256": sha256_file(target), "etag": expected_etag, "reused": True}
    request = urllib.request.Request(url, headers={"User-Agent": "PhotoLibrary-ModelEvaluation/1.0"})
    temporary = target.with_name(target.name + ".part")
    digest = hashlib.sha256()
    md5 = hashlib.md5()
    total = 0
    try:
        with urllib.request.urlopen(request, timeout=90) as source, temporary.open("wb") as output:
            expected = source.headers.get("Content-Length")
            expected_bytes = int(expected) if expected and expected.isdigit() else None
            if expected_bytes is not None and expected_bytes > maximum_bytes:
                raise ValueError(f"DOCCI metadata exceeds the configured bound: {url}")
            etag = source.headers.get("ETag", "").strip('"')
            while True:
                block = source.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if total > maximum_bytes:
                    raise ValueError(f"DOCCI metadata exceeds the configured bound: {url}")
                output.write(block)
                digest.update(block)
                md5.update(block)
            if expected_bytes is not None and total != expected_bytes:
                raise IOError(f"DOCCI metadata length mismatch: received {total}, expected {expected_bytes}")
            if total != expected_bytes or md5.hexdigest() != expected_etag:
                raise IOError(f"DOCCI metadata does not match the verified publisher object: {url}")
            output.flush()
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    temporary.replace(target)
    return {"url": url, "bytes": total, "sha256": digest.hexdigest(), "md5": md5.hexdigest(),
            "etag": etag, "reused": False}


def _valid_jpeg(payload: bytes) -> None:
    import io
    with Image.open(io.BytesIO(payload)) as image:
        if image.format != "JPEG" or image.width < 1 or image.height < 1:
            raise ValueError("Selected DOCCI archive member is not a valid JPEG")
        image.verify()


def extract_selected_images(archive_url: str, selected_files: dict[str, str], output_root: Path,
                            max_image_bytes: int = MAX_SOURCE_IMAGE_BYTES,
                            max_total_bytes: int = MAX_SELECTED_IMAGE_BYTES,
                            expected_archive_bytes: int = DOCCI_IMAGES_BYTES,
                            expected_archive_etag: str = DOCCI_IMAGES_ETAG) -> dict:
    output_root.mkdir(parents=True, exist_ok=True)
    destinations = {name: output_root / output_filename(name) for name in selected_files}
    found = set()
    receipts = {}
    total_bytes = 0
    for name, path in destinations.items():
        if path.is_file():
            payload = path.read_bytes()
            _valid_jpeg(payload)
            found.add(name)
            receipts[name] = {"file": path.name, "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest(), "reused": True}
            total_bytes += len(payload)
    if total_bytes > max_total_bytes:
        raise ValueError("Previously extracted DOCCI files exceed the selected-image storage limit")
    if len(found) == len(selected_files):
        return {"selected_files": len(found), "bytes_written_or_reused": total_bytes, "images": receipts, "archive_download_bytes": 0}
    usage = shutil.disk_usage(output_root)
    if usage.free < MIN_FREE_BYTES + max_total_bytes:
        raise OSError(f"Need at least {MIN_FREE_BYTES + max_total_bytes} free bytes to keep the configured storage reserve")
    request = urllib.request.Request(archive_url, headers={"User-Agent": "PhotoLibrary-ModelEvaluation/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        content_length = response.headers.get("Content-Length")
        expected_bytes = int(content_length) if content_length and content_length.isdigit() else None
        expected_etag = response.headers.get("ETag", "").strip('"')
        if expected_bytes != expected_archive_bytes or expected_etag.lower() != expected_archive_etag.lower():
            raise IOError("DOCCI image archive metadata does not match the verified publisher object")
        hashing = HashingReader(response)
        with gzip.GzipFile(fileobj=hashing, mode="rb") as compressed:
            with tarfile.open(fileobj=compressed, mode="r|") as archive:
                for member in archive:
                    if not member.isfile():
                        continue
                    filename = PurePosixPath(member.name.replace("\\", "/")).name
                    if filename not in destinations or filename in found:
                        continue
                    if member.size < 1 or member.size > max_image_bytes:
                        raise ValueError(f"Selected DOCCI image exceeds its allowed size: {filename}")
                    if total_bytes + member.size > max_total_bytes:
                        raise OSError("Selected DOCCI images exceed the configured storage limit")
                    source = archive.extractfile(member)
                    if source is None:
                        raise IOError(f"Could not read selected DOCCI image: {member.name}")
                    payload = source.read(max_image_bytes + 1)
                    if len(payload) != member.size:
                        raise IOError(f"Selected DOCCI image length mismatch: {member.name}")
                    _valid_jpeg(payload)
                    destination = destinations[filename]
                    temporary = destination.with_name(destination.name + ".part")
                    temporary.write_bytes(payload)
                    temporary.replace(destination)
                    receipts[filename] = {
                        "file": destination.name,
                        "bytes": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                        "source_member": member.name,
                        "role": selected_files[filename],
                        "reused": False,
                    }
                    found.add(filename)
                    total_bytes += len(payload)
                    if len(found) % 100 == 0:
                        print(f"Extracted {len(found)}/{len(selected_files)} selected DOCCI images", flush=True)
            while compressed.read(1024 * 1024):
                pass
        while hashing.read(1024 * 1024):
            pass
        if expected_bytes is not None and hashing.bytes_read != expected_bytes:
            raise IOError(f"DOCCI archive length mismatch: read {hashing.bytes_read}, expected {expected_bytes}")
        calculated_md5 = hashing.md5.hexdigest()
        if calculated_md5.lower() != expected_archive_etag.lower():
            raise IOError("DOCCI archive MD5 does not match the Google Cloud Storage ETag")
        archive_receipt = {
            "url": archive_url,
            "bytes": hashing.bytes_read,
            "etag": expected_etag,
            "md5": calculated_md5,
            "sha256": hashing.sha256.hexdigest(),
        }
    missing = set(destinations) - found
    if missing:
        raise FileNotFoundError(f"DOCCI archive is missing {len(missing)} selected image files: {sorted(missing)[:5]}")
    return {
        "selected_files": len(found),
        "bytes_written_or_reused": total_bytes,
        "archive_download_bytes": archive_receipt["bytes"],
        "archive": archive_receipt,
        "images": receipts,
    }


def build_manifest(base_manifest_path: Path, image_root: Path, manifest_path: Path, selected: list[dict],
                   selected_files: dict[str, str], extracted_root: Path) -> dict:
    base = json.loads(base_manifest_path.read_text(encoding="utf-8"))
    if len(base.get("images", [])) < 1000:
        raise ValueError("The base gallery must contain at least 1,000 real photos")
    image_root = image_root.resolve()
    extracted_root = extracted_root.resolve()
    images = []
    ids_by_digest = {}
    base_root = base_manifest_path.parent.resolve()
    base_image_root = Path(base.get("image_root", base_root)).expanduser()
    if not base_image_root.is_absolute():
        base_image_root = (base_root / base_image_root).resolve()
    for row in base["images"]:
        image = {**row, "file": (base_image_root / row["file"]).resolve().relative_to(image_root).as_posix()}
        images.append(image)
        ids_by_digest.setdefault(image["sha256"], image["id"])
    docci_ids = {}
    needed_files = set(selected_files)
    for row in selected:
        needed_files.add(row["image_file"])
        needed_files.update(row["distractor_files"])
    for filename in sorted(needed_files):
        source = extracted_root / output_filename(filename)
        if not source.is_file():
            raise FileNotFoundError(f"Selected DOCCI image has not been extracted: {source}")
        digest = sha256_file(source)
        docci_id = image_identifier(filename)
        existing = ids_by_digest.get(digest)
        if existing is not None:
            docci_ids[filename] = existing
            continue
        relative = source.resolve().relative_to(image_root).as_posix()
        docci_ids[filename] = docci_id
        ids_by_digest[digest] = docci_id
        images.append({
            "id": docci_id,
            "file": relative,
            "sha256": digest,
            "bytes": source.stat().st_size,
            "source": DOCCI_HOME,
            "source_filename": filename,
            "license": IMAGE_LICENSE,
            "license_review": "publisher_declared_cc_by_4_0; attribution_required",
            "dataset": "DOCCI",
            "role": selected_files.get(filename, "hard_negative"),
        })
    queries = []
    for row in selected:
        positive = docci_ids[row["image_file"]]
        negatives = [docci_ids[value] for value in row["distractor_files"]]
        if positive in negatives or len(set(negatives)) != 4:
            raise ValueError(f"DOCCI query {row['example_id']} has duplicate or conflicting targets")
        queries.append({
            "query_id": f"docci-{row['example_id']}",
            "query": row["description"],
            "family": "docci-long-compositional-test",
            "split": "test",
            "source_example_id": row["example_id"],
            "cluster_id": row["cluster_id"],
            "difficulty": row["difficulty"],
            "positive": [positive],
            "explicit_negative": negatives,
            "grades": {positive: 3, **{image_id: 0 for image_id in negatives}},
            "absent": False,
            "unjudged_policy": "All other photos remain unknown, not negative.",
        })
    return {
        "version": 1,
        "dataset": "DOCCI hard-compositional-200 plus Open Images 10k background",
        "dataset_url": DOCCI_HOME,
        "license": "DOCCI media and annotation CC BY 4.0; Open Images has per-image metadata and remains unverified in this local manifest.",
        "image_root": Path(os.path.relpath(image_root, manifest_path.resolve().parent)).as_posix(),
        "images": images,
        "queries": queries,
        "selection_policy": "200 longest and most compositional test descriptions among examples with four publisher-curated visual distractors; deterministic tie-break by example ID hash; at most one selected query per cluster where possible.",
        "judgment_policy": "One exact described target and four publisher-provided visual distractors are judged. Remaining images are unknown and are not counted as false positives.",
        "metrics_warning": "The query labels are sparse. nDCG, judged precision and false-positive estimates apply only to the target and four curated distractors; report judgment coverage alongside ranking metrics.",
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--data-root", type=Path, default=Path(".benchmark-data/docci-hard-200"))
    parser.add_argument("--base-manifest", type=Path, default=Path(".benchmark-data/gallery-10k/manifest.json"))
    parser.add_argument("--query-count", type=int, default=200)
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    data_root = (workspace / args.data_root).resolve() if not args.data_root.is_absolute() else args.data_root.resolve()
    base_manifest = (workspace / args.base_manifest).resolve() if not args.base_manifest.is_absolute() else args.base_manifest.resolve()
    image_root = workspace
    query_root = data_root / "source"
    descriptions_path = query_root / "docci_descriptions.jsonlines"
    metadata_path = query_root / "docci_metadata.jsonlines"
    description_receipt = download_small_file(
        DOCCI_DESCRIPTIONS, descriptions_path, 32 * 1024**2,
        DOCCI_DESCRIPTION_BYTES, DOCCI_DESCRIPTION_ETAG)
    metadata_receipt = download_small_file(
        DOCCI_METADATA, metadata_path, 256 * 1024**2,
        DOCCI_METADATA_BYTES, DOCCI_METADATA_ETAG)
    description_rows = read_jsonl(descriptions_path)
    metadata_rows = read_jsonl(metadata_path)
    selected, selected_files = select_examples(description_rows, metadata_rows, args.query_count)
    manifest_path = data_root / "manifest.json"
    extraction_receipt = extract_selected_images(
        DOCCI_IMAGES, selected_files, data_root / "images",
        expected_archive_bytes=DOCCI_IMAGES_BYTES, expected_archive_etag=DOCCI_IMAGES_ETAG)
    manifest = build_manifest(base_manifest, image_root, manifest_path, selected, selected_files, data_root / "images")
    write_json_atomic(manifest_path, manifest)
    target_files = {row["image_file"] for row in selected}
    negative_files = {filename for row in selected for filename in row["distractor_files"]}
    write_json_atomic(data_root / "acquisition.json", {
        "sources": [description_receipt, metadata_receipt],
        "archive": extraction_receipt,
        "queries": len(selected),
        "target_images": len(selected),
        "unique_target_files": len(target_files),
        "unique_hard_negative_files": len(negative_files - target_files),
        "selected_docc_files": len(selected_files),
        "gallery_images": len(manifest["images"]),
        "selected_queries": [{"example_id": row["example_id"], "image_file": row["image_file"], "difficulty": row["difficulty"]} for row in selected],
        "manifest_sha256": sha256_file(manifest_path),
        "manifest_path": str(manifest_path),
        "images_are_not_committed": True,
    })
    print(json.dumps({
        "query_count": len(manifest["queries"]),
        "gallery_images": len(manifest["images"]),
        "selected_docc_images": len(selected_files),
        "archive_download_bytes": extraction_receipt.get("archive_download_bytes", 0),
        "manifest_sha256": sha256_file(manifest_path),
        "manifest": str(manifest_path),
    }, indent=2))


if __name__ == "__main__":
    main()
