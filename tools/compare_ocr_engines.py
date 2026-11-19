import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
import urllib.request
import uuid
import zipfile
from pathlib import Path

BUILD = Path(__file__).resolve().parents[1] / "app" / "build"
EVALUATION = BUILD / "public-evaluation"
MODEL = EVALUATION / "tessdata" / "eng.traineddata"
MODEL_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/65727574dfcd264acbb0c3e07860e4e9e9b22185/eng.traineddata"
MODEL_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
MANIFEST_SHA256 = "6bca507c3eda70a988633a6c17ceb14abf61ff5eee0f083afeab02807adf9909"
APP = "org.photolibrary.app.feasibility"
RUNNER = f"{APP}.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "org.photolibrary.app.feasibility.PublicDatasetOcrTest"
METHODS = {"mlkit": "scoreHierTextRecognition", "tesseract": "scoreHierTextWithTesseract"}


def verify_bytes(data: bytes, expected: str, label: str) -> None:
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Unexpected SHA-256 for {label}")


def prepare() -> None:
    if not MODEL.is_file():
        MODEL.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(MODEL_URL, timeout=30) as response:
            data = response.read()
        verify_bytes(data, MODEL_SHA256, MODEL.name)
        MODEL.write_bytes(data)
    verify_bytes(MODEL.read_bytes(), MODEL_SHA256, MODEL.name)
    print(f"Verified English tessdata_fast model: {MODEL.stat().st_size:,} bytes")


def verify_test_assets(test_apk: Path) -> None:
    with zipfile.ZipFile(test_apk) as archive:
        manifest = archive.read("assets/hiertext/manifest.json")
        verify_bytes(manifest, MANIFEST_SHA256, "frozen HierText manifest")
        lines = json.loads(manifest)["lines"]
        if len(lines) != 30 or len({line["image_id"] for line in lines}) != 12:
            raise ValueError("Unexpected sample size")
        for line in lines:
            verify_bytes(archive.read(f"assets/hiertext/{line['file']}"), line["sha256"], line["file"])
        verify_bytes(archive.read("assets/tessdata/eng.traineddata"), MODEL_SHA256, "bundled Tesseract model")


def summarize(runs: list[dict]) -> dict:
    first = runs[0]
    accuracy = ("exact", "blanks", "edits", "characters", "line_edits")
    if any(run[name] != first[name] for run in runs for name in accuracy):
        raise ValueError(f"Recognition scores varied between runs for {first['engine']}")
    warm = sorted(timing for run in runs for timing in run["recognition_ms"][1:])
    return {
        "exact": first["exact"], "blanks": first["blanks"],
        "character_edits": first["edits"], "reference_characters": first["characters"],
        "cer": first["edits"] / first["characters"],
        "initialization_median_ms": statistics.median(run["initialization_ms"] for run in runs),
        "first_recognition_median_ms": statistics.median(run["recognition_ms"][0] for run in runs),
        "startup_plus_first_median_ms": statistics.median(
            run["initialization_ms"] + run["recognition_ms"][0] for run in runs
        ),
        "warm_p50_ms": statistics.median(warm),
        "warm_p95_ms": warm[math.ceil(0.95 * len(warm)) - 1],
        "total_30_median_ms": statistics.median(
            run["initialization_ms"] + sum(run["recognition_ms"]) for run in runs
        ),
    }


def compare(adb: str, serial: str) -> None:
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("This comparison requires an explicit emulator serial")

    def call(*arguments: str) -> str:
        return subprocess.check_output(
            [adb, "-s", serial, *arguments], text=True, encoding="utf-8",
            errors="replace", stderr=subprocess.STDOUT, timeout=90,
        ).strip()

    if call("shell", "settings", "get", "global", "airplane_mode_on") != "1":
        raise ValueError("Enable airplane mode on the test emulator")
    if "Wifi is disabled" not in call("shell", "cmd", "wifi", "status"):
        raise ValueError("Turn off Wi-Fi on the test emulator")
    test_apk = BUILD / "outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"
    verify_test_assets(test_apk)
    for apk in [BUILD / "outputs/apk/feasibility/app-feasibility.apk", test_apk]:
        print(call("install", "-r", str(apk)), flush=True)

    runs = {engine: [] for engine in METHODS}
    for engine in ("mlkit", "tesseract", "tesseract", "mlkit", "mlkit", "tesseract"):
        run_id = uuid.uuid4().hex
        call("shell", "am", "force-stop", APP)
        output = call(
            "shell", "am", "instrument", "-w", "-e", "class", f"{TEST}#{METHODS[engine]}",
            "-e", "ocrRunId", run_id, RUNNER,
        )
        if "OK (1 test)" not in output:
            raise RuntimeError(output)
        logs = call("logcat", "-d", "-s", "PhotoLibraryData:I", "*:*")
        entries = [
            json.loads(line.split("OCR comparison ", 1)[1])
            for line in logs.splitlines() if "OCR comparison " in line and run_id in line
        ]
        if len(entries) != 1:
            raise ValueError(f"No unique result for {engine}")
        row = entries[0]
        if row["engine"] != engine or row["lines"] != 30 or row["images"] != 12 or row["characters"] != 340:
            raise ValueError(f"Unexpected scored input for {engine}")
        runs[engine].append(row)
        print(f"{engine} run {len(runs[engine])}: {row['exact']}/30 exact, {row['edits']} character edits", flush=True)

    summary = {engine: summarize(values) for engine, values in runs.items()}
    paired = list(zip(runs["mlkit"][0]["line_edits"], runs["tesseract"][0]["line_edits"]))
    report = {
        "device": serial,
        "android": call("shell", "getprop", "ro.build.version.release"),
        "abi": call("shell", "getprop", "ro.product.cpu.abi"),
        "manifest_sha256": MANIFEST_SHA256,
        "tessdata_fast_sha256": MODEL_SHA256,
        "mlkit_version": "16.0.1",
        "tesseract4android_version": "4.9.0",
        "tesseract_page_segmentation": "single_line",
        "summary": summary,
        "paired_line_errors": {
            "mlkit_lower": sum(mlkit < tess for mlkit, tess in paired),
            "tesseract_lower": sum(tess < mlkit for mlkit, tess in paired),
            "tied": sum(mlkit == tess for mlkit, tess in paired),
        },
        "runs": runs,
    }
    destination = BUILD / "datasets" / "ocr-engine-comparison.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print(f"Report: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "run"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
    else:
        compare(args.adb, args.serial)


if __name__ == "__main__":
    main()
