import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw

from tools.vlm_eval.prepare_review import prepare


class PrepareReviewTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.corpus = self.root / "corpus"
        self.corpus.mkdir()
        self.output = self.root / "review"
        self.baseline = self.root / "baseline.json"
        images = []
        for index in range(8):
            path = self.corpus / f"image-{index}.jpg"
            Image.new("RGB", (12, 12), (index * 20, 0, 0)).save(path)
            images.append({"id": str(index), "file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                           "author": "fixture", "source": "generated", "license": "owned"})
        self.manifest = {"images": images, "queries": [{"id": "query-1", "query": "a red square", "type": "generated"}]}
        self.write_inputs()

    def write_inputs(self):
        data = json.dumps(self.manifest).encode()
        (self.corpus / "manifest.json").write_bytes(data)
        self.baseline.write_text(json.dumps({
            "complete": True, "manifest_sha256": hashlib.sha256(data).hexdigest(),
            "queries": [{"query": query["query"], "ranking": [{"id": str(index), "score": 0.8 - index / 10} for index in range(8)]}
                        for query in self.manifest["queries"]],
        }), encoding="utf-8")

    def test_review_starts_pending_with_exact_candidate_provenance(self):
        result = prepare(self.corpus, self.baseline, self.output)
        self.assertEqual(8, result["candidate_slots"])
        review = json.loads((self.output / "judgments.json").read_text(encoding="utf-8"))
        self.assertEqual("pending_visual_review_before_vlm_outputs", review["review_status"])
        self.assertTrue(all(row["judgment"] is None for row in review["queries"][0]["candidates"]))
        by_id = {row["id"]: row for row in review["queries"][0]["candidates"]}
        for image in self.manifest["images"]:
            self.assertEqual(image["sha256"], by_id[image["id"]]["sha256"])
            self.assertEqual(image["file"], by_id[image["id"]]["file"])
        with self.assertRaises(ValueError):
            prepare(self.corpus, self.baseline, self.output)

    def test_review_order_is_deterministic_and_independent_of_baseline_order(self):
        prepare(self.corpus, self.baseline, self.output)
        def candidate_ids(output):
            review = json.loads((output / "judgments.json").read_text(encoding="utf-8"))
            return [row["id"] for row in review["queries"][0]["candidates"]]
        first = candidate_ids(self.output)
        self.assertNotEqual(list(map(str, range(8))), first)
        second = self.root / "second-review"
        prepare(self.corpus, self.baseline, second)
        self.assertEqual(first, candidate_ids(second))
        baseline = json.loads(self.baseline.read_text(encoding="utf-8"))
        for index, candidate in enumerate(baseline["queries"][0]["ranking"]):
            candidate["id"] = str(7 - index)
        self.baseline.write_text(json.dumps(baseline), encoding="utf-8")
        third = self.root / "third-review"
        prepare(self.corpus, self.baseline, third)
        self.assertEqual(first, candidate_ids(third))

    def test_judgment_document_and_rendered_labels_disclose_no_baseline_scores_or_ranks(self):
        original = ImageDraw.ImageDraw.text
        labels = []
        def draw_text(draw, position, text, *args, **kwargs):
            labels.append(text)
            return original(draw, position, text, *args, **kwargs)
        with patch.object(ImageDraw.ImageDraw, "text", draw_text):
            prepare(self.corpus, self.baseline, self.output)
        review = json.loads((self.output / "judgments.json").read_text(encoding="utf-8"))
        for candidate in review["queries"][0]["candidates"]:
            self.assertEqual({"id", "file", "sha256", "author", "source", "license", "judgment", "reason"}, set(candidate))
        self.assertEqual(["query-1 - a red square"] + ["ID " + row["id"] for row in review["queries"][0]["candidates"]], labels)
        self.assertEqual(hashlib.sha256(self.baseline.read_bytes()).hexdigest(), review["baseline_sha256"])

    def test_concurrent_review_created_during_render_is_never_overwritten(self):
        save = Image.Image.save
        marker = b'{"review_status":"human_review_in_progress"}'

        def render(image, path, *args, **kwargs):
            (self.output / "judgments.json").write_bytes(marker)
            return save(image, path, *args, **kwargs)

        with patch.object(Image.Image, "save", render):
            with self.assertRaises(FileExistsError):
                prepare(self.corpus, self.baseline, self.output)
        self.assertEqual(marker, (self.output / "judgments.json").read_bytes())
        self.assertEqual([], list(self.output.glob("*.tmp")))

    def test_duplicate_and_pathlike_query_ids_are_rejected(self):
        for value in ["../escape", "nested/image", "/absolute", "", "Upper"]:
            self.manifest["queries"][0]["id"] = value
            self.write_inputs()
            with self.assertRaises(ValueError):
                prepare(self.corpus, self.baseline, self.output)
        self.manifest["queries"] = [{"id": "duplicate", "query": value, "type": "generated"} for value in ["one", "two"]]
        self.write_inputs()
        with self.assertRaises(ValueError):
            prepare(self.corpus, self.baseline, self.output)

    def test_changed_candidate_or_frozen_manifest_is_rejected(self):
        (self.corpus / self.manifest["images"][0]["file"]).write_bytes(b"changed")
        with self.assertRaises(ValueError):
            prepare(self.corpus, self.baseline, self.output)
        self.manifest["queries"][0]["query"] = "changed after inference"
        (self.corpus / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaises(ValueError):
            prepare(self.corpus, self.baseline, self.output)


if __name__ == "__main__":
    unittest.main()
