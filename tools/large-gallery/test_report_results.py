import copy
import unittest

from report_results import build_metrics


class ReportResultsTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"images": [{"id": str(value)} for value in range(8)],
            "queries": [{"query": "test", "type": "category", "positive": ["6", "7"], "explicit_negative": ["1"]}]}
        self.report = {"complete": True, "manifest_sha256": "frozen", "queries": [{
            "query": "test", "encode_ms": 10, "rank_ms": 2,
            "ranking": [{"id": str(value)} for value in range(8)]}]}

    def metric(self):
        return build_metrics(self.manifest, self.report, "frozen")["metrics"][0]

    def test_judged_precision_uses_only_actual_top_five_slots(self):
        self.assertEqual(0, self.metric()["judged_top5_precision"])
        self.assertEqual(4, self.metric()["top5_unjudged"])
        self.assertEqual(0, self.metric()["top5_known_positive_count"])

    def test_all_unjudged_top_five_has_no_precision_estimate(self):
        self.manifest["queries"][0]["explicit_negative"] = ["5"]
        self.assertIsNone(self.metric()["judged_top5_precision"])

    def test_positive_only_queries_cannot_claim_precision(self):
        self.manifest["queries"][0].update(positive=["0"], explicit_negative=[])
        self.assertIsNone(self.metric()["judged_top5_precision"])
        self.assertEqual(1, self.metric()["top5_known_positive_count"])

    def test_stale_incomplete_duplicate_or_partial_results_are_rejected(self):
        for change in ("manifest", "incomplete", "duplicate", "missing", "queries", "conflict"):
            report, manifest = copy.deepcopy(self.report), copy.deepcopy(self.manifest)
            if change == "manifest": report["manifest_sha256"] = "old"
            if change == "incomplete": report["complete"] = False
            if change == "duplicate": report["queries"][0]["ranking"][0]["id"] = "1"
            if change == "missing": report["queries"][0]["ranking"].pop()
            if change == "queries": report["queries"] = []
            if change == "conflict": manifest["queries"][0]["positive"].append("1")
            with self.subTest(change=change), self.assertRaises(ValueError):
                build_metrics(manifest, report, "frozen")

    def test_duplicate_manifest_ids_and_invalid_timings_are_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["images"].append(manifest["images"][0])
        with self.assertRaises(ValueError):
            build_metrics(manifest, self.report, "frozen")
        for value in (-1, float("nan"), float("inf"), "12", True):
            for key in ("encode_ms", "rank_ms"):
                report = copy.deepcopy(self.report)
                report["queries"][0][key] = value
                with self.subTest(value=value, key=key), self.assertRaises(ValueError):
                    build_metrics(self.manifest, report, "frozen")


if __name__ == "__main__":
    unittest.main()
