import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from tools import verify_local_core as verifier


def android_result(passed=1, skipped=0):
    codes = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n" * passed
    codes += "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -4\n" * skipped
    return codes + f"OK ({passed + skipped} tests)\nINSTRUMENTATION_CODE: -1\n"


class AndroidTranscriptTest(unittest.TestCase):
    def test_executed_and_skipped_are_counted_separately(self):
        self.assertEqual({"reported": 3, "executed_passed": 2, "skipped": 1},
                         verifier.parse_android(android_result(2, 1)))

    def test_assertions_crashes_partial_output_and_count_mismatch_fail(self):
        valid = android_result()
        invalid = (
            "INSTRUMENTATION_STATUS_CODE: 1\n",
            valid.replace("CODE: 0", "CODE: -2"),
            valid.replace("CODE: 0", "CODE: -1"),
            valid.replace("OK (1 tests)", "OK (2 tests)"),
            valid.replace("INSTRUMENTATION_CODE: -1", ""),
            valid + "INSTRUMENTATION_FAILED: Process crashed\n",
            valid + "FAILURES!!!\n",
            valid + valid,
            android_result(0, 0),
        )
        for transcript in invalid:
            with self.subTest(transcript=transcript), self.assertRaises(RuntimeError):
                verifier.parse_android(transcript)

    def test_class_selection_is_known_unique_and_workflows_are_explicit(self):
        self.assertEqual(verifier.CLASSES, verifier.selected_classes(None, False))
        self.assertEqual(("SearchGuidanceTest",), verifier.selected_classes(" SearchGuidanceTest ", False))
        self.assertIn("ClothingWorkflowTest", verifier.selected_classes(None, True))
        for value in ("Unknown", "SearchGuidanceTest,SearchGuidanceTest", "ClothingWorkflowTest", ","):
            with self.subTest(value=value), self.assertRaises(ValueError):
                verifier.selected_classes(value, False)


class ClassReceiptTest(unittest.TestCase):
    def test_resume_requires_matching_identity_counts_and_untampered_complete_transcript(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            transcript = root / "Class.txt"
            receipt_path = root / "Class.json"
            transcript.write_text(android_result(), encoding="utf-8")
            receipt = {"class": "Class", "signature": "current", "counts": verifier.parse_android(android_result()),
                       "transcript_sha256": verifier.file_sha256(transcript)}
            verifier.atomic_json(receipt_path, receipt)
            self.assertEqual(receipt, verifier.reusable_class(receipt_path, transcript, "current", "Class"))
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "changed-sources-or-apk", "Class"))
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "current", "Other"))
            transcript.write_text(android_result() + "tampered", encoding="utf-8")
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "current", "Class"))
            receipt["transcript_sha256"] = verifier.file_sha256(transcript)
            receipt["counts"]["skipped"] = 1
            verifier.atomic_json(receipt_path, receipt)
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "current", "Class"))
            transcript.write_text("INSTRUMENTATION_STATUS_CODE: 1\n", encoding="utf-8")
            receipt["transcript_sha256"] = verifier.file_sha256(transcript)
            verifier.atomic_json(receipt_path, receipt)
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "current", "Class"))
            receipt_path.write_text("{broken", encoding="utf-8")
            self.assertIsNone(verifier.reusable_class(receipt_path, transcript, "current", "Class"))


class VerificationRunTest(unittest.TestCase):
    def run_fixture(self, root, instrumentation, resume=False):
        for path in ("app/build/outputs/apk/feasibility/app-feasibility.apk",
                     "app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"):
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                target.write_bytes(b"stable-build")

        def run(command, **kwargs):
            transcript = instrumentation(command) if "instrument" in command else "Success\n"
            kwargs["stdout"].write(transcript)
            kwargs["stdout"].flush()
            return SimpleNamespace(returncode=0)

        argv = ["verify", "--adb", "adb", "--classes", "PhotoQueryExecutionTest,SearchGuidanceTest"]
        if resume:
            argv.append("--resume")
        with patch.object(verifier, "ROOT", root), patch.object(verifier, "snapshot", return_value={"source": "hash"}), \
                patch.object(verifier.subprocess, "run", side_effect=run), patch("sys.argv", argv), \
                contextlib.redirect_stdout(io.StringIO()):
            verifier.main()

    def test_each_class_gets_separate_invocation_and_resume_reuses_only_exact_build(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            calls = []

            def instrumentation(command):
                calls.append(command)
                return android_result()

            self.run_fixture(root, instrumentation)
            self.assertEqual(2, len(calls))
            self.assertTrue(all("," not in command[command.index("class") + 1] for command in calls))
            self.run_fixture(root, instrumentation, resume=True)
            self.assertEqual(2, len(calls))
            receipt = json.loads((root / "app/build/local-core-verification/receipt.json").read_text())
            self.assertEqual(2, receipt["android_executed_passed"])
            self.assertTrue(all(item["reused"] for item in receipt["class_receipts"]))
            (root / "app/build/outputs/apk/feasibility/app-feasibility.apk").write_bytes(b"changed-build")
            self.run_fixture(root, instrumentation, resume=True)
            self.assertEqual(4, len(calls))

    def test_failure_stops_without_retry_or_overall_receipt_and_preserves_prior_class(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            calls = []

            def instrumentation(command):
                calls.append(command)
                return android_result() if len(calls) == 1 else "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n"

            with self.assertRaises(RuntimeError):
                self.run_fixture(root, instrumentation)
            output = root / "app/build/local-core-verification"
            self.assertEqual(2, len(calls))
            self.assertFalse((output / "receipt.json").exists())
            self.assertTrue((output / "classes/PhotoQueryExecutionTest.json").exists())
            self.assertFalse((output / "classes/SearchGuidanceTest.json").exists())
            self.assertIn("FAILURES!!!", (output / "classes/SearchGuidanceTest.txt").read_text())
            self.run_fixture(root, lambda command: android_result(), resume=True)
            receipt = json.loads((output / "receipt.json").read_text())
            self.assertTrue(receipt["class_receipts"][0]["reused"])
            self.assertFalse(receipt["class_receipts"][1]["reused"])


if __name__ == "__main__":
    unittest.main()
