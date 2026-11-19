import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def album_images(root: Path, album_id: str) -> list[dict]:
    album_root = root / f"album{album_id}"
    if not album_root.is_dir():
        raise ValueError(f"Missing album directory: {album_root}")
    paths = sorted(
        path for path in album_root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg"}
    )
    if not paths:
        raise ValueError(f"Album contains no JPEG images: {album_root}")
    images = []
    names = set()
    for path in paths:
        name = path.name
        if name in names:
            raise ValueError(f"Duplicate image filename in album {album_id}: {name}")
        names.add(name)
        images.append({
            "id": f"album{album_id}:{name}",
            "file": path.relative_to(root).as_posix(),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
            "gallery_id": album_id,
            "source": "PhotoBench full raw album",
            "license_status": "upstream_conflict_treat_as_cc_by_nc_4_0_pending_confirmation",
        })
    return images


def queries_for_album(path: Path, album_id: str, available: set[str]) -> list[dict]:
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, list) or len(values) != 100:
        raise ValueError(f"Album {album_id} must contain exactly 100 validation queries")
    result = []
    for position, row in enumerate(values, 1):
        if not isinstance(row, dict):
            raise ValueError(f"Album {album_id} query {position} must be an object")
        prompt = row.get("query_en")
        positives = row.get("ground_truth")
        if not isinstance(prompt, str) or not prompt.strip():
            raise ValueError(f"Album {album_id} query {position} has no English prompt")
        if not isinstance(positives, list) or any(not isinstance(item, str) or not item for item in positives):
            raise ValueError(f"Album {album_id} query {position} has invalid ground truth")
        if len(positives) != len(set(positives)):
            raise ValueError(f"Album {album_id} query {position} repeats a ground-truth image")
        missing = set(positives) - available
        if missing:
            raise ValueError(f"Album {album_id} query {position} references missing images: {sorted(missing)}")
        result.append({
            "query_id": f"photobench-album{album_id}-{position:03d}",
            "query": prompt.strip(),
            "query_original": row.get("query_cn"),
            "gallery_id": album_id,
            "family": "unreviewed_personal_album_prompt",
            "grades": {f"album{album_id}:{name}": 3 for name in positives},
            "absent": len(positives) == 0,
            "ground_truth_scope": "released_validation_labels; unjudged_relevant_behavior_not_independently_reviewed",
        })
    return result


def build_manifest(query_root: Path, image_root: Path, album_ids: list[str]) -> dict:
    if len(album_ids) != 2 or len(set(album_ids)) != 2:
        raise ValueError("Select exactly two distinct albums for the 200-query set")
    if any(not re.fullmatch(r"[1-3]", value) for value in album_ids):
        raise ValueError("Album IDs must be 1, 2 or 3")
    images = []
    queries = []
    for album_id in album_ids:
        album = album_images(image_root, album_id)
        available = {row["id"].split(":", 1)[1] for row in album}
        query_file = query_root / f"album{album_id}_validation.json"
        if not query_file.is_file():
            raise ValueError(f"Missing PhotoBench validation file: {query_file}")
        images.extend(album)
        queries.extend(queries_for_album(query_file, album_id, available))
    if len(queries) != 200:
        raise ValueError(f"Expected 200 queries, found {len(queries)}")
    return {
        "dataset": "PhotoBench",
        "dataset_version": "full-validation-200-two-album",
        "dataset_url": "https://github.com/LaVieEnRose365/PhotoBench",
        "image_root": str(image_root.resolve()),
        "license_policy": "Upstream license metadata conflicts; internal academic evaluation only pending clarification; do not redistribute images or train product models.",
        "albums": album_ids,
        "images": images,
        "queries": queries,
        "judgment_policy": "Released ground-truth images receive grade 3. Empty ground truth marks an absent query. All other candidates retain unknown status unless a separate review adds labels.",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--albums", nargs=2, default=["1", "2"])
    args = parser.parse_args()
    manifest = build_manifest(args.queries, args.images, args.albums)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".part")
    temporary.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(json.dumps({
        "images": len(manifest["images"]),
        "queries": len(manifest["queries"]),
        "albums": manifest["albums"],
        "manifest_sha256": sha256(args.output),
        "image_bytes": sum(row["bytes"] for row in manifest["images"]),
        "output": str(args.output),
    }, indent=2))


if __name__ == "__main__":
    main()
