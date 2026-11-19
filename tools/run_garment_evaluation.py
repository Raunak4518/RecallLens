import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
APP = "org.photolibrary.app.feasibility"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--cohort", type=Path, default=ROOT / "evaluation/garment-cohort-v1.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--overlap-policy", choices=("WITHHOLD", "EXCLUDE_OTHER_PEOPLE"), default="WITHHOLD")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use a disposable emulator")
    if args.output is None:
        suffix = "-exclude-other-people" if args.overlap_policy == "EXCLUDE_OTHER_PEOPLE" else ""
        args.output = ROOT / "app/build" / (args.cohort.stem + suffix)
    previous = args.output / "emulator.json"
    if previous.is_file():
        prior = json.loads(previous.read_text(encoding="utf-8-sig"))
        if prior.get("overlap_policy", "WITHHOLD") != args.overlap_policy:
            raise ValueError("Choose a separate output directory for a different overlap policy")
    staged = args.output / "device"
    args.output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["python", "tools/prepare_garment_evaluation.py", "--cohort", str(args.cohort),
                    "--output", str(staged)], cwd=ROOT, check=True)
    manifest_bytes = (staged / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    names = [row["image_id"] + ".jpg" for row in manifest["images"]] + ["manifest.json"]
    if any(not re.fullmatch(r"(?:[a-f0-9]{16}\.jpg|manifest\.json)", name) for name in names):
        raise ValueError("Unexpected staged filename")
    fixture = "garment-evaluation-" + uuid.uuid4().hex
    remote = "/data/local/tmp/" + fixture
    private = "files/" + fixture

    def adb(*command, check=True):
        result = subprocess.run([str(args.adb), "-s", args.serial, *command], capture_output=True,
                                text=True, encoding="utf-8")
        if check and result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result

    for apk in ("app/build/outputs/apk/feasibility/app-feasibility.apk",
                "app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"):
        if "Success" not in adb("install", "-r", str(ROOT / apk)).stdout:
            raise RuntimeError("APK installation did not report success")
    try:
        adb("shell", "mkdir", remote)
        adb("shell", "run-as", APP, "mkdir", private)
        for name in names:
            adb("push", str(staged / name), remote + "/" + name)
            adb("shell", "run-as", APP, "cp", remote + "/" + name, private + "/" + name)
        print(f"Staged {len(names) - 1} attributed photos", flush=True)
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "garmentEvaluation", "true",
                     "-e", "garmentOverlapPolicy", args.overlap_policy,
                     "-e", "garmentFixture", fixture, "-e", "class",
                     "org.photolibrary.app.feasibility.GarmentModelEvaluationTest",
                     APP + ".test/androidx.test.runner.AndroidJUnitRunner")
        transcript = result.stdout + result.stderr
        (args.output / "instrumentation.txt").write_text(transcript, encoding="utf-8")
        if "OK (1 test)" not in transcript or "INSTRUMENTATION_STATUS_CODE: -4" in transcript or "INSTRUMENTATION_STATUS_CODE: -2" in transcript:
            raise RuntimeError("Evaluation did not execute successfully")
        report = json.loads(adb("exec-out", "run-as", APP, "cat", private + "-results.json").stdout)
        if report["overlap_policy"] != args.overlap_policy:
            raise ValueError("Device evaluated another overlap policy")
        if report["manifest_sha256"] != hashlib.sha256(manifest_bytes).hexdigest():
            raise ValueError("Device evaluated another manifest")
        expected = {row["image_id"]: row["sha256"] for row in manifest["images"]}
        actual = {row["image_id"]: row["source_sha256"] for row in report["images"]}
        if actual != expected or len(report["images"]) != len(expected):
            raise ValueError("Device did not evaluate each source exactly once")
        (args.output / "emulator.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Passed: {len(report['images'])} photos; report {args.output / 'emulator.json'}", flush=True)
    finally:
        for name in names:
            adb("shell", "run-as", APP, "rm", private + "/" + name, check=False)
            adb("shell", "rm", remote + "/" + name, check=False)
        adb("shell", "run-as", APP, "rm", private + "-results.json", check=False)
        adb("shell", "run-as", APP, "rmdir", private, check=False)
        adb("shell", "rmdir", remote, check=False)


if __name__ == "__main__":
    main()
