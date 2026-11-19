import argparse
import asyncio
from contextlib import asynccontextmanager, closing
from dataclasses import dataclass
from hashlib import sha256
from hmac import compare_digest
from ipaddress import ip_address
import json
import os
from pathlib import Path
import re
import sqlite3
from threading import Event, Thread
from time import time
from uuid import uuid4

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from fastapi import Depends, FastAPI, HTTPException, Request

from worker.ocr_worker import MAX_REQUEST, OcrWorker, checked_uuid


@dataclass(frozen=True)
class ServiceConfig:
    token_hashes: dict[str, str]
    database: Path
    ttl_seconds: int = 3600
    max_pending_per_owner: int = 8
    max_jobs_per_owner: int = 64
    max_jobs_total: int = 256
    max_body_bytes: int = MAX_REQUEST
    workers: int = 2
    lease_seconds: int = 900
    max_attempts: int = 3
    allow_local_http: bool = False

    def __post_init__(self):
        if not self.token_hashes or any(
            not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", owner)
            or not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
            for owner, value in self.token_hashes.items()
        ):
            raise ValueError("Configure caller IDs and SHA-256 bearer hashes")
        if len(set(self.token_hashes.values())) != len(self.token_hashes):
            raise ValueError("Each caller needs a distinct bearer token")
        positive = (self.ttl_seconds, self.max_pending_per_owner, self.max_jobs_per_owner,
                    self.max_jobs_total, self.max_body_bytes, self.workers, self.lease_seconds, self.max_attempts)
        if any(type(value) is not int or value <= 0 for value in positive):
            raise ValueError("Service bounds must be positive integers")
        if self.max_body_bytes > MAX_REQUEST or self.workers > 16:
            raise ValueError("Service bounds exceed supported limits")


