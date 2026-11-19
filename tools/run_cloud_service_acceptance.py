import argparse
from base64 import b64encode
from contextlib import contextmanager
from hashlib import sha256, file_digest
import json
from pathlib import Path
import secrets
import socket
import subprocess
import sys
from threading import Thread
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "app/build/worker-runtime"))

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from tesserocr import PyTessBaseAPI, PSM, tesseract_version
import uvicorn
from worker.service import ServiceConfig, create_app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use the permitted emulator")
    output = ROOT / "app/build/cloud-service-acceptance"
    output.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(40)
    key = X25519PrivateKey.generate()

    @contextmanager
    def recognizer():
        with PyTessBaseAPI(path=str(ROOT / "app/build/public-evaluation/tessdata"), lang="eng", psm=PSM.AUTO) as engine:
            def recognize(image):
                engine.SetImage(image)
                return engine.GetUTF8Text().strip()
            yield recognize

    config = ServiceConfig({"acceptance": sha256(token.encode()).hexdigest()},
                           output / f"jobs-{secrets.token_hex(8)}.sqlite3", allow_local_http=True)
    with (ROOT / "app/build/public-evaluation/tessdata/eng.traineddata").open("rb") as weights:
        model = {"engine": "Tesseract", "version": tesseract_version().splitlines()[0],
                 "weights_sha256": file_digest(weights, "sha256").hexdigest(),
                 "preprocessing": "verified-png-rgb:PSM_AUTO:eng:v1"}
    app = create_app(config, key, recognizer, model_info=model)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(16)
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="error", access_log=False))
        thread = Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 15
            while not server.started:
                if not thread.is_alive() or time.monotonic() > deadline:
                    raise RuntimeError("Acceptance server failed to start")
                time.sleep(.05)
            command = [str(args.adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                       "-e", "cloudServiceUrl", f"http://10.0.2.2:{port}",
                       "-e", "cloudServiceToken", token,
                       "-e", "cloudWorkerKey", b64encode(key.public_key().public_bytes_raw()).decode(),
                       "-e", "class", "org.photolibrary.app.feasibility.CloudClientTest#normalExportWorkflowUsesAuthenticatedHttpService",
                       "org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner"]
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=300)
            transcript = (result.stdout + result.stderr).replace(token, "[redacted]")
            (output / "instrumentation.txt").write_text(transcript, encoding="utf-8")
            if result.returncode or "OK (1 test)" not in transcript:
                raise RuntimeError(transcript)
            (output / "results.json").write_text(json.dumps({
                "modes": ["CLOUD_FULL", "CLOUD_REDACTED"],
                "passed": True, "engine": "Tesseract", "transport": "explicit emulator loopback HTTP",
            }, indent=2), encoding="utf-8")
            print("Both authenticated Android cloud workflows passed")
        finally:
            server.should_exit = True
            thread.join(timeout=15)


if __name__ == "__main__":
    main()
