import argparse
import hashlib
import json
import re
import subprocess
import uuid
from pathlib import Path

from report_results import build_metrics, summarize

ROOT = Path(__file__).resolve().parents[2]
APP = "org.photolibrary.app.feasibility"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--corpus", type=Path, default=ROOT / ".benchmark-data/gallery-v1")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/large-gallery-benchmark")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.serial != "emulator-5580":
        raise ValueError("Only the authorized disposable emulator is permitted")
    manifest_bytes = (args.corpus / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    if not 1000 <= len(manifest["images"]) <= 5000:
        raise ValueError("Need a completed 1k–5k real-image manifest")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    state = output / "run.json"
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    if args.resume:
        previous = json.loads(state.read_text(encoding="utf-8"))
        if previous["manifest_sha256"] != manifest_sha:
            raise ValueError("Cannot resume a different manifest")
        fixture = previous["fixture"]
    else:
        fixture = "large-gallery-" + uuid.uuid4().hex
    if not re.fullmatch(r"large-gallery-[a-f0-9]{32}", fixture):
        raise ValueError("Invalid fixture token")
    remote = "/data/local/tmp/" + fixture
    private = "files/" + fixture
    state.write_text(json.dumps({"fixture": fixture, "manifest_sha256": manifest_sha, "complete": False}), encoding="utf-8")
    def adb(*parts, check=True):
        completed = subprocess.run([str(args.adb), "-s", args.serial, *parts], capture_output=True,
            text=True, encoding="utf-8", errors="replace")
        if check and completed.returncode:
            raise RuntimeError("ADB operation failed: " + completed.stderr[-600:])
        return completed
    corpus_bytes = sum(row["bytes"] for row in manifest["images"])
    space = adb("shell", "df", "-k", "/data").stdout.strip().splitlines()[-1].split()
    if int(space[3]) * 1024 < (0 if args.resume else 3 * corpus_bytes) + 1024**3:
        raise RuntimeError("Emulator needs room for remote staging, private copy, MediaStore and 1 GiB reserve")
    success = False
    try:
        if not args.resume:
            adb("shell", "mkdir", "-p", remote)
            adb("push", str(args.corpus / "images"), remote + "/images")
            adb("push", str(args.corpus / "manifest.json"), remote + "/manifest.json")
            adb("shell", "run-as", APP, "cp", "-R", remote, private)
        print(f"Staged {len(manifest['images'])} real attributed photos", flush=True)
        with (output / "android.txt").open("w", encoding="utf-8") as transcript_file:
            completed = subprocess.Popen([str(args.adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "largeGallery", "true", "-e", "largeGalleryFixture", fixture, "-e", "class",
                "org.photolibrary.app.feasibility.LargeGalleryRetrievalTest",
                APP + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=transcript_file, stderr=subprocess.STDOUT)
            if completed.wait() != 0:
                raise RuntimeError("Instrumentation transport failed; streaming transcript retained")
        transcript = (output / "android.txt").read_text(encoding="utf-8")
        if "OK (1 test)" not in transcript or re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2|4)\b", transcript):
            raise RuntimeError("Large gallery benchmark did not pass")
        report = json.loads(adb("exec-out", "run-as", APP, "cat", private + "-results.json").stdout)
        if report["manifest_sha256"] != hashlib.sha256(manifest_bytes).hexdigest() or not report.get("complete"):
            raise RuntimeError("Device result does not match the frozen complete manifest")
        (output / "emulator.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        metrics = build_metrics(manifest, report, manifest_sha)
        (output / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        (output / "summary.json").write_text(json.dumps(summarize(report, metrics), indent=2), encoding="utf-8")
        state.write_text(json.dumps({"fixture": fixture, "manifest_sha256": manifest_sha, "complete": True}), encoding="utf-8")
        success = True
        print(f"Completed {len(metrics['metrics'])} query rankings over {len(manifest['images'])} real photos; judgment scope follows the manifest: {output}", flush=True)
    finally:
        if success and re.fullmatch(r"large-gallery-[a-f0-9]{32}", fixture):
            adb("shell", "run-as", APP, "rm", "-rf", private, check=False)
            adb("shell", "run-as", APP, "rm", "-f", private + "-results.json", check=False)
            adb("shell", "rm", "-rf", remote, check=False)


if __name__ == "__main__":
    main()
