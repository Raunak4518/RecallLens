from base64 import b64decode, b64encode
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from threading import Barrier, Event
import unittest

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from fastapi.testclient import TestClient
from PIL import Image
from io import BytesIO

from worker.service import ServiceConfig, create_app
from worker import test_ocr_worker as ocr_fixtures


class ServiceTest(unittest.TestCase):
    request = ocr_fixtures.OcrWorkerTest.request
    decrypt = ocr_fixtures.OcrWorkerTest.decrypt

    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.private = X25519PrivateKey.generate()
        self.worker_id = sha256(self.private.public_key().public_bytes_raw()).hexdigest()
        self.database = Path(self.temporary.name) / "queue.sqlite3"
        self.now = [100.0]
        self.tokens = {"alice": "a" * 40, "bob": "b" * 40}
        self.headers = {name: {"Authorization": f"Bearer {token}"} for name, token in self.tokens.items()}
        with BytesIO() as output:
            Image.new("RGB", (24, 24), (45, 96, 120)).save(output, "PNG")
            self.png = output.getvalue()

    def app(self, recognize=lambda image: "PRIVATE OCR RESULT", **bounds):
        @contextmanager
        def recognizer():
            yield recognize
        config = ServiceConfig({name: sha256(token.encode()).hexdigest() for name, token in self.tokens.items()},
                               self.database, **bounds)
        return create_app(config, self.private, recognizer, start_workers=False, clock=lambda: self.now[0])

    def submit(self, client, encoded=None, caller="alice"):
        return client.post("/v1/jobs", content=encoded or self.request(), headers=self.headers[caller])

    def test_authorization_tls_and_owner_isolation(self):
        app = self.app()
        with TestClient(app, base_url="https://testserver") as client:
            self.assertEqual(401, client.get("/v1/worker-key").status_code)
            self.assertEqual(401, client.get("/v1/worker-key", headers={"Authorization": "Bearer " + "x" * 40}).status_code)
            encoded = self.request()
            response = self.submit(client, encoded)
            self.assertEqual(202, response.status_code)
            job = response.json()["id"]
            path = f"/v1/jobs/{job}"
            self.assertEqual(404, client.get(path, headers=self.headers["bob"]).status_code)
            self.assertEqual(404, client.delete(path, headers=self.headers["bob"]).status_code)
            self.assertEqual(404, self.submit(client, encoded, caller="bob").status_code)
            self.assertEqual(self.worker_id, client.get("/v1/worker-key", headers=self.headers["alice"]).json()["worker"])
        with TestClient(app, base_url="http://testserver") as client:
            self.assertEqual(400, client.get("/v1/worker-key", headers=self.headers["alice"]).status_code)
        local = self.app(allow_local_http=True)
        with TestClient(local, base_url="http://127.0.0.1", client=("127.0.0.1", 50000)) as client:
            self.assertEqual(200, client.get("/v1/worker-key", headers=self.headers["alice"]).status_code)

    def test_expiry_uses_one_cutoff_for_job_receipt_and_claim(self):
        app = self.app()
        jobs = app.state.jobs
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client).json()["id"]
            self.assertTrue(jobs.run_one())
            self.now[0] = 3700.0
            reads = []

            def advancing_clock():
                reads.append(True)
                return self.now[0] + len(reads)

            jobs.clock = advancing_clock
            with closing(jobs.connect()) as connection, connection:
                jobs.expire(connection)
                self.assertEqual(1, len(reads))
                for table in ("service_jobs", "receipts", "job_claims"):
                    self.assertEqual(0, connection.execute(f"SELECT COUNT(*) FROM {table} WHERE id = ?", (job,)).fetchone()[0])

    def test_queued_request_survives_restart_and_result_is_encrypted(self):
        app = self.app()
        encoded = self.request()
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client, encoded).json()["id"]
            with closing(sqlite3.connect(self.database)) as db:
                self.assertEqual(encoded, db.execute("SELECT input FROM service_jobs WHERE id = ?", (job,)).fetchone()[0])
        reopened = self.app()
        self.assertTrue(reopened.state.jobs.run_one())
        with TestClient(reopened, base_url="https://testserver") as client:
            response = client.get(f"/v1/jobs/{job}", headers=self.headers["alice"])
            self.assertEqual("succeeded", response.json()["state"])
            ciphertext = json.dumps(response.json()["result"]).encode()
            self.assertEqual("PRIVATE OCR RESULT", self.decrypt(ciphertext)["text"])
            self.assertEqual(202, self.submit(client, encoded).status_code)
            self.assertEqual("succeeded", self.submit(client, encoded).json()["state"])
        self.assertNotIn(b"PRIVATE OCR RESULT", self.database.read_bytes())

    def test_tamper_conflicting_replay_and_stream_bound(self):
        app = self.app(max_body_bytes=4096)
        with TestClient(app, base_url="https://testserver") as client:
            encoded = self.request()
            packet = json.loads(encoded)
            box = bytearray(b64decode(packet["box"]))
            box[-1] ^= 1
            packet["box"] = b64encode(box).decode()
            self.assertEqual(400, self.submit(client, json.dumps(packet).encode()).status_code)
            self.assertEqual(202, self.submit(client, encoded).status_code)
            self.assertEqual(409, self.submit(client, self.request(json.loads(encoded)["job"])).status_code)
            self.assertEqual(413, self.submit(client, b"x" * 4097).status_code)

    def test_pending_and_retained_quotas(self):
        app = self.app(max_pending_per_owner=1, max_jobs_per_owner=2, max_jobs_total=3)
        with TestClient(app, base_url="https://testserver") as client:
            self.assertEqual(202, self.submit(client).status_code)
            self.assertEqual(429, self.submit(client).status_code)
            self.assertTrue(app.state.jobs.run_one())
            self.assertEqual(202, self.submit(client).status_code)
            self.assertTrue(app.state.jobs.run_one())
            self.assertEqual(429, self.submit(client).status_code)
            self.assertEqual(202, self.submit(client, caller="bob").status_code)
            self.assertTrue(app.state.jobs.run_one())
            self.assertEqual(429, self.submit(client, caller="bob").status_code)

    def test_deletion_fences_running_completion(self):
        entered = Event()
        finish = Event()
        def recognize(image):
            entered.set()
            self.assertTrue(finish.wait(5))
            return "REVOKED RESULT"
        app = self.app(recognize)
        with TestClient(app, base_url="https://testserver") as client:
            encoded = self.request()
            job = self.submit(client, encoded).json()["id"]
            with ThreadPoolExecutor(max_workers=1) as pool:
                processing = pool.submit(app.state.jobs.run_one)
                try:
                    self.assertTrue(entered.wait(5))
                    self.assertEqual(200, client.delete(f"/v1/jobs/{job}", headers=self.headers["alice"]).status_code)
                finally:
                    finish.set()
                self.assertTrue(processing.result(timeout=10))
            self.assertEqual(410, client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).status_code)
            self.assertEqual(410, self.submit(client, encoded).status_code)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual((None, None, "deleted"), db.execute("SELECT input, result, state FROM service_jobs").fetchone())
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0])

    def test_expiry_fences_running_result_and_purges_ciphertext(self):
        def recognize(image):
            self.now[0] = 111.0
            return "EXPIRED RESULT"
        app = self.app(recognize, ttl_seconds=10)
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client).json()["id"]
            self.assertTrue(app.state.jobs.run_one())
            self.assertFalse(app.state.jobs.run_one())
            self.assertEqual(404, client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).status_code)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM service_jobs").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM job_claims").fetchone()[0])

    def test_retry_and_attempt_limit(self):
        attempts = []
        def recognize(image):
            attempts.append(1)
            raise RuntimeError("Transient model detail should remain private")
        app = self.app(recognize, max_attempts=2)
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client).json()["id"]
            self.assertTrue(app.state.jobs.run_one())
            self.assertEqual("queued", client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).json()["state"])
            self.assertTrue(app.state.jobs.run_one())
            response = client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).json()
            self.assertEqual("failed", response["state"])
            self.assertEqual("processing_failed", response["error"])
            self.assertFalse(app.state.jobs.run_one())
        self.assertEqual(2, len(attempts))

    def test_distinct_service_jobs_infer_concurrently(self):
        barrier = Barrier(2)
        def recognize(image):
            barrier.wait(timeout=5)
            return "PARALLEL"
        app = self.app(recognize)
        with TestClient(app, base_url="https://testserver") as client:
            jobs = [self.submit(client).json()["id"] for _ in range(2)]
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = [pool.submit(app.state.jobs.run_one) for _ in jobs]
                self.assertTrue(all(future.result(timeout=10) for future in results))
            for job in jobs:
                self.assertEqual("succeeded", client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).json()["state"])

    def test_crashed_queue_claim_resumes_after_lease_expiry(self):
        app = self.app(lease_seconds=5)
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client).json()["id"]
        with closing(sqlite3.connect(self.database)) as db, db:
            db.execute("UPDATE service_jobs SET state = 'running', lease_owner = 'crashed', lease_until = 105, attempts = 1")
        reopened = self.app(lease_seconds=5)
        self.assertFalse(reopened.state.jobs.run_one())
        self.now[0] = 106
        self.assertTrue(reopened.state.jobs.run_one())
        with TestClient(reopened, base_url="https://testserver") as client:
            result = client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).json()
            self.assertEqual("succeeded", result["state"])
            self.assertEqual(2, result["attempts"])

    def test_crashed_final_attempt_becomes_failed(self):
        app = self.app(max_attempts=1)
        with TestClient(app, base_url="https://testserver") as client:
            job = self.submit(client).json()["id"]
            with closing(sqlite3.connect(self.database)) as db, db:
                db.execute("UPDATE service_jobs SET state = 'running', lease_owner = 'crashed', lease_until = 99, attempts = 1")
            self.assertFalse(app.state.jobs.run_one())
            result = client.get(f"/v1/jobs/{job}", headers=self.headers["alice"]).json()
            self.assertEqual("failed", result["state"])
            self.assertEqual("attempts_exhausted", result["error"])


if __name__ == "__main__":
    unittest.main()
