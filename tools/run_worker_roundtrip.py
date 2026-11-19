import argparse
from base64 import b64decode, b64encode
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from uuid import uuid4

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from tesserocr import PyTessBaseAPI, PSM


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from worker.ocr_worker import HPKE, MODES, OcrWorker, wire_info


PACKAGE = "org.photolibrary.app.feasibility"
TEST = "org.photolibrary.app.feasibility.PhotoExportTest#encryptedRoundTripWithHostWorker"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def roundtrip(adb, device, tessdata):
    if not device.startswith("emulator-"):
        raise SystemExit("Choose the test emulator serial.")
    command = [str(adb), "-s", device]
    output = ROOT / "app/build/worker-roundtrip" / uuid4().hex
    output.mkdir(parents=True)
    key = X25519PrivateKey.generate()
    public = b64encode(key.public_key().public_bytes_raw()).decode()
    for mode in MODES:
        subprocess.run([*command, "shell", "run-as", PACKAGE, "rm", "-f",
                        *[f"cache/{mode}.{ending}" for ending in ("request", "result", "incoming", "staging")]], check=True)
    evidence = []
    log = output / "instrumentation.txt"
    with log.open("wb") as stdout:
        process = subprocess.Popen([*command, "shell", "am", "instrument", "-w", "-r",
                                    "-e", "class", TEST, "-e", "workerPublicKey", public, RUNNER],
                                   stdout=stdout, stderr=subprocess.STDOUT)
        try:
            with PyTessBaseAPI(path=str(tessdata.resolve()), lang="eng", psm=PSM.AUTO) as engine:
                def recognize(image):
                    engine.SetImage(image)
                    return engine.GetUTF8Text().strip()

                worker = OcrWorker(key, output / "receipts.sqlite3", recognize)
                for mode in MODES:
                    deadline = time.monotonic() + 100
                    while True:
                        request = subprocess.run([*command, "exec-out", "run-as", PACKAGE, "cat",
                                                  f"cache/{mode}.request"], capture_output=True, timeout=15)
                        if request.returncode == 0 and request.stdout.startswith(b"{"):
                            break
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError(f"Android did not write {mode}. See {log}")
                        time.sleep(.35)
                    packet = json.loads(request.stdout)
                    changed = bytearray(b64decode(packet["box"]))
                    changed[-1] ^= 1
                    packet["box"] = b64encode(changed).decode()
                    try:
                        worker.process(json.dumps(packet).encode())
                    except InvalidTag:
                        pass
                    else:
                        raise RuntimeError("Tampered HPKE request was accepted")
                    response = worker.process(request.stdout)
                    reopened = OcrWorker(key, worker.ledger, recognize)
                    if reopened.process(request.stdout) != response:
                        raise RuntimeError("The worker changed its response to an identical request")
                    original = json.loads(request.stdout)
                    info = wire_info("request", worker.worker_id, original["job"])
                    clear = json.loads(HPKE.decrypt(b64decode(original["box"]), key, info))
                    clear["source"] = str(uuid4())
                    original["box"] = b64encode(HPKE.encrypt(json.dumps(clear).encode(), key.public_key(), info)).decode()
                    try:
                        reopened.process(json.dumps(original).encode())
                    except ValueError as error:
                        if str(error) != "Job identifier has a different request":
                            raise
                    else:
                        raise RuntimeError("A different valid request reused a job identifier")
                    subprocess.run([*command, "shell", "-T", "run-as", PACKAGE, "tee", f"cache/{mode}.incoming"],
                                   input=response, stdout=subprocess.DEVNULL, check=True, timeout=15)
                    subprocess.run([*command, "shell", "run-as", PACKAGE, "mv", f"cache/{mode}.incoming", f"cache/{mode}.result"],
                                   check=True, timeout=15)
                    evidence.append({"mode": mode, "encrypted_request_bytes": len(request.stdout),
                                     "encrypted_result_bytes": len(response), "tamper_rejected": True,
                                     "retry_identical": True, "conflicting_replay_rejected": True})
            process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.terminate()
                subprocess.run([*command, "shell", "am", "force-stop", PACKAGE], check=False, timeout=15)
    if process.returncode != 0 or "OK (1 test)" not in log.read_text(encoding="utf-8", errors="replace"):
        raise RuntimeError(f"Android round trip failed. See {log}")
    (output / "results.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(output / "results.json")
    print(json.dumps(evidence))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, default=Path(os.environ.get("ANDROID_HOME", ".")) / "platform-tools/adb.exe")
    parser.add_argument("--device", required=True)
    parser.add_argument("--tessdata", type=Path, default=ROOT / "app/build/public-evaluation/tessdata")
    options = parser.parse_args()
    roundtrip(options.adb, options.device, options.tessdata)
