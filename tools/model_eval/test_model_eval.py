import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from tools.model_eval.benchmark import (
    candidate_jsonl_rows,
    evaluate_chunk_routes,
    evaluate_model,
    judgment_jsonl_rows,
    model_loading_policy,
    parse_model_revisions,
    paired_quality_comparisons,
    split_query_chunks,
    validate_options,
)
from tools.model_eval.core import (
    EmbeddingCache,
    cache_key,
    evaluate_ranking,
    load_manifest,
    query_identifier,
    query_image_indices,
    resolve_gallery_root,
    resolve_image_path,
    sha256_directory,
    sha256_file,
    write_jsonl,
)
from tools.model_eval.encoders import resolve_device
from tools.replay_photo_queries import load_rows, replay


class ModelFingerprintTest(unittest.TestCase):
    def test_explicit_model_file_set_is_stable_when_an_unused_candidate_is_added(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "vision.onnx").write_bytes(b"vision")
            (root / "text.onnx").write_bytes(b"text")
            baseline = sha256_directory(root)
            (root / "q4-candidate.onnx").write_bytes(b"candidate")
            self.assertEqual(baseline, sha256_directory(root, included_files=("vision.onnx", "text.onnx")))
            self.assertNotEqual(baseline, sha256_directory(root))

    def test_selected_model_fingerprint_rejects_missing_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "vision.onnx").write_bytes(b"vision")
            with self.assertRaisesRegex(ValueError, "missing required files"):
                sha256_directory(root, included_files=("vision.onnx", "text.onnx"))


