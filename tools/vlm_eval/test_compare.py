from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.vlm_eval.compare import compare
from tools.vlm_eval.image_input import policy_receipt
from tools.vlm_eval.run_rerank import PARAMETERS, SYSTEM_PROMPT


class CompareJournalTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.baseline = self.root / "baseline.json"
        self.judgments = self.root / "judgments.json"
        self.journal = self.root / "journal.jsonl"
        self.hashes = dict(corpus_sha256="a" * 64, baseline_sha256="b" * 64, judgments_sha256="c" * 64)
        cases = []
        ranked = []
        reviewed = []
        for query_id, kind, relevant in [("relation-01", "natural_relationship_pilot", True),
                                        ("absence-probe-01", "candidate_pool_absence_probe", False)]:
            photos = [{"id": str(index), "judgment": "relevant" if relevant and index == 1 else "irrelevant"}
                      for index in range(8)]
            ranked.append({"query": query_id, "ranking": [{"id": photo["id"]} for photo in photos]})
            reviewed.append({"id": query_id, "query": query_id, "type": kind, "candidates": photos})
            for photo in photos:
                cases.append({"query_id": query_id, "query": query_id, "image_id": photo["id"], "source_sha256": "a" * 64})
        self.baseline.write_text(json.dumps({"queries": ranked}), encoding="utf-8")
        self.judgments.write_text(json.dumps({"queries": reviewed}), encoding="utf-8")
        self.records = [{"record": "header", "version": 2, "case_count": len(cases), **self.hashes, **policy_receipt(),
                         "system_prompt": SYSTEM_PROMPT, "prompt_sha256": hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest(),
                         "parameters": {**PARAMETERS, "model": "local"},
                         "endpoint": "http://127.0.0.1:9876/v1/chat/completions",
                         "native_limits_expected": {"image_max_tokens": 64}}]
        for number, case in enumerate(cases, 1):
            answer = "YES" if case["image_id"] == "1" else "NO"
            self.records.append({"record": "case", "number": number, **case, "request_status": "completed",
                                 "http_status": 200, "answer": answer, "decision": answer.lower(), "request_elapsed_ms": 25,
                                 "raw_response": json.dumps({"choices": [{"message": {"content": answer}}]})})
        self.records.append({"record": "terminal", "complete": True, "completed_cases": len(cases)})
        validated = patch("tools.vlm_eval.compare.validate_inputs", return_value=(cases, self.hashes))
        validated.start()
        self.addCleanup(validated.stop)

    def compare(self, records):
        self.journal.write_text("\n".join(json.dumps(row) for row in records), encoding="utf-8")
        return compare(self.root, self.baseline, self.judgments, self.journal)

    def test_compares_completed_same_pool_and_counts_false_support(self):
        result = self.compare(self.records)
        self.assertEqual(0, result["baseline"]["top1_relevant"])
        self.assertEqual(1, result["reranked"]["top1_relevant"])
        self.assertEqual(["absence-probe-01"], result["fully_irrelevant_probe_ids"])
        self.assertEqual(1, result["probes_with_explicit_false_support"])
        self.assertEqual(200, result["median_eight_request_ms"])

    def test_rejects_missing_record_and_incomplete_terminal(self):
        with self.assertRaisesRegex(ValueError, "complete single run"):
            self.compare(self.records[:-1])
        records = deepcopy(self.records)
        records[-1]["complete"] = False
        with self.assertRaisesRegex(ValueError, "completed frozen run"):
            self.compare(records)

    def test_rejects_swapped_candidate_wrong_frozen_hash_and_edited_decision(self):
        edits = [(1, "image_id", "wrong", "unexpected or missing candidate"),
                 (0, "baseline_sha256", "wrong", "completed frozen run"),
                 (1, "decision", "yes", "disagrees with the response"),
                 (1, "request_elapsed_ms", float("nan"), "Invalid request timing")]
        for row, key, value, message in edits:
            with self.subTest(key=key):
                records = deepcopy(self.records)
                records[row][key] = value
                with self.assertRaisesRegex(ValueError, message):
                    self.compare(records)

    def test_rejects_changed_generation_and_render_policy(self):
        edits = [("parameters", {**PARAMETERS, "temperature": 1}), ("image_policy_sha256", "0" * 64),
                 ("system_prompt", "different prompt"), ("native_limits_expected", {"image_max_tokens": 128})]
        for key, value in edits:
            with self.subTest(key=key):
                records = deepcopy(self.records)
                records[0][key] = value
                with self.assertRaisesRegex(ValueError, "completed frozen run"):
                    self.compare(records)

    def test_rejects_edited_answer_even_when_decision_agrees_with_edit(self):
        records = deepcopy(self.records)
        records[1].update(answer="YES", decision="yes")
        with self.assertRaisesRegex(ValueError, "answer disagrees with the saved server response"):
            self.compare(records)


if __name__ == "__main__":
    unittest.main()
