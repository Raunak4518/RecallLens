from base64 import b64decode, b64encode
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from threading import Barrier, Event, Lock
import unittest
from uuid import uuid4

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image

from worker.ocr_worker import HPKE, JobInProgress, JobLeaseLost, OcrWorker, wire_info


class OcrWorkerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.ledger = Path(self.temporary.name) / "receipts.sqlite3"
        self.private = X25519PrivateKey.generate()
        self.worker_id = sha256(self.private.public_key().public_bytes_raw()).hexdigest()
        with BytesIO() as output:
            Image.new("RGB", (24, 24), (45, 96, 120)).save(output, "PNG")
            self.png = output.getvalue()

    def request(self, job=None):
        job = job or str(uuid4())
        key = bytes(range(32))
        clear = {
            "mode": "CLOUD_FULL", "task": "OCR_ENGLISH", "policy": "photo-export-v1",
            "sha256": sha256(self.png).hexdigest(), "source": str(uuid4()),
            "return_key": b64encode(key).decode(), "png": b64encode(self.png).decode(),
        }
        box = HPKE.encrypt(json.dumps(clear).encode(), self.private.public_key(), wire_info("request", self.worker_id, job))
        packet = {"v": 1, "worker": self.worker_id, "job": job, "box": b64encode(box).decode()}
        return json.dumps(packet).encode()

    def decrypt(self, response):
        packet = json.loads(response)
        box = b64decode(packet["box"])
        return json.loads(AESGCM(bytes(range(32))).decrypt(
            box[:12], box[12:], wire_info("result", self.worker_id, packet["job"]),
        ))

    def test_distinct_jobs_can_both_enter_inference(self):
        barrier = Barrier(2)

        def recognize(image):
            barrier.wait(timeout=5)
            return "CONCURRENT CONFIDENTIAL MARKER"

        worker = OcrWorker(self.private, self.ledger, recognize)
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(worker.process, self.request())
            second = executor.submit(worker.process, self.request())
            for future in (first, second):
                self.assertEqual("CONCURRENT CONFIDENTIAL MARKER", self.decrypt(future.result(timeout=10))["text"])
        self.assertNotIn(b"CONCURRENT CONFIDENTIAL MARKER", self.ledger.read_bytes())
        with closing(sqlite3.connect(self.ledger)) as connection, connection:
            self.assertEqual(2, connection.execute("SELECT COUNT(*) FROM receipts").fetchone()[0])
            self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM job_claims").fetchone()[0])

    def test_model_identity_is_inside_authenticated_ciphertext(self):
        model = {"engine": "fixture-engine", "version": "1", "weights_sha256": "a" * 64,
                 "preprocessing": "rgb-oriented1600-v1"}
        worker = OcrWorker(self.private, self.ledger, lambda image: "text", model_info=model)
        model["version"] = "caller-mutated"
        response = worker.process(self.request())
        observed = self.decrypt(response)["model"]
        self.assertEqual("1", observed["version"])
        self.assertEqual("a" * 64, observed["weights_sha256"])
        self.assertNotIn(b"fixture-engine", response)
        packet = json.loads(response)
        changed = bytearray(b64decode(packet["box"]))
        changed[-1] ^= 1
        packet["box"] = b64encode(changed).decode()
        with self.assertRaises(InvalidTag):
            self.decrypt(json.dumps(packet).encode())

    def test_duplicate_active_job_is_retryable_and_receipt_is_stable(self):
        entered = Event()
        finish = Event()
        calls = []

        def recognize(image):
            calls.append(1)
            entered.set()
            self.assertTrue(finish.wait(5))
            return "DUPLICATE SAFE"

        worker = OcrWorker(self.private, self.ledger, recognize, wait_seconds=0)
        encoded = self.request()
        with ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(worker.process, encoded)
            try:
                self.assertTrue(entered.wait(5))
                with self.assertRaises(JobInProgress):
                    worker.process(encoded)
                with self.assertRaisesRegex(ValueError, "different request"):
                    worker.process(self.request(json.loads(encoded)["job"]))
            finally:
                finish.set()
            receipt = pending.result(timeout=10)
        reopened = OcrWorker(self.private, self.ledger, lambda image: self.fail("Duplicate inference"))
        self.assertEqual(receipt, reopened.process(encoded))
        self.assertEqual(1, len(calls))

    def test_waiting_duplicate_receives_same_result_once(self):
        entered = Event()
        finish = Event()
        lock = Lock()
        calls = 0

        def recognize(image):
            nonlocal calls
            with lock:
                calls += 1
            entered.set()
            self.assertTrue(finish.wait(5))
            return "WAITING DUPLICATE"

        worker = OcrWorker(self.private, self.ledger, recognize)
        encoded = self.request()
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(worker.process, encoded)
            try:
                self.assertTrue(entered.wait(5))
                second = executor.submit(worker.process, encoded)
            finally:
                finish.set()
            self.assertEqual(first.result(timeout=10), second.result(timeout=10))
        self.assertEqual(1, calls)

    def test_expired_claim_is_recovered_and_stale_completion_is_rejected(self):
        now = [100.0]
        entered = Event()
        finish = Event()
        encoded = self.request()

        def abandoned(image):
            entered.set()
            self.assertTrue(finish.wait(5))
            return "STALE"

        stale = OcrWorker(self.private, self.ledger, abandoned, lease_seconds=1, clock=lambda: now[0])
        replacement = OcrWorker(self.private, self.ledger, lambda image: "RECOVERED", clock=lambda: now[0])
        with ThreadPoolExecutor(max_workers=1) as executor:
            old = executor.submit(stale.process, encoded)
            try:
                self.assertTrue(entered.wait(5))
                now[0] = 102.0
                receipt = replacement.process(encoded)
            finally:
                finish.set()
            with self.assertRaises(JobLeaseLost):
                old.result(timeout=10)
        self.assertEqual("RECOVERED", self.decrypt(receipt)["text"])
        self.assertEqual(receipt, replacement.process(encoded))

    def test_failure_releases_lease_and_keeps_request_binding(self):
        encoded = self.request()
        attempts = []

        def recognize(image):
            attempts.append(1)
            if len(attempts) == 1:
                raise RuntimeError("Engine failed")
            return "RETRIED"

        worker = OcrWorker(self.private, self.ledger, recognize)
        with self.assertRaisesRegex(RuntimeError, "Engine failed"):
            worker.process(encoded)
        with self.assertRaisesRegex(ValueError, "different request"):
            worker.process(self.request(json.loads(encoded)["job"]))
        self.assertEqual("RETRIED", self.decrypt(worker.process(encoded))["text"])
        self.assertEqual(2, len(attempts))

    def test_legacy_receipt_table_remains_readable(self):
        encoded = self.request()
        with closing(sqlite3.connect(self.ledger)) as connection, connection:
            connection.execute("CREATE TABLE receipts (id TEXT PRIMARY KEY, digest BLOB NOT NULL, result BLOB NOT NULL)")
            connection.execute("INSERT INTO receipts VALUES (?, ?, ?)",
                               (json.loads(encoded)["job"], sha256(encoded).digest(), b"existing receipt bytes"))
        worker = OcrWorker(self.private, self.ledger, lambda image: self.fail("Legacy replay invoked OCR"))
        self.assertEqual(b"existing receipt bytes", worker.process(encoded))
        with self.assertRaisesRegex(ValueError, "different request"):
            worker.process(self.request(json.loads(encoded)["job"]))

    def test_tampered_request_never_creates_a_claim(self):
        packet = json.loads(self.request())
        box = bytearray(b64decode(packet["box"]))
        box[-1] ^= 1
        packet["box"] = b64encode(box).decode()
        worker = OcrWorker(self.private, self.ledger, lambda image: self.fail("Tamper invoked OCR"))
        with self.assertRaises(InvalidTag):
            worker.process(json.dumps(packet).encode())
        self.assertFalse(self.ledger.exists())


if __name__ == "__main__":
    unittest.main()
