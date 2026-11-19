import argparse
from base64 import b64decode, b64encode
from binascii import crc32
from contextlib import closing
from hashlib import sha256
from io import BytesIO
from math import isfinite
import json
import os
from pathlib import Path
import sqlite3
from struct import unpack_from
from time import monotonic, sleep, time
from uuid import UUID, uuid4

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.hpke import AEAD, KDF, KEM, Suite
from PIL import Image


HPKE = Suite(KEM.X25519, KDF.HKDF_SHA256, AEAD.AES_256_GCM)
MAX_PNG = 12 * 1024 * 1024
MAX_REQUEST = 24 * 1024 * 1024
RESULT_FIELDS = ("mode", "task", "sha256", "policy", "source")
MODES = ("CLOUD_FULL", "CLOUD_REDACTED")
PNG_CHUNKS = {b"IHDR", b"IDAT", b"IEND"}


def parse_json(data):
    def unique(pairs):
        output = dict(pairs)
        if len(output) != len(pairs):
            raise ValueError("Duplicate JSON property")
        return output

    value = json.loads(data, object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object")
    return value


def wire_info(direction, worker, job):
    return f"photo-library:v1:ocr:{direction}:{worker}:{job}".encode()


def checked_uuid(value):
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("Invalid protocol identifier")
    return value


def verify_png(data):
    if not 45 <= len(data) <= MAX_PNG or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Unsupported image")
    at = 8
    header = False
    pixels = False
    while at + 12 <= len(data):
        size, kind = unpack_from(">I4s", data, at)
        end = at + 8 + size
        if end + 4 > len(data) or kind not in PNG_CHUNKS or (not header and kind != b"IHDR"):
            break
        if crc32(data[at + 4:end]) != unpack_from(">I", data, end)[0]:
            break
        if kind == b"IHDR":
            if header or size != 13:
                break
            width, height = unpack_from(">II", data, at + 8)
            if not (1 <= width <= 1600 and 1 <= height <= 1600):
                break
            header = True
        elif kind == b"IDAT":
            pixels = True
        elif kind == b"IEND" and pixels and size == 0 and end + 4 == len(data):
            return
        at = end + 4
    raise ValueError("Invalid or unsupported PNG export")


class JobInProgress(RuntimeError):
    pass


class JobLeaseLost(RuntimeError):
    pass


class OcrWorker:
    def __init__(self, private_key, ledger, recognize, lease_seconds=900, wait_seconds=15, clock=time, accept_result=None, model_info=None):
        if not isfinite(lease_seconds) or not isfinite(wait_seconds) or lease_seconds <= 0 or wait_seconds < 0:
            raise ValueError("Invalid job lease configuration")
        self.private_key = private_key
        self.worker_id = sha256(private_key.public_key().public_bytes_raw()).hexdigest()
        self.ledger = ledger
        self.recognize = recognize
        self.lease_seconds = lease_seconds
        self.wait_seconds = wait_seconds
        self.clock = clock
        self.accept_result = accept_result
        if model_info is not None and (
            set(model_info) != {"engine", "version", "weights_sha256", "preprocessing"}
            or any(not isinstance(value, str) or not 1 <= len(value) <= 256 for value in model_info.values())
            or len(model_info["weights_sha256"]) != 64
            or any(char not in "0123456789abcdef" for char in model_info["weights_sha256"])
        ):
            raise ValueError("Invalid model provenance")
        self.model_info = dict(model_info) if model_info is not None else None

    def validate_request(self, encoded):
        if not 1 <= len(encoded) <= MAX_REQUEST:
            raise ValueError("Request size exceeds the limit")
        packet = parse_json(encoded)
        if set(packet) != {"v", "worker", "job", "box"} or type(packet["v"]) is not int or packet["v"] != 1 or packet["worker"] != self.worker_id:
            raise ValueError("Unsupported worker envelope")
        job = checked_uuid(packet["job"])
        clear = parse_json(HPKE.decrypt(b64decode(packet["box"], validate=True), self.private_key,
                                        wire_info("request", self.worker_id, job)))
        if set(clear) != {*RESULT_FIELDS, "png", "return_key"} or clear["mode"] not in MODES:
            raise ValueError("Invalid request fields")
        if clear["task"] != "OCR_ENGLISH" or clear["policy"] != "photo-export-v1":
            raise ValueError("Unsupported task or policy")
        checked_uuid(clear["source"])
        image = b64decode(clear["png"], validate=True)
        key = b64decode(clear["return_key"], validate=True)
        if len(key) != 32 or sha256(image).hexdigest() != clear["sha256"]:
            raise ValueError("Request binding failed")
        verify_png(image)
        return job, clear, image, key

    def process(self, encoded):
        job, clear, image, key = self.validate_request(encoded)
        request_digest = sha256(encoded).digest()
        owner = uuid4().hex
        deadline = monotonic() + self.wait_seconds
        while True:
            acquired, previous = self._claim(job, request_digest, owner)
            if previous is not None:
                return previous
            if acquired:
                break
            if monotonic() >= deadline:
                raise JobInProgress("Job is already being processed")
            sleep(min(.05, max(0, deadline - monotonic())))
        try:
            with Image.open(BytesIO(image), formats=("PNG",)) as opened:
                with opened.convert("RGB") as decoded:
                    text = self.recognize(decoded)[:16_384]
            result = {name: clear[name] for name in RESULT_FIELDS}
            result["text"] = text
            if self.model_info is not None:
                result["model"] = self.model_info
            nonce = os.urandom(12)
            sealed = nonce + AESGCM(key).encrypt(nonce, json.dumps(result, ensure_ascii=False).encode(),
                                                wire_info("result", self.worker_id, job))
            output = json.dumps({"v": 1, "job": job, "worker": self.worker_id, "box": b64encode(sealed).decode()}).encode()
            self._complete(job, request_digest, owner, output)
            return output
        except BaseException:
            self._release(job, owner)
            raise

    def _connect(self):
        connection = sqlite3.connect(self.ledger, isolation_level="IMMEDIATE", timeout=15)
        try:
            connection.execute("CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, digest BLOB NOT NULL, result BLOB NOT NULL)")
            connection.execute(
                "CREATE TABLE IF NOT EXISTS job_claims "
                "(id TEXT PRIMARY KEY, digest BLOB NOT NULL, owner TEXT, lease_until REAL NOT NULL)"
            )
        except BaseException:
            connection.close()
            raise
        return connection

    def _claim(self, job, digest, owner):
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            previous = connection.execute("SELECT digest, result FROM receipts WHERE id = ?", (job,)).fetchone()
            if previous is not None:
                if previous[0] != digest:
                    raise ValueError("Job identifier has a different request")
                return False, previous[1]
            claim = connection.execute("SELECT digest, lease_until FROM job_claims WHERE id = ?", (job,)).fetchone()
            now = self.clock()
            if claim is None:
                connection.execute("INSERT INTO job_claims VALUES (?, ?, ?, ?)", (job, digest, owner, now + self.lease_seconds))
                return True, None
            if claim[0] != digest:
                raise ValueError("Job identifier has a different request")
            if claim[1] > now:
                return False, None
            connection.execute("UPDATE job_claims SET owner = ?, lease_until = ? WHERE id = ?",
                               (owner, now + self.lease_seconds, job))
            return True, None

    def _complete(self, job, digest, owner, output):
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            claim = connection.execute("SELECT digest, owner, lease_until FROM job_claims WHERE id = ?", (job,)).fetchone()
            if claim is None or claim[0] != digest or claim[1] != owner or claim[2] <= self.clock():
                raise JobLeaseLost("Job lease expired or was replaced")
            if self.accept_result is not None and not self.accept_result(connection, job):
                raise JobLeaseLost("Job approval expired or was revoked")
            connection.execute("INSERT INTO receipts VALUES (?, ?, ?)", (job, digest, output))
            connection.execute("DELETE FROM job_claims WHERE id = ? AND owner = ?", (job, owner))

    def _release(self, job, owner):
        with closing(self._connect()) as connection, connection:
            connection.execute("UPDATE job_claims SET owner = NULL, lease_until = 0 WHERE id = ? AND owner = ?", (job, owner))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-key", type=Path, required=True)
    actions = parser.add_subparsers(dest="action", required=True)
    key = actions.add_parser("generate-key")
    key.add_argument("--public-key", type=Path, required=True)
    work = actions.add_parser("process")
    work.add_argument("--tessdata", type=Path, required=True)
    work.add_argument("--ledger", type=Path, required=True)
    work.add_argument("--request", type=Path, required=True)
    work.add_argument("--result", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "generate-key":
        private = X25519PrivateKey.generate()
        with args.private_key.open("xb") as target:
            target.write(private.private_bytes_raw())
        with args.public_key.open("xb") as target:
            target.write(private.public_key().public_bytes_raw())
        return

    from tesserocr import PyTessBaseAPI, PSM

    if args.request.stat().st_size > MAX_REQUEST:
        raise SystemExit("Request size exceeds the limit")
    private = X25519PrivateKey.from_private_bytes(args.private_key.read_bytes())
    with PyTessBaseAPI(path=str(args.tessdata.resolve()), lang="eng", psm=PSM.AUTO) as engine:
        def recognize(image):
            engine.SetImage(image)
            return engine.GetUTF8Text().strip()

        worker = OcrWorker(private, args.ledger, recognize)
        result = worker.process(args.request.read_bytes())
    with args.result.open("xb") as target:
        target.write(result)


if __name__ == "__main__":
    main()
