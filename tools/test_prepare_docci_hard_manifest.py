import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

from tools.prepare_docci_hard_manifest import (
    build_manifest,
    extract_selected_images,
    image_identifier,
    output_filename,
    query_complexity,
    select_examples,
    sha256_file,
)


class DocciManifestTest(unittest.TestCase):
    def test_output_paths_and_ids_are_stable_and_extension_safe(self):
        self.assertNotEqual(output_filename("photo.jpg"), output_filename("photo.jpeg"))
        self.assertNotEqual(image_identifier("photo.jpg"), image_identifier("photo.jpeg"))
        self.assertEqual(output_filename("PHOTO.JPG"), output_filename("photo.jpg"))

    def test_selection_fills_requested_size_after_cluster_diversity_pass(self):
        descriptions = []
        metadata = []
        for index in range(5):
            example_id = f"test_{index:05d}"
            descriptions.append({
                "example_id": example_id,
                "split": "test",
                "image_file": f"target-{index}.jpg",
                "description": (
                    "A small striped red object beside a large white box while a person is facing it "
                    f"near a window with a number {index}."
                ),
            })
            metadata.append({
                "example_id": example_id,
                "cluster_id": "shared" if index < 4 else "other",
                "distractors": [f"negative-{index}-{part}.jpg" for part in range(4)],
            })
        selected, files = select_examples(descriptions, metadata, count=3)
        self.assertEqual(3, len(selected))
        self.assertEqual(15, len(files))
        self.assertTrue(all(len(row["distractor_files"]) == 4 for row in selected))
        self.assertEqual("deterministic_description_length_and_detail_marker_score",
                         query_complexity(selected[0]["description"])["method"])

    def test_stem_collisions_use_noncolliding_outputs(self):
        descriptions = [
            {"example_id": "test_00001", "split": "test", "image_file": "same.jpg", "description": "one thing"},
            {"example_id": "test_00002", "split": "test", "image_file": "same.jpeg", "description": "another thing"},
        ]
        metadata = [
            {"example_id": "test_00001", "cluster_id": "one", "distractors": [f"a{i}.jpg" for i in range(4)]},
            {"example_id": "test_00002", "cluster_id": "two", "distractors": [f"b{i}.jpg" for i in range(4)]},
        ]
        selected, files = select_examples(descriptions, metadata, count=2)
        self.assertEqual(2, len(selected))
        self.assertNotEqual(output_filename("same.jpg"), output_filename("same.jpeg"))
        self.assertEqual(10, len(files))

    def test_streamed_archive_extracts_only_selected_image_and_checks_etag(self):
        image_buffer = io.BytesIO()
        Image.new("RGB", (20, 20), (130, 20, 70)).save(image_buffer, format="JPEG")
        image_bytes = image_buffer.getvalue()
        archive_buffer = io.BytesIO()
        with tarfile.open(fileobj=archive_buffer, mode="w:gz") as archive:
            member = tarfile.TarInfo("docci/test_00001.jpg")
            member.size = len(image_bytes)
            archive.addfile(member, io.BytesIO(image_bytes))
            unrelated = tarfile.TarInfo("docci/not-selected.jpg")
            unrelated.size = len(image_bytes)
            archive.addfile(unrelated, io.BytesIO(image_bytes))
        archive_bytes = archive_buffer.getvalue()
        etag = hashlib.md5(archive_bytes).hexdigest()

        class Response(io.BytesIO):
            def __init__(self, payload):
                super().__init__(payload)
                self.headers = {"Content-Length": str(len(payload)), "ETag": f'"{etag}"'}

        with tempfile.TemporaryDirectory() as folder:
            output_root = Path(folder) / "images"
            with patch("tools.prepare_docci_hard_manifest.urllib.request.urlopen",
                       return_value=Response(archive_bytes)), patch(
                           "tools.prepare_docci_hard_manifest.shutil.disk_usage",
                           return_value=SimpleNamespace(free=4 * 1024**3)):
                receipt = extract_selected_images(
                    "https://example.invalid/archive.tar.gz", {"test_00001.jpg": "target"},
                    output_root, max_image_bytes=1024 * 1024, max_total_bytes=1024 * 1024,
                    expected_archive_bytes=len(archive_bytes), expected_archive_etag=etag)
            output_file = output_root / output_filename("test_00001.jpg")
            self.assertTrue(output_file.is_file())
            self.assertFalse((output_root / output_filename("not-selected.jpg")).exists())
            self.assertEqual(1, receipt["selected_files"])
            self.assertEqual(len(archive_bytes), receipt["archive_download_bytes"])
            self.assertEqual(hashlib.sha256(image_bytes).hexdigest(), receipt["images"]["test_00001.jpg"]["sha256"])

    def test_manifest_uses_relative_image_root_and_records_exact_judgments(self):
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder)
            base_root = workspace / ".benchmark-data" / "gallery-10k"
            base_images = base_root / "images"
            extracted_root = workspace / ".benchmark-data" / "docci-hard-200" / "images"
            base_images.mkdir(parents=True)
            extracted_root.mkdir(parents=True)
            base_image = base_images / "base.jpg"
            Image.new("RGB", (16, 16), (1, 2, 3)).save(base_image, format="JPEG")
            base_digest = sha256_file(base_image)
            base_rows = [{
                "id": f"base-image-{index}",
                "file": "images/base.jpg",
                "sha256": base_digest,
            } for index in range(1000)]
            base_manifest = base_root / "manifest.json"
            base_manifest.write_text(json.dumps({"version": "gallery-10k-v1", "images": base_rows}),
                                     encoding="utf-8")
            selected_files = {"target.jpg": "target", **{f"negative-{i}.jpg": "hard_negative" for i in range(4)}}
            for index, filename in enumerate(selected_files):
                Image.new("RGB", (16, 16), (index * 45 + 5, 20, 230 - index * 35)).save(
                    extracted_root / output_filename(filename), format="JPEG")
            selected = [{
                "example_id": "test_00001",
                "image_file": "target.jpg",
                "description": "A detailed test target next to a red object.",
                "cluster_id": "test-cluster",
                "distractor_files": [f"negative-{i}.jpg" for i in range(4)],
                "difficulty": query_complexity("A detailed test target next to a red object."),
            }]
            manifest_path = workspace / ".benchmark-data" / "docci-hard-200" / "manifest.json"
            manifest = build_manifest(base_manifest, workspace, manifest_path, selected,
                                      selected_files, extracted_root)
            self.assertEqual("../..", manifest["image_root"])
            self.assertEqual(1005, len(manifest["images"]))
            self.assertEqual(["docci-test_00001"], [row["query_id"] for row in manifest["queries"]])
            query = manifest["queries"][0]
            self.assertEqual(1, len(query["positive"]))
            self.assertEqual(4, len(query["explicit_negative"]))
            self.assertEqual(5, len(query["grades"]))


if __name__ == "__main__":
    unittest.main()