class JobService:
    def __init__(self, config, private_key, recognizer_factory, clock=time, model_info=None):
        self.config = config
        self.private_key = private_key
        self.recognizer_factory = recognizer_factory
        self.model_info = dict(model_info) if model_info is not None else None
        self.clock = clock
        self.config.database.parent.mkdir(parents=True, exist_ok=True)
        self.inspector = OcrWorker(private_key, config.database, None)
        with closing(self.connect()) as connection, connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS service_jobs ("
                "id TEXT PRIMARY KEY, owner TEXT NOT NULL, digest BLOB NOT NULL, input BLOB, result BLOB, "
                "state TEXT NOT NULL, expires REAL NOT NULL, lease_until REAL NOT NULL DEFAULT 0, "
                "lease_owner TEXT, attempts INTEGER NOT NULL DEFAULT 0, error TEXT)"
            )
            connection.execute("CREATE INDEX IF NOT EXISTS service_jobs_owner ON service_jobs(owner, state)")
            connection.execute("CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, digest BLOB NOT NULL, result BLOB NOT NULL)")
            connection.execute("CREATE TABLE IF NOT EXISTS job_claims (id TEXT PRIMARY KEY, digest BLOB NOT NULL, owner TEXT, lease_until REAL NOT NULL)")
        self.stop = Event()
        self.threads = []

    def connect(self):
        connection = sqlite3.connect(self.config.database, timeout=15, isolation_level="IMMEDIATE")
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA secure_delete=ON")
        return connection

    def expire(self, connection):
        now = self.clock()
        expired = connection.execute("SELECT id FROM service_jobs WHERE expires <= ?", (now,)).fetchall()
        for row in expired:
            connection.execute("DELETE FROM receipts WHERE id = ?", (row["id"],))
            connection.execute("DELETE FROM job_claims WHERE id = ?", (row["id"],))
        connection.execute("DELETE FROM service_jobs WHERE expires <= ?", (now,))

    def submit(self, owner, encoded):
        try:
            job, _, _, _ = self.inspector.validate_request(encoded)
        except (ValueError, InvalidTag, TypeError, KeyError, UnicodeError):
            raise HTTPException(400, "Invalid encrypted OCR request") from None
        request_digest = sha256(encoded).digest()
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            self.expire(connection)
            existing = connection.execute("SELECT * FROM service_jobs WHERE id = ?", (job,)).fetchone()
            if existing is not None:
                if existing["owner"] != owner:
                    raise HTTPException(404, "Job not found")
                if existing["digest"] != request_digest:
                    raise HTTPException(409, "Job identifier is bound to another request")
                if existing["state"] == "deleted":
                    raise HTTPException(410, "Job was deleted")
                return self.status(existing)
            counts = connection.execute(
                "SELECT COUNT(*) AS total, COALESCE(SUM(owner = ?), 0) AS owned, "
                "COALESCE(SUM(owner = ? AND state IN ('queued', 'running')), 0) AS pending FROM service_jobs",
                (owner, owner),
            ).fetchone()
            if (counts["total"] >= self.config.max_jobs_total or counts["owned"] >= self.config.max_jobs_per_owner
                    or counts["pending"] >= self.config.max_pending_per_owner):
                raise HTTPException(429, "Job quota reached", headers={"Retry-After": "30"})
            connection.execute(
                "INSERT INTO service_jobs (id, owner, digest, input, state, expires) VALUES (?, ?, ?, ?, 'queued', ?)",
                (job, owner, request_digest, encoded, self.clock() + self.config.ttl_seconds),
            )
            return self.status(connection.execute("SELECT * FROM service_jobs WHERE id = ?", (job,)).fetchone())

    def get(self, owner, job, delete=False):
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            self.expire(connection)
            row = connection.execute("SELECT * FROM service_jobs WHERE id = ? AND owner = ?", (job, owner)).fetchone()
            if row is None:
                raise HTTPException(404, "Job not found")
            if delete:
                connection.execute(
                    "UPDATE service_jobs SET input = NULL, result = NULL, state = 'deleted', lease_owner = NULL, lease_until = 0 WHERE id = ?",
                    (job,),
                )
                connection.execute("DELETE FROM receipts WHERE id = ?", (job,))
                connection.execute("DELETE FROM job_claims WHERE id = ?", (job,))
                return {"id": job, "state": "deleted"}
            if row["state"] == "deleted":
                raise HTTPException(410, "Job was deleted")
            result = self.status(row)
            if row["result"] is not None:
                result["result"] = json.loads(row["result"])
            return result

    @staticmethod
    def status(row):
        return {"id": row["id"], "state": row["state"], "expires_at": row["expires"], "attempts": row["attempts"],
                "protocol": 1, "task": "OCR_ENGLISH", "error": row["error"]}

    def run_one(self):
        owner = uuid4().hex
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            self.expire(connection)
            connection.execute(
                "UPDATE service_jobs SET state = 'failed', error = 'attempts_exhausted', lease_owner = NULL, lease_until = 0 "
                "WHERE state = 'running' AND lease_until <= ? AND attempts >= ?",
                (self.clock(), self.config.max_attempts),
            )
            row = connection.execute(
                "SELECT * FROM service_jobs WHERE attempts < ? AND "
                "(state = 'queued' OR (state = 'running' AND lease_until <= ?)) ORDER BY rowid LIMIT 1",
                (self.config.max_attempts, self.clock()),
            ).fetchone()
            if row is None:
                return False
            job = row["id"]
            connection.execute(
                "UPDATE service_jobs SET state = 'running', attempts = attempts + 1, lease_owner = ?, lease_until = ? WHERE id = ?",
                (owner, self.clock() + self.config.lease_seconds, job),
            )

        def current(connection, identifier):
            return connection.execute(
                "SELECT 1 FROM service_jobs WHERE id = ? AND state = 'running' AND lease_owner = ? AND expires > ? AND lease_until > ?",
                (identifier, owner, self.clock(), self.clock()),
            ).fetchone() is not None

        try:
            with self.recognizer_factory() as recognize:
                worker = OcrWorker(self.private_key, self.config.database, recognize, clock=self.clock,
                                   lease_seconds=self.config.lease_seconds, wait_seconds=0, accept_result=current,
                                   model_info=self.model_info)
                result = worker.process(row["input"])
            with closing(self.connect()) as connection, connection:
                connection.execute("BEGIN IMMEDIATE")
                if current(connection, job):
                    connection.execute(
                        "UPDATE service_jobs SET state = 'succeeded', result = ?, input = NULL, lease_owner = NULL, lease_until = 0 WHERE id = ?",
                        (result, job),
                    )
        except Exception:
            with closing(self.connect()) as connection, connection:
                connection.execute("BEGIN IMMEDIATE")
                connection.execute(
                    "UPDATE service_jobs SET state = CASE WHEN attempts < ? THEN 'queued' ELSE 'failed' END, "
                    "error = 'processing_failed', lease_owner = NULL, lease_until = 0 WHERE id = ? AND lease_owner = ? AND state = 'running'",
                    (self.config.max_attempts, job, owner),
                )
        return True

    def start(self):
        def process():
            while not self.stop.is_set():
                try:
                    active = self.run_one()
                except sqlite3.Error:
                    active = False
                if not active:
                    self.stop.wait(.2)
        def sweep():
            while not self.stop.wait(1):
                try:
                    with closing(self.connect()) as connection, connection:
                        self.expire(connection)
                except sqlite3.Error:
                    pass
        self.threads = [Thread(target=process, name=f"ocr-worker-{index}", daemon=True) for index in range(self.config.workers)]
        self.threads.append(Thread(target=sweep, name="ocr-expiry", daemon=True))
        for thread in self.threads:
            thread.start()

    def close(self):
        self.stop.set()
        for thread in self.threads:
            thread.join(timeout=2)


