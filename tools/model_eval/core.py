import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile

import numpy as np


SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_directory(path, included_files=None):
    root = Path(path).resolve()
    if not root.is_dir():
        raise ValueError(f"Model snapshot is not a directory: {path}")
    digest = hashlib.sha256()
    all_entries = {entry.relative_to(root).as_posix(): entry for entry in root.rglob("*") if entry.is_file()}
    if included_files is None:
        entries = list(all_entries.items())
    else:
        requested = {Path(value).as_posix() for value in included_files}
        missing = requested - all_entries.keys()
        if missing:
            raise ValueError(f"Model snapshot is missing required files: {', '.join(sorted(missing))}")
        entries = [(relative, all_entries[relative]) for relative in requested]
    entries.sort(key=lambda item: item[0])
    if not entries:
        raise ValueError(f"Model snapshot contains no files: {path}")
    for relative_path, entry in entries:
        relative = relative_path.encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        with entry.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def canonical_digest(value):
    encoded = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False, default=str).encode("utf-8")
    return sha256_bytes(encoded)


def load_manifest(path, expected_sha256=None):
    path = Path(path).resolve()
    payload = path.read_bytes()
    manifest_sha256 = sha256_bytes(payload)
    if expected_sha256 is not None and manifest_sha256 != expected_sha256.lower():
        raise ValueError("Manifest bytes do not match the expected SHA-256")
    try:
        manifest = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid manifest JSON: {error}") from error
    version = manifest.get("version", manifest.get("schema_version", 1)) if isinstance(manifest, dict) else None
    supported_version = (type(version) is int and version == 1) or version == "gallery-10k-v1"
    if not isinstance(manifest, dict) or not supported_version:
        raise ValueError("Manifest version must be 1")
    images = manifest.get("images")
    queries = manifest.get("queries")
    if not isinstance(images, list) or not images:
        raise ValueError("Manifest images must be a nonempty array")
    if not isinstance(queries, list) or not queries:
        raise ValueError("Manifest queries must be a nonempty array")
    image_ids = set()
    image_gallery_ids = []
    for row in images:
        if not isinstance(row, dict):
            raise ValueError("Each image must be an object")
        image_id = row.get("id")
        filename = row.get("file")
        digest = row.get("sha256")
        if not isinstance(image_id, str) or not image_id.strip() or image_id in image_ids:
            raise ValueError("Image IDs must be unique nonempty strings")
        if not isinstance(filename, str) or not filename.strip():
            raise ValueError(f"Image {image_id} must have a relative file path")
        if Path(filename).is_absolute() or any(part == ".." for part in Path(filename).parts):
            raise ValueError(f"Image {image_id} path must stay inside the gallery root")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise ValueError(f"Image {image_id} must have a lowercase SHA-256")
        gallery_id = row.get("gallery_id")
        if gallery_id is not None and (not isinstance(gallery_id, str) or not gallery_id.strip()):
            raise ValueError(f"Image {image_id} has an invalid gallery_id")
        image_gallery_ids.append(gallery_id)
        image_ids.add(image_id)
    query_ids = set()
    normalized_queries = []
    for position, row in enumerate(queries):
        if not isinstance(row, dict):
            raise ValueError("Each query must be an object")
        query = row.get("query")
        if not isinstance(query, str) or not query.strip():
            raise ValueError(f"Query at position {position} must have nonempty text")
        query_id = query_identifier(row, position)
        if query_id in query_ids:
            raise ValueError(f"Duplicate query ID: {query_id}")
        query_ids.add(query_id)
        gallery_id = row.get("gallery_id")
        if gallery_id is not None and (not isinstance(gallery_id, str) or not gallery_id.strip()):
            raise ValueError(f"Query {query_id} has an invalid gallery_id")
        grades = row.get("grades")
        positive = row.get("positive")
        negative = row.get("explicit_negative")
        if grades is not None:
            if not isinstance(grades, dict) or any(not isinstance(key, str) or type(value) is not int or not 0 <= value <= 3 for key, value in grades.items()):
                raise ValueError(f"Query {query_id} grades must map photo IDs to integers from zero through three")
            if any(value not in image_ids for value in grades):
                raise ValueError(f"Query {query_id} references an unknown graded image ID")
            normalized_grades = dict(grades)
            if positive is not None or negative is not None:
                if not isinstance(positive, list) or not isinstance(negative, list):
                    raise ValueError(f"Query {query_id} must provide both positive and explicit_negative arrays")
                if any(not isinstance(value, str) or value not in image_ids for value in positive + negative):
                    raise ValueError(f"Query {query_id} references an unknown image ID")
                if len(set(positive)) != len(positive) or len(set(negative)) != len(negative):
                    raise ValueError(f"Query {query_id} contains duplicate judgments")
                if set(positive) != {value for value, grade in normalized_grades.items() if grade > 0}:
                    raise ValueError(f"Query {query_id} positive labels conflict with grades")
                if set(negative) != {value for value, grade in normalized_grades.items() if grade == 0}:
                    raise ValueError(f"Query {query_id} negative labels conflict with grades")
        else:
            if not isinstance(positive, list) or not isinstance(negative, list):
                raise ValueError(f"Query {query_id} must have grades or positive and explicit_negative arrays")
            if any(not isinstance(value, str) or value not in image_ids for value in positive + negative):
                raise ValueError(f"Query {query_id} references an unknown image ID")
            if len(set(positive)) != len(positive) or len(set(negative)) != len(negative):
                raise ValueError(f"Query {query_id} contains duplicate judgments")
            if set(positive) & set(negative):
                raise ValueError(f"Query {query_id} has conflicting positive and negative judgments")
            normalized_grades = {value: 3 for value in positive}
            normalized_grades.update({value: 0 for value in negative})
        positive = [value for value, grade in normalized_grades.items() if grade > 0]
        negative = [value for value, grade in normalized_grades.items() if grade == 0]
        absent = row.get("absent", not bool(positive))
        if not isinstance(absent, bool) or (absent and positive):
            raise ValueError(f"Query {query_id} has inconsistent absent-target labels")
        family = row.get("family", row.get("type", "unspecified"))
        if not isinstance(family, str) or not family.strip():
            raise ValueError(f"Query {query_id} has an invalid family")
        normalized_queries.append({
            **row,
            "query_id": query_id,
            "family": family,
            "positive": positive,
            "explicit_negative": negative,
            "grades": normalized_grades,
            "absent": absent,
        })
    has_image_galleries = any(value is not None for value in image_gallery_ids)
    if has_image_galleries and any(value is None for value in image_gallery_ids):
        raise ValueError("Either every image must have gallery_id or none may have it")
    has_query_galleries = any(row.get("gallery_id") is not None for row in normalized_queries)
    if has_query_galleries and any(row.get("gallery_id") is None for row in normalized_queries):
        raise ValueError("Either every query must have gallery_id or none may have it")
    if has_image_galleries != has_query_galleries:
        raise ValueError("Image and query records must both use gallery_id or both omit it")
    if has_image_galleries:
        image_groups = {}
        for row in images:
            image_groups.setdefault(row["gallery_id"], set()).add(row["id"])
        for row in normalized_queries:
            gallery_id = row.get("gallery_id")
            if gallery_id not in image_groups:
                raise ValueError(f"Query {row['query_id']} references an unknown gallery_id")
            group_ids = image_groups[gallery_id]
            if not set(row["positive"] + row["explicit_negative"]) <= group_ids:
                raise ValueError(f"Query {row['query_id']} has judgments outside its gallery_id")
    manifest["queries"] = normalized_queries
    return manifest, manifest_sha256, path


