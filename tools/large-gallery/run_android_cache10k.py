import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
import uuid


APP = "org.photolibrary.app.feasibility"
CLASS = "org.photolibrary.app.feasibility.TenThousandCachedSearchTest"
SERIAL = "emulator-5580"


def digest(file):
    with file.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(adb_path, manifest_path, receipt_path, output):
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    manifest_sha = digest(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (receipt["version"], receipt["manifest_sha256"], receipt["count"], receipt["dimensions"], receipt["normalized"], receipt["dtype"]) != (
            1, manifest_sha, 10_000, 512, True, "float32 little-endian, row-major"):
        raise ValueError("Need the exact 10,000-image vector receipt and manifest")
    files = []
    for key, filename, bytes_expected in (("vectors", "scene-vectors-f32le.bin", 20_480_000), ("ids", "scene-vector-ids.txt", 170_000)):
        row = receipt[key]
        path = receipt_path.parent / filename
        if row["file"] != filename or row["bytes"] != bytes_expected or path.stat().st_size != bytes_expected or digest(path) != row["sha256"]:
            raise ValueError(f"Unexpected {key} data")
        files.append(path)
    ids = files[1].read_text(encoding="ascii").splitlines()
    if len(ids) != 10_000 or len(set(ids)) != 10_000 or ids != [row["id"] for row in manifest["images"]]:
        raise ValueError("Ordered vector IDs must match the frozen gallery")
    queries = [row["query"] for row in manifest["queries"]][:40]
    if len(queries) < 3 or len(set(queries)) != len(queries):
        raise ValueError("Need distinct frozen queries")

    def adb(*parts, timeout=60, check=True):
        result = subprocess.run([str(adb_path), "-s", SERIAL, *map(str, parts)], capture_output=True, timeout=timeout, check=check)
        return result.stdout.decode("utf-8", errors="replace")

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1" or adb("shell", "getprop", "ro.product.cpu.abi").strip() != "x86_64":
        raise RuntimeError("Only the test emulator is authorized")
    output.mkdir(parents=True, exist_ok=False)
    token = f"cache10k-{uuid.uuid4().hex}"
    remote = f"/data/local/tmp/{token}"
    private = f"files/{token}"
    payload = {"manifestSha256": manifest_sha, "vectorsSha256": receipt["vectors"]["sha256"],
               "idsSha256": receipt["ids"]["sha256"], "queries": queries}
    try:
        adb("shell", "mkdir", remote)
        for file in files:
            adb("push", file, f"{remote}/{file.name}", timeout=180)
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "input.json"
            config.write_text(json.dumps(payload), encoding="utf-8")
            adb("push", config, f"{remote}/input.json")
        adb("shell", "run-as", APP, "cp", "-R", remote, private)
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS, "-e", "cache10k", "true",
                     "-e", "cache10kFixture", token, APP + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=480)
        (output / "instrumentation.txt").write_text(result, encoding="utf-8")
        if "OK (1 test)" not in result or "INSTRUMENTATION_CODE: -1" not in result or re.search(r"INSTRUMENTATION_STATUS_CODE: -[24]\b", result):
            raise RuntimeError("Android cache benchmark did not pass")
        data = json.loads(adb("exec-out", "run-as", APP, "cat", private + "/result.json"))
        if not data["complete"] or data["photos"] != 10_000 or data["manifestSha256"] != manifest_sha or data["vectorsSha256"] != payload["vectorsSha256"]:
            raise ValueError("Android receipt does not match the input")
        if [row["query"] for row in data["queries"]] != queries:
            raise ValueError("Android report query mismatch")
        data["serial"] = SERIAL
        data["warmEncodeMsMedian"] = statistics.median(row["encodeMs"] for row in data["queries"][1:])
        data["warmRankMsMedian"] = statistics.median(row["rankMs"] for row in data["queries"][1:])
        (output / "results.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: value for key, value in data.items() if key != "queries"}), flush=True)
    finally:
        adb("shell", "run-as", APP, "rm", "-rf", private, check=False)
        adb("shell", "rm", "-rf", remote, check=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--vector-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    run(args.adb, args.manifest, args.vector_receipt, args.output)


if __name__ == "__main__":
    main()
