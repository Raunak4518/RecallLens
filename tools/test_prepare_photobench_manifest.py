import json
from pathlib import Path

import pytest

from tools.prepare_photobench_manifest import build_manifest


def write_album(root: Path, album_id: str, positives: list[str]) -> None:
    images = root / "images" / f"album{album_id}"
    images.mkdir(parents=True)
    for name in ("target.jpg", "distractor.JPG"):
        (images / name).write_bytes((album_id + name).encode("utf-8"))
    queries = root / "queries"
    queries.mkdir(exist_ok=True)
    rows = [
        {"query_en": f"prompt {index}", "query_cn": f"查询 {index}", "ground_truth": positives if index == 0 else []}
        for index in range(100)
    ]
    (queries / f"album{album_id}_validation.json").write_text(json.dumps(rows), encoding="utf-8")


def test_builds_two_album_manifest_with_scoped_labels_and_hashes(tmp_path: Path) -> None:
    write_album(tmp_path, "1", ["target.jpg"])
    write_album(tmp_path, "2", ["target.jpg"])
    manifest = build_manifest(tmp_path / "queries", tmp_path / "images", ["1", "2"])
    assert len(manifest["images"]) == 4
    assert len(manifest["queries"]) == 200
    assert manifest["queries"][0]["grades"] == {"album1:target.jpg": 3}
    assert manifest["queries"][100]["grades"] == {"album2:target.jpg": 3}
    assert manifest["queries"][1]["absent"] is True
    assert all(len(image["sha256"]) == 64 for image in manifest["images"])
    assert {image["gallery_id"] for image in manifest["images"]} == {"1", "2"}
    assert manifest["image_root"] == str((tmp_path / "images").resolve())


def test_rejects_ground_truth_outside_album_gallery(tmp_path: Path) -> None:
    write_album(tmp_path, "1", ["missing.jpg"])
    write_album(tmp_path, "2", ["target.jpg"])
    with pytest.raises(ValueError, match="references missing images"):
        build_manifest(tmp_path / "queries", tmp_path / "images", ["1", "2"])


def test_requires_exactly_two_albums(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="exactly two"):
        build_manifest(tmp_path, tmp_path, ["1"])