def query_identifier(row, position):
    explicit = row.get("query_id", row.get("id"))
    if isinstance(explicit, str) and explicit.strip():
        return explicit
    return f"q{position:05d}-{sha256_bytes(row['query'].encode('utf-8'))[:12]}"


def query_image_indices(image_rows, query):
    gallery_id = query.get("gallery_id")
    if gallery_id is None:
        return list(range(len(image_rows)))
    return [index for index, row in enumerate(image_rows) if row.get("gallery_id") == gallery_id]


def resolve_gallery_root(manifest_path, gallery_root=None, image_root=None):
    if gallery_root is not None:
        return Path(gallery_root).resolve()
    if image_root is None:
        return Path(manifest_path).resolve().parent
    configured = Path(image_root).expanduser()
    return configured.resolve() if configured.is_absolute() else (Path(manifest_path).resolve().parent / configured).resolve()


def resolve_image_path(filename, gallery_root):
    root = Path(gallery_root).resolve()
    relative = Path(filename)
    if relative.is_absolute() or any(part == ".." for part in relative.parts):
        raise ValueError(f"Image path escapes the gallery root: {filename}")
    resolved = (root / relative).resolve()
    if not resolved.is_relative_to(root):
        raise ValueError(f"Image path escapes the gallery root: {filename}")
    return resolved


def cache_key(content_sha256, model_sha256, preprocess_sha256, role, execution_sha256=None):
    if any(not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value) for value in (content_sha256, model_sha256, preprocess_sha256)):
        raise ValueError("Cache fingerprints must be lowercase SHA-256 values")
    if execution_sha256 is not None and (not isinstance(execution_sha256, str) or not SHA256_PATTERN.fullmatch(execution_sha256)):
        raise ValueError("Execution fingerprint must be a lowercase SHA-256")
    if role not in {"image", "text"}:
        raise ValueError("Cache role must be image or text")
    return canonical_digest({"content": content_sha256, "model": model_sha256, "preprocess": preprocess_sha256,
                            "execution": execution_sha256, "role": role})