class ModelLoadingPolicyTest(unittest.TestCase):
    def test_longclip_custom_code_is_enabled_only_for_the_pinned_candidate(self):
        self.assertEqual(
            ("creative-graphic-design/LongCLIP-B", True),
            model_loading_policy("longclip:creative-graphic-design/LongCLIP-B"),
        )
        self.assertEqual(("google/siglip2-base-patch16-224", False),
                         model_loading_policy("google/siglip2-base-patch16-224"))
        with self.assertRaisesRegex(ValueError, "only for the pinned LongCLIP-B"):
            model_loading_policy("longclip:unreviewed/repository")


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.images = [
            {"id": "a1", "file": "album1/a1.jpg", "sha256": "1" * 64, "gallery_id": "1"},
            {"id": "a2", "file": "album1/a2.jpg", "sha256": "2" * 64, "gallery_id": "1"},
            {"id": "b1", "file": "album2/b1.jpg", "sha256": "3" * 64, "gallery_id": "2"},
        ]
        self.queries = [
            {"query_id": "q1", "query": "red object", "gallery_id": "1", "family": "album", "grades": {"a2": 3}, "absent": False},
            {"query_id": "q2", "query": "missing thing", "gallery_id": "2", "family": "album", "grades": {}, "absent": True},
        ]

    def write_manifest(self, root, images=None, queries=None, image_root=None):
        manifest_path = root / "manifest.json"
        manifest = {"version": 1, "image_root": str(image_root) if image_root else "images",
                    "images": self.images if images is None else images,
                    "queries": self.queries if queries is None else queries}
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return manifest_path

    def test_loads_photobench_schema_and_limits_query_to_its_gallery(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest_path = self.write_manifest(root)
            manifest, digest, _ = load_manifest(manifest_path)
            self.assertEqual(64, len(digest))
            self.assertEqual([0, 1], query_image_indices(manifest["images"], manifest["queries"][0]))
            self.assertEqual("q1", query_identifier(manifest["queries"][0], 0))
            self.assertEqual({"a2": 3}, manifest["queries"][0]["grades"])
            self.assertTrue(manifest["queries"][1]["absent"])

    def test_expected_manifest_hash_and_judgment_group_are_enforced(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest_path = self.write_manifest(root)
            _, digest, _ = load_manifest(manifest_path)
            with self.assertRaises(ValueError):
                load_manifest(manifest_path, "0" * 64)
            invalid = [dict(self.queries[0], grades={"b1": 3}), self.queries[1]]
            invalid_path = self.write_manifest(root, queries=invalid)
            with self.assertRaisesRegex(ValueError, "outside its gallery_id"):
                load_manifest(invalid_path)
            self.assertNotEqual(digest, sha256_file(invalid_path))

    def test_image_root_and_gallery_override_resolve_inside_root(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            data_root = root / "dataset"
            (data_root / "album1").mkdir(parents=True)
            image = data_root / "album1" / "a1.jpg"
            image.write_bytes(b"image")
            manifest_path = self.write_manifest(root, image_root=data_root)
            manifest, _, _ = load_manifest(manifest_path)
            gallery_root = resolve_gallery_root(manifest_path, image_root=manifest.get("image_root"))
            self.assertEqual(image.resolve(), resolve_image_path("album1/a1.jpg", gallery_root))
            with self.assertRaises(ValueError):
                resolve_image_path("../outside.jpg", gallery_root)

    def test_large_gallery_positive_negative_schema_is_supported(self):
        images = [
            {"id": "a1", "file": "a1.jpg", "sha256": "1" * 64},
            {"id": "a2", "file": "a2.jpg", "sha256": "2" * 64},
        ]
        queries = [{"query": "visible red object", "positive": ["a1"], "explicit_negative": ["a2"], "type": "category"}]
        with tempfile.TemporaryDirectory() as folder:
            path = self.write_manifest(Path(folder), images=images, queries=queries)
            manifest, _, _ = load_manifest(path)
            query = manifest["queries"][0]
            self.assertEqual({"a1": 3, "a2": 0}, query["grades"])
            self.assertEqual("category", query["family"])

    def test_current_ten_thousand_photo_version_string_is_supported(self):
        images = [
            {"id": "a1", "file": "a1.jpg", "sha256": "1" * 64},
            {"id": "a2", "file": "a2.jpg", "sha256": "2" * 64},
        ]
        queries = [{"query": "a photo of a rare object", "positive": ["a1"],
                    "explicit_negative": ["a2"], "type": "human_verified_category"}]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            path.write_text(json.dumps({"version": "gallery-10k-v1", "images": images, "queries": queries}),
                            encoding="utf-8")
            manifest, _, _ = load_manifest(path)
            self.assertEqual(1, len(manifest["queries"]))
            self.assertEqual("human_verified_category", manifest["queries"][0]["family"])


class EmbeddingCacheTest(unittest.TestCase):
    def test_cache_key_changes_with_content_model_or_preprocessor(self):
        content, model, preprocess = "a" * 64, "b" * 64, "c" * 64
        execution = "f" * 64
        base = cache_key(content, model, preprocess, "image", execution)
        self.assertNotEqual(base, cache_key("d" * 64, model, preprocess, "image"))
        self.assertNotEqual(base, cache_key(content, "d" * 64, preprocess, "image"))
        self.assertNotEqual(base, cache_key(content, model, "d" * 64, "image"))
        self.assertNotEqual(base, cache_key(content, model, preprocess, "text"))
        self.assertNotEqual(base, cache_key(content, model, preprocess, "image", "0" * 64))

    def test_round_trip_normalizes_and_corrupt_cache_is_removed(self):
        with tempfile.TemporaryDirectory() as folder:
            cache = EmbeddingCache(folder)
            key = "e" * 64
            cache.put(key, np.array([3.0, 4.0], dtype=np.float32))
            np.testing.assert_allclose(cache.get(key), [0.6, 0.8])
            cache.path_for(key).write_bytes(b"invalid")
            self.assertIsNone(cache.get(key))
            self.assertFalse(cache.path_for(key).exists())


class RetrievalQualityTest(unittest.TestCase):
    def test_metrics_separate_unjudged_from_explicit_negatives(self):
        query = {"positive": ["p1", "p2"], "explicit_negative": ["n1"],
                 "grades": {"p1": 3, "p2": 2, "n1": 0}, "absent": False}
        result = evaluate_ranking(["n1", "u1", "p1", "p2"], query, 3)
        self.assertEqual(0, result["known_positive_hit_at_1"])
        self.assertEqual(1, result["known_positive_hit_at_5"])
        self.assertEqual(0.5, result["known_positive_recall_at_k"])
        self.assertEqual(2 / 3, result["judgment_coverage_at_k"])
        self.assertEqual(1, result["explicit_negative_in_top_k"])
        self.assertEqual(1, result["unjudged_in_top_k"])

    def test_candidate_coverage_separates_generation_misses_from_ranking_misses(self):
        query = {"positive": ["p1", "p2"], "explicit_negative": [],
                 "grades": {"p1": 3, "p2": 3}, "absent": False}
        result = evaluate_ranking(["u1", "u2", "u3", "u4", "u5", "u6", "p1"], query, 5)
        self.assertEqual(0.0, result["known_positive_recall_at_k"])
        self.assertEqual(0.5, result["known_positive_candidate_coverage_at_depth"])

    def test_android_manifest_gallery_scope_is_preserved_in_replay_candidates(self):
        images = [
            {"id": "a1", "file": "a1.jpg", "sha256": "1" * 64, "gallery_id": "A"},
            {"id": "a2", "file": "a2.jpg", "sha256": "2" * 64, "gallery_id": "A"},
            {"id": "000b", "file": "b1.jpg", "sha256": "3" * 64, "gallery_id": "B"},
        ]
        queries = [{"query_id": "album-a-q1", "query": "a red object", "gallery_id": "A",
                    "family": "album", "grades": {"a2": 3}, "absent": False}]
        manifest = {"images": images, "queries": queries}
        normalized_query = dict(queries[0], positive=["a2"], explicit_negative=[],
                                grades={"a2": 3}, absent=False)
        query_rows = [("album-a-q1", normalized_query)]
        vector_by_query = {"album-a-q1": np.array([0.0, 1.0], dtype=np.float32)}
        image_ids = ["a1", "a2", "000b"]
        matrix = np.array([[1.0, 0.0], [0.0, 1.0], [0.0, 1.0]], dtype=np.float32)
        ranked_rows, _ = evaluate_model(manifest, "clip", image_ids, matrix, query_rows, vector_by_query, 10, 5)
        query_index = {"album-a-q1": queries[0]}
        candidates = candidate_jsonl_rows(query_index, {"album-a-q1": {"routes": {"clip": ranked_rows[0][1]}}})
        judgments = judgment_jsonl_rows(query_index)
        self.assertEqual("A", candidates[0]["gallery_id"])
        self.assertEqual(["a2", "a1"], candidates[0]["routes"]["clip"])
        self.assertEqual("A", judgments[0]["gallery_id"])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            candidate_path = root / "candidates.jsonl"
            judgment_path = root / "judgments.jsonl"
            write_jsonl(candidate_path, candidates)
            write_jsonl(judgment_path, judgments)
            report = replay(load_rows(candidate_path), load_rows(judgment_path), {"clip"}, [], 1, 10, True)
            self.assertEqual(1.0, report["overall"]["hit_at_1"]["mean"])


class DeviceSelectionTest(unittest.TestCase):
    class Device:
        def __init__(self, value):
            self.value = value
            self.type = value.split(":", 1)[0]

        def __str__(self):
            return self.value

    class Cuda:
        def __init__(self, available, count=0):
            self.available = available
            self.count = count

        def is_available(self):
            return self.available

        def device_count(self):
            return self.count

    class Torch:
        def __init__(self, available, count=0):
            self.cuda = DeviceSelectionTest.Cuda(available, count)

        def device(self, value):
            return DeviceSelectionTest.Device(value)

    def test_requested_cuda_fails_hard_without_fallback(self):
        with self.assertRaisesRegex(RuntimeError, "CUDA was requested"):
            resolve_device("cuda", self.Torch(False))
        with self.assertRaisesRegex(RuntimeError, "only 1 device"):
            resolve_device("cuda:1", self.Torch(True, 1))

    def test_cpu_and_available_cuda_resolve_explicitly(self):
        self.assertEqual("cpu", str(resolve_device("cpu", self.Torch(False))))
        self.assertEqual("cuda:0", str(resolve_device("cuda", self.Torch(True, 1))))


class MetricCutoffTest(unittest.TestCase):
    class Options:
        batch_size = 1
        decode_workers = 1
        max_image_bytes = 1024
        max_image_pixels = 1024
        depth = 4
        k = 2
        dtype = "float32"
        device = "cpu"
        models = ["clip=local-model"]

    def test_candidate_depth_must_cover_top_five_metrics(self):
        with self.assertRaisesRegex(ValueError, "top-5"):
            validate_options(self.Options())


class PairedComparisonTest(unittest.TestCase):
    def test_paired_intervals_use_identical_album_scoped_queries(self):
        def model(route, scores):
            rows = []
            for query_id, value in scores.items():
                rows.append({"query_id": query_id, "gallery_id": "album1", "metrics": {
                    "known_positive_hit_at_1": value,
                    "known_positive_hit_at_5": value,
                    "known_positive_recall_at_k": value,
                    "reciprocal_rank_at_k": value,
                    "ndcg_at_k_unjudged_zero": value,
                    "known_positive_candidate_coverage_at_depth": value,
                }})
            return {"route": route, "by_query": rows}

        result = paired_quality_comparisons([
            model("baseline", {"q1": 0.0, "q2": 1.0}),
            model("candidate", {"q1": 1.0, "q2": 1.0}),
        ], samples=100)
        comparison = result["comparisons"][0]
        self.assertEqual("baseline", result["reference_route"])
        self.assertEqual(0.5, comparison["metrics"]["known_positive_hit_at_1"]["mean_delta"])
        self.assertEqual(2, comparison["metrics"]["known_positive_hit_at_1"]["queries"])
        self.assertEqual(2, comparison["metrics"]["known_positive_hit_at_1"]["independent_clusters"])
        self.assertEqual(2, len(comparison["metrics"]["known_positive_hit_at_1"]["ci95"]))


class QueryChunkingTest(unittest.TestCase):
    class Encoding:
        def __init__(self, text):
            self.ids = text.split()

    class Tokenizer:
        def encode(self, text, add_special_tokens=False):
            return QueryChunkingTest.Encoding(text)

        def decode(self, ids, skip_special_tokens=True, clean_up_tokenization_spaces=True):
            return " ".join(ids)

    class Encoder:
        def __init__(self):
            self.tokenizer = QueryChunkingTest.Tokenizer()
            self.max_text_tokens = 8

        def prepare_query_for_chunking(self, query):
            return " ".join(query.split())

    def test_long_query_is_split_into_overlapping_context_safe_chunks(self):
        query = "one two three four five six seven eight nine ten eleven twelve thirteen"
        chunks = split_query_chunks(self.Encoder(), query, overlap_tokens=10)
        self.assertEqual(3, len(chunks))
        self.assertTrue(all(len(chunk.split()) <= 6 for chunk in chunks))
        self.assertEqual(chunks[0].split()[-2:], chunks[1].split()[:2])
        self.assertEqual(chunks[1].split()[-2:], chunks[2].split()[:2])

    def test_chunk_aggregation_routes_return_full_rankings_and_metrics(self):
        images = [
            {"id": "visual", "file": "visual.jpg", "sha256": "1" * 64},
            {"id": "textual", "file": "textual.jpg", "sha256": "2" * 64},
            {"id": "mixed", "file": "mixed.jpg", "sha256": "3" * 64},
        ]
        query = {"query_id": "q1", "query": "a detailed visual and textual query",
                 "family": "docci", "positive": ["mixed"], "explicit_negative": ["visual"],
                 "grades": {"mixed": 3, "visual": 0}, "absent": False}
        vectors = {"q1": np.array([1.0, 0.0], dtype=np.float32)}
        chunk_vectors = {"q1": [np.array([1.0, 0.0], dtype=np.float32),
                                  np.array([0.0, 1.0], dtype=np.float32)]}
        matrix = np.array([[1.0, 0.0], [0.0, 1.0], [0.7, 0.7]], dtype=np.float32)
        rankings, results = evaluate_chunk_routes(
            {"images": images}, "clip", [row["id"] for row in images], matrix,
            [("q1", query)], vectors, chunk_vectors, depth=3, cutoff=3)
        self.assertEqual({"chunk_mean", "chunk_maxsim", "chunk_top2_mean", "direct_chunk_rrf"}, set(rankings))
        self.assertEqual(4, len(results))
        self.assertTrue(all(len(rows) == 1 and len(rows[0][1]) == 3 for rows in rankings.values()))
        self.assertTrue(all("known_positive_hit_at_1" in result["quality"]["overall"] for result in results))


class ModelRevisionTest(unittest.TestCase):
    def test_revisions_can_be_pinned_per_model_route(self):
        self.assertEqual({"clip": "abc123", "siglip": "def456"},
                         parse_model_revisions(["clip=abc123", "siglip=def456"], {"clip", "siglip"}))

    def test_unknown_duplicate_and_malformed_revision_routes_fail(self):
        with self.assertRaisesRegex(ValueError, "unknown model route"):
            parse_model_revisions(["missing=abc"], {"clip"})
        with self.assertRaisesRegex(ValueError, "Duplicate model revision"):
            parse_model_revisions(["clip=abc", "clip=def"], {"clip"})
        with self.assertRaisesRegex(ValueError, "alias=revision"):
            parse_model_revisions(["bad"], {"clip"})


if __name__ == "__main__":
    unittest.main()
