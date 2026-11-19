import unittest

from tools.vlm_eval.decision import candidate_metrics, parse_decision, ranked_candidates


class DecisionTest(unittest.TestCase):
    def test_only_whole_explicit_answers_are_accepted(self):
        for value, expected in [("YES", "yes"), (" no.\n", "no"), ("Uncertain!", "uncertain"), ("\x1b[32mYes\x1b[0m", "yes")]:
            self.assertEqual(expected, parse_decision(value))
        for value in ["not yes", "YES because it is visible", "yes\nno", "No\nYES", "{\"answer\":\"yes\"}", "", "YES\x00"]:
            self.assertEqual("malformed", parse_decision(value))

    def test_reranking_is_stable_and_abstentions_do_not_become_support(self):
        candidates = [{"id": str(index)} for index in range(6)]
        decisions = dict(zip(map(str, range(6)), ["no", "malformed", "yes", "uncertain", "yes", "failed"]))
        self.assertEqual(["2", "4", "1", "3", "5", "0"], [row["id"] for row in ranked_candidates(candidates, decisions)])
        labels = dict(zip(map(str, range(6)), ["irrelevant", "irrelevant", "relevant", "unjudgeable", "irrelevant", "unjudgeable"]))
        result = candidate_metrics(candidates, labels, decisions)
        self.assertEqual("irrelevant", result["baseline"]["top1"])
        self.assertEqual("relevant", result["reranked"]["top1"])
        self.assertEqual(3, result["abstentions"])
        self.assertEqual(2, result["supported_count"])
        self.assertEqual(1, result["supported_irrelevant_count"])
        self.assertEqual(2, result["reranked"]["top5_unjudgeable"])

    def test_unknown_truth_does_not_count_as_a_negative(self):
        candidates = [{"id": "a"}, {"id": "b"}]
        result = candidate_metrics(candidates, {"a": "unjudgeable", "b": "unjudgeable"}, {"a": "yes", "b": "no"})
        self.assertIsNone(result["baseline"]["judged_top5_precision"])
        self.assertEqual(0, result["supported_irrelevant_count"])
        self.assertEqual(1, result["supported_unjudgeable_count"])

    def test_partial_duplicate_or_mismatched_candidate_inputs_fail(self):
        for candidates, decisions in [([{"id": "a"}], {}), ([{"id": "a"}, {"id": "a"}], {"a": "yes"}), ([{"id": "a"}], {"a": "invalid"})]:
            with self.assertRaises(ValueError):
                ranked_candidates(candidates, decisions)
        with self.assertRaises(ValueError):
            candidate_metrics([{"id": "a"}], {}, {"a": "yes"})
        with self.assertRaises(ValueError):
            candidate_metrics([{"id": "a"}], {"a": "positive"}, {"a": "yes"})


if __name__ == "__main__":
    unittest.main()