class EmbeddingCache:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def path_for(self, key):
        if not isinstance(key, str) or not SHA256_PATTERN.fullmatch(key):
            raise ValueError("Embedding cache key must be a lowercase SHA-256")
        return self.root / key[:2] / f"{key}.npy"

    def get(self, key):
        path = self.path_for(key)
        if not path.is_file():
            return None
        try:
            value = np.load(path, allow_pickle=False)
            if value.ndim != 1 or value.size == 0 or not np.isfinite(value).all():
                raise ValueError("Invalid embedding cache shape or values")
            value = np.asarray(value, dtype=np.float32)
            norm = float(np.linalg.norm(value))
            if not math.isfinite(norm) or norm <= 0:
                raise ValueError("Invalid embedding cache norm")
            return value / norm
        except (OSError, ValueError, EOFError):
            path.unlink(missing_ok=True)
            return None

    def put(self, key, value):
        vector = np.asarray(value, dtype=np.float32)
        if vector.ndim != 1 or vector.size == 0 or not np.isfinite(vector).all():
            raise ValueError("Embedding must be a finite nonempty vector")
        norm = float(np.linalg.norm(vector))
        if not math.isfinite(norm) or norm <= 0:
            raise ValueError("Embedding must have a positive finite norm")
        vector = vector / norm
        destination = self.path_for(key)
        destination.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{key}.", suffix=".part", dir=destination.parent)
        try:
            with os.fdopen(descriptor, "wb") as output:
                np.save(output, vector, allow_pickle=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary_name, destination)
        finally:
            Path(temporary_name).unlink(missing_ok=True)


def evaluate_ranking(ranked_ids, query, cutoff):
    positives = set(query["positive"])
    negatives = set(query["explicit_negative"])
    top = ranked_ids[:cutoff]
    judged = [image_id for image_id in top if image_id in positives or image_id in negatives]
    relevant = [image_id for image_id in top if image_id in positives]
    candidate_relevant = set(ranked_ids) & positives
    all_grades = query["grades"]

    def dcg(values):
        return sum((2**value - 1) / math.log2(position + 2) for position, value in enumerate(values))

    ideal = dcg(sorted(all_grades.values(), reverse=True)[:cutoff])
    actual = dcg([all_grades.get(image_id, 0) for image_id in top])
    positive_rank = next((position for position, image_id in enumerate(ranked_ids, 1) if image_id in positives), None)
    return {
        "known_positive_hit_at_1": int(bool(ranked_ids) and ranked_ids[0] in positives),
        "known_positive_hit_at_5": int(bool(set(ranked_ids[:5]) & positives)),
        "known_positive_recall_at_k": len(relevant) / len(positives) if positives else None,
        "known_positive_candidate_coverage_at_depth": len(candidate_relevant) / len(positives) if positives else None,
        "reciprocal_rank_at_k": 1 / positive_rank if positive_rank is not None and positive_rank <= cutoff else 0.0,
        "ndcg_at_k_unjudged_zero": actual / ideal if ideal > 0 else None,
        "judgment_coverage_at_k": len(judged) / len(top) if top else None,
        "judged_precision_at_k": len(relevant) / len(judged) if judged else None,
        "unjudged_in_top_k": len(top) - len(judged),
        "explicit_negative_in_top_k": sum(image_id in negatives for image_id in top),
        "absent_target": query["absent"],
        "absent_target_returned_candidates": len(top) if query["absent"] else None,
    }


def aggregate_rows(rows):
    metrics = sorted({key for row in rows for key, value in row.items() if isinstance(value, (int, float)) and value is not None and not isinstance(value, bool)})
    output = {}
    for metric in metrics:
        values = [row[metric] for row in rows if isinstance(row.get(metric), (int, float)) and row.get(metric) is not None and not isinstance(row.get(metric), bool)]
        if values:
            output[metric] = {"mean": float(np.mean(values)), "queries": len(values)}
    return output


def summarize_quality(rows):
    families = {}
    for family in sorted({row["family"] for row in rows}):
        selected = [row["metrics"] for row in rows if row["family"] == family]
        families[family] = {"queries": len(selected), **aggregate_rows(selected)}
    return {"queries": len(rows), "overall": aggregate_rows([row["metrics"] for row in rows]), "by_family": families}


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".part", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, indent=2, ensure_ascii=False, allow_nan=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".part", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            for row in rows:
                output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":"), allow_nan=False))
                output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)