def create_app(config, private_key, recognizer_factory, start_workers=True, clock=time, model_info=None):
    service = JobService(config, private_key, recognizer_factory, clock, model_info)

    @asynccontextmanager
    async def lifespan(app):
        if start_workers:
            service.start()
        try:
            yield
        finally:
            service.close()

    app = FastAPI(title="Photo library OCR worker", version="1", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.jobs = service
    upload_slots = asyncio.Semaphore(max(2, config.workers * 2))

    async def caller(request: Request):
        local = False
        if request.client is not None:
            try:
                local = ip_address(request.client.host).is_loopback
            except ValueError:
                pass
        if request.url.scheme != "https" and not (config.allow_local_http and local):
            raise HTTPException(400, "HTTPS is required")
        authorization = request.headers.get("authorization", "")
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not 32 <= len(token) <= 512:
            raise HTTPException(401, "Bearer authorization required", headers={"WWW-Authenticate": "Bearer"})
        candidate = sha256(token.encode()).hexdigest()
        owner = next((identity for identity, expected in config.token_hashes.items() if compare_digest(candidate, expected)), None)
        if owner is None:
            raise HTTPException(401, "Invalid bearer token", headers={"WWW-Authenticate": "Bearer"})
        return owner

    @app.get("/v1/worker-key")
    def worker_key(owner: str = Depends(caller)):
        from base64 import b64encode
        return {"v": 1, "worker": service.inspector.worker_id,
                "public_key": b64encode(private_key.public_key().public_bytes_raw()).decode(),
                "task": "OCR_ENGLISH", "policy": "photo-export-v1"}

    @app.post("/v1/jobs", status_code=202)
    async def submit(request: Request, owner: str = Depends(caller)):
        try:
            await asyncio.wait_for(upload_slots.acquire(), timeout=1)
        except TimeoutError:
            raise HTTPException(429, "Upload capacity reached", headers={"Retry-After": "5"}) from None
        try:
            encoded = bytearray()
            try:
                async with asyncio.timeout(30):
                    async for chunk in request.stream():
                        if len(encoded) + len(chunk) > config.max_body_bytes:
                            raise HTTPException(413, "Request exceeds the configured limit")
                        encoded.extend(chunk)
            except TimeoutError:
                raise HTTPException(408, "Upload timed out") from None
            from starlette.concurrency import run_in_threadpool
            return await run_in_threadpool(service.submit, owner, bytes(encoded))
        finally:
            upload_slots.release()

    def valid_job(job):
        try:
            return checked_uuid(job)
        except (ValueError, TypeError):
            raise HTTPException(404, "Job not found") from None

    @app.get("/v1/jobs/{job}")
    def get(job: str, owner: str = Depends(caller)):
        return service.get(owner, valid_job(job))

    @app.delete("/v1/jobs/{job}")
    def delete(job: str, owner: str = Depends(caller)):
        return service.get(owner, valid_job(job), delete=True)

    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--tessdata", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--allow-local-http", action="store_true")
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--ttl-seconds", type=int, default=3600)
    parser.add_argument("--max-pending-per-owner", type=int, default=8)
    parser.add_argument("--max-jobs-per-owner", type=int, default=64)
    parser.add_argument("--max-jobs-total", type=int, default=256)
    args = parser.parse_args()
    if args.allow_local_http:
        if not ip_address(args.host).is_loopback:
            parser.error("Local HTTP must bind to a loopback IP address")
    elif args.tls_cert is None or args.tls_key is None:
        parser.error("Provide TLS certificate/key or explicitly enable local HTTP")
    tokens = json.loads(os.environ.get("PHOTO_LIBRARY_CALLER_HASHES", "{}"))
    config = ServiceConfig(tokens, args.database, allow_local_http=args.allow_local_http,
                           workers=args.workers, ttl_seconds=args.ttl_seconds,
                           max_pending_per_owner=args.max_pending_per_owner,
                           max_jobs_per_owner=args.max_jobs_per_owner, max_jobs_total=args.max_jobs_total)
    from contextlib import contextmanager
    from tesserocr import PSM, PyTessBaseAPI, tesseract_version
    from hashlib import file_digest
    with (args.tessdata / "eng.traineddata").open("rb") as model_file:
        weights = file_digest(model_file, "sha256").hexdigest()
    model_info = {"engine": "Tesseract", "version": tesseract_version().splitlines()[0],
                  "weights_sha256": weights, "preprocessing": "verified-png-rgb:PSM_AUTO:eng:v1"}

    @contextmanager
    def recognizer():
        with PyTessBaseAPI(path=str(args.tessdata.resolve()), lang="eng", psm=PSM.AUTO) as engine:
            def recognize(image):
                engine.SetImage(image)
                return engine.GetUTF8Text().strip()
            yield recognize

    import uvicorn
    app = create_app(config, X25519PrivateKey.from_private_bytes(args.private_key.read_bytes()), recognizer, model_info=model_info)
    uvicorn.run(app, host=args.host, port=args.port, ssl_certfile=str(args.tls_cert) if args.tls_cert else None,
                ssl_keyfile=str(args.tls_key) if args.tls_key else None, proxy_headers=False, access_log=False)


if __name__ == "__main__":
    main()
