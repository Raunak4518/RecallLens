import unittest

import numpy as np

from tools.model_eval.clip_text_precision_sweep import (
    app_query_token_chunks,
    app_query_tokens,
    normalized_vectors,
    parse_candidate,
)


class TokenizerStub:
    def __init__(self, ids):
        self.ids = ids
        self.input = None

    def encode(self, value, add_special_tokens=True):
        self.input = value
        return type("Encoded", (), {"ids": self.ids})()

    def token_to_id(self, token):
        if token == "<|startoftext|>":
            return 49406
        return 49407 if token == "<|endoftext|>" else None


class ClipTextPrecisionSweepTest(unittest.TestCase):
    def test_query_normalization_matches_app_and_truncation_keeps_stop_token(self):
        tokenizer = TokenizerStub(list(range(80)))
        result = app_query_tokens(tokenizer, "  A\tDog\nOn Beach  ")
        self.assertEqual("a dog on beach", tokenizer.input)
        self.assertEqual(list(range(76)) + [49407], result)

    def test_query_within_context_keeps_its_existing_tokens(self):
        tokenizer = TokenizerStub([49406, 320, 1929, 49407])
        self.assertEqual([49406, 320, 1929, 49407], app_query_tokens(tokenizer, "a dog"))

    def test_long_query_chunks_overlap_and_cover_the_token_sequence(self):
        tokenizer = TokenizerStub(list(range(160)))
        chunks = app_query_token_chunks(tokenizer, "Long   QUERY")
        self.assertEqual("long query", tokenizer.input)
        self.assertEqual(3, len(chunks))
        self.assertEqual([49406, *range(75), 49407], chunks[0])
        self.assertEqual([49406, *range(65, 140), 49407], chunks[1])
        self.assertEqual([49406, *range(130, 160), 49407], chunks[2])
        self.assertTrue(all(len(chunk) <= 77 for chunk in chunks))

    def test_normalized_vectors_require_valid_nonzero_rows(self):
        actual = normalized_vectors([[3.0, 4.0], [5.0, 12.0]])
        np.testing.assert_allclose(actual, [[0.6, 0.8], [5 / 13, 12 / 13]])
        with self.assertRaisesRegex(ValueError, "zero or invalid"):
            normalized_vectors([[0.0, 0.0]])

    def test_candidate_requires_alias_and_graph_path(self):
        alias, path = parse_candidate("float_text=./text_model.onnx")
        self.assertEqual("float_text", alias)
        self.assertTrue(path.is_absolute())
        with self.assertRaisesRegex(ValueError, "alias=ONNX-path"):
            parse_candidate("missing-separator")
        with self.assertRaisesRegex(ValueError, "Invalid candidate alias"):
            parse_candidate("-invalid=graph.onnx")


if __name__ == "__main__":
    unittest.main()
