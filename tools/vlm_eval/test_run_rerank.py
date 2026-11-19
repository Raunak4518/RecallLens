import contextlib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from PIL import Image

from tools.vlm_eval.run_rerank import PARAMETERS, SYSTEM_PROMPT, loopback_endpoint, run, validate_inputs


class RunRerankTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.corpus = self.root / "corpus"
        self.corpus.mkdir()
        self.baseline = self.root / "baseline.json"
        self.judgments = self.root / "judgments.json"
        self.output = self.root / "output"
        images = []
        for index in range(8):
            path = self.corpus / f"{index}.jpg"
            Image.new("RGB", (8, 8), (index * 25, 0, 0)).save(path)
            images.append({"id": str(index), "file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        queries = [{"id": "query-" + str(index), "query": "visible fixture query " + str(index)} for index in range(24)]
        self.manifest = {"images": images, "queries": queries}
        self.report = {"complete": True, "queries": [{"query": query["query"],
            "ranking": [{"id": str(index), "score": .9 - index / 10} for index in range(8)]} for query in queries]}
        self.review = {"review_status": "frozen_provisional_ai_visual_review_before_vlm_test_outputs", "queries": [
            {"id": query["id"], "query": query["query"], "candidates": [
                {**image, "judgment": "irrelevant", "reason": "Generated fixture judgment, not model truth"}
                for image in reversed(images)]} for query in queries]}
        self.write_inputs()

    def write_inputs(self):
        manifest_bytes = json.dumps(self.manifest).encode()
        (self.corpus / "manifest.json").write_bytes(manifest_bytes)
        manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
        self.report["manifest_sha256"] = manifest_sha
        baseline_bytes = json.dumps(self.report).encode()
        self.baseline.write_bytes(baseline_bytes)
        self.review["corpus_sha256"] = manifest_sha
        self.review["baseline_sha256"] = hashlib.sha256(baseline_bytes).hexdigest()
        review_bytes = json.dumps(self.review).encode()
        self.judgments.write_bytes(review_bytes)
        self.expected = hashlib.sha256(review_bytes).hexdigest()

    @contextlib.contextmanager
    def server(self, responder):
        requests = []
        gets = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(handler):
                body = json.loads(handler.rfile.read(int(handler.headers["Content-Length"])))
                requests.append(body)
                responder(handler, len(requests), body)

            def do_GET(handler):
                gets.append(handler.path)
                handler.send_response(200)
                handler.end_headers()

            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}", requests, gets
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def run_at(self, base_url, **kwargs):
        return run(base_url, self.corpus, self.baseline, self.judgments, self.output, expected_sha=self.expected, **kwargs)

    def journal(self):
        return [json.loads(line) for line in (self.output / "journal.jsonl").read_text(encoding="utf-8").splitlines()]

    def answer(self, handler, answer):
        payload = json.dumps({"choices": [{"message": {"content": answer}}]}).encode()
        handler.send_response(200)
        handler.send_header("Content-Length", str(len(payload)))
        handler.end_headers()
        handler.wfile.write(payload)

    def test_accepts_only_literal_loopback_http_explicit_port(self):
        self.assertEqual("http://127.0.0.1:8080/v1/chat/completions", loopback_endpoint("http://127.0.0.1:8080/"))
        self.assertEqual("http://[::1]:8080/v1/chat/completions", loopback_endpoint("http://[::1]:8080"))
        for url in ["http://localhost:8080", "http://example.com:8080", "http://192.168.0.1:8080", "https://127.0.0.1:8080",
                    "http://127.0.0.1", "http://127.0.0.1:8080/path", "http://user@127.0.0.1:8080", "http://127.0.0.1:8080?x=1",
                    "http://127.0.0.1:8080#fragment", "http://[::1%25lo]:8080", "http://127.0.0.1:65536"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                loopback_endpoint(url)

    def test_validates_frozen_hash_top8_judgments_and_source_bytes(self):
        cases, _ = validate_inputs(self.corpus, self.baseline, self.judgments, self.expected)
        self.assertEqual(192, len(cases))
        self.assertEqual("0", cases[0]["image_id"])
        with self.assertRaises(ValueError):
            validate_inputs(self.corpus, self.baseline, self.judgments)
        (self.corpus / "0.jpg").write_bytes(b"changed")
        with self.assertRaises(ValueError):
            validate_inputs(self.corpus, self.baseline, self.judgments, self.expected)

    def test_rejects_unfrozen_incomplete_or_duplicate_pools(self):
        for mutation in [lambda: self.review.update(review_status="pending"),
                         lambda: self.review["queries"][0]["candidates"].pop(),
                         lambda: self.review["queries"][0]["candidates"].__setitem__(1, self.review["queries"][0]["candidates"][0]),
                         lambda: self.review["queries"][0]["candidates"][0].update(judgment=None),
                         lambda: self.report.update(complete=False)]:
            original_review = json.loads(json.dumps(self.review))
            original_report = json.loads(json.dumps(self.report))
            mutation()
            self.write_inputs()
            with self.assertRaises(ValueError):
                validate_inputs(self.corpus, self.baseline, self.judgments, self.expected)
            self.review, self.report = original_review, original_report
        self.write_inputs()

    def test_mock_run_journals_all_cases_controls_and_abstentions_without_truth_upload(self):
        def respond(handler, number, body):
            self.answer(handler, ["YES", "NO", "UNCERTAIN", "YES because I think so"][number % 4])
        with self.server(respond) as (url, requests, _):
            result = self.run_at(url)
            self.assertTrue(result["complete"])
            self.assertEqual(192, len(requests))
            for request in requests:
                self.assertEqual(SYSTEM_PROMPT, request["messages"][0]["content"])
                for key, value in PARAMETERS.items():
                    self.assertEqual(value, request[key])
                self.assertEqual(2, len(request["messages"][1]["content"]))
                self.assertTrue(request["messages"][1]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,"))
                self.assertNotIn("Generated fixture judgment", json.dumps(request))
            with self.assertRaises(FileExistsError):
                self.run_at(url)
            self.assertEqual(192, len(requests))
        rows = self.journal()
        self.assertEqual(self.expected, rows[0]["judgments_sha256"])
        self.assertEqual(384, rows[0]["image_policy"]["longest_edge"])
        self.assertTrue(rows[0]["image_policy_sha256"])
        self.assertTrue(rows[0]["pillow_version"])
        self.assertEqual({"yes", "no", "uncertain", "malformed"}, {row["decision"] for row in rows[1:-1]})
        self.assertTrue(all(row["elapsed_ms"] >= 0 and row["http_status"] == 200 and row["raw_response"] for row in rows[1:-1]))
        for row in rows[1:-1]:
            self.assertEqual(row["elapsed_ms"], row["request_elapsed_ms"])
            self.assertGreaterEqual(row["case_elapsed_ms"], row["host_preprocess_ms"] + row["request_elapsed_ms"])
            self.assertTrue(row["render_sha256"])
            self.assertEqual((8, 8), (row["render_width"], row["render_height"]))
        self.assertTrue(rows[-1]["complete"])
        self.assertEqual(192, rows[-1]["completed_cases"])

    def test_redirects_are_not_followed_and_three_errors_stop_without_retry(self):
        def redirect(handler, number, body):
            handler.send_response(302)
            handler.send_header("Location", "/redirected")
            handler.end_headers()
        with self.server(redirect) as (url, requests, gets):
            with self.assertRaisesRegex(RuntimeError, "three consecutive"):
                self.run_at(url)
            self.assertEqual(3, len(requests))
            self.assertEqual([], gets)
        rows = self.journal()
        self.assertEqual(["failed"] * 3, [row["decision"] for row in rows[1:-1]])
        self.assertFalse(rows[-1]["complete"])

    def test_total_timeout_stops_slow_responses(self):
        def delayed(handler, number, body):
            time.sleep(.15)
            with contextlib.suppress(OSError):
                self.answer(handler, "YES")
        started = time.monotonic()
        with self.server(delayed) as (url, requests, _):
            with self.assertRaises(RuntimeError):
                self.run_at(url, timeout=.03)
            self.assertEqual(3, len(requests))
        self.assertLess(time.monotonic() - started, 2)
        self.assertFalse(self.journal()[-1]["complete"])

    def test_connection_failure_does_not_retry_a_post(self):
        def disconnect(handler, number, body):
            handler.connection.close()
            handler.close_connection = True
        with self.server(disconnect) as (url, requests, _):
            with self.assertRaises(RuntimeError):
                self.run_at(url)
            self.assertEqual(3, len(requests))
        self.assertEqual(3, self.journal()[-1]["completed_cases"])


if __name__ == "__main__":
    unittest.main()
