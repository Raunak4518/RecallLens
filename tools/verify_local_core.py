import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
CLASSES = (
    "DurableIndexingTest", "StagedIndexingTest", "FolderSourceTest", "GalleryAvailabilityTest", "SourceObservationTest",
    "PhotoQueryPlanTest", "PhotoLocationsTest", "GarmentEvidenceTest",
    "GarmentEvidenceStoreTest", "CloudClientTest", "CloudTextStoreTest", "ClothingQueryTest",
    "LocalPhotoIndexerTest", "NamedSubjectsTest", "NamedSourceLifecycleTest", "PhotoContentFingerprintTest",
    "PersistentPhotoIndexTest", "IdentityMatchingTest", "IdentityCacheMetadataTest",
    "LocalVisualSearchTest", "PhotoExportTest",
    "PhotoQueryExecutionTest", "IdentityCandidateRaceTest", "VerifiedGarmentEvidenceTest", "SearchGuidanceTest",
    "ClothingSearchRaceTest", "GarmentSummaryRaceTest",
    "ModelNoticesTest", "RuntimeNoticesTest", "PartialContentQueryTest", "PartialContentWorkflowTest", "PhotoCacheRestorationTest",
    "SelfIdentityTest", "SelfIdentityWorkflowTest", "ComplexNearbyPlanTest", "PhotoContentSearchTest", "ComplexNearbyWorkflowTest", "PhotoLocationPublicationTest",
    "CaptureYearQueryTest", "CaptureEventsTest", "MarksheetEvidenceTest", "DocumentSearchWorkflowTest",
)


def snapshot():
    paths = set()
    for folder in ("app/src", "worker", "tools"):
        paths.update(path for path in (ROOT / folder).rglob("*")
                     if path.is_file() and path.suffix in (".kt", ".java", ".xml", ".py", ".txt", ".json", ".md")
                     and "__pycache__" not in path.parts)
    paths.update(ROOT / name for name in ("app/build.gradle.kts", "settings.gradle.kts", "gradle.properties"))
    paths.update(path for path in (ROOT / ".local-models").rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): file_sha256(path)
            for path in sorted(paths)}


def file_sha256(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2), encoding="utf-8")
    temporary.replace(path)


def parse_android(transcript):
    matches = re.findall(r"^OK \((\d+) tests?\)\s*$", transcript, re.MULTILINE)
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", transcript, re.MULTILINE)
    passed = codes.count("0")
    skipped = codes.count("-4")
    if (len(matches) != 1 or int(matches[0]) == 0 or any(code not in {"0", "1", "-4"} for code in codes)
            or passed + skipped != int(matches[0]) or "INSTRUMENTATION_CODE: -1" not in transcript
            or "INSTRUMENTATION_FAILED" in transcript or "FAILURES!!!" in transcript):
        raise RuntimeError("Android instrumentation did not pass")
    return {"reported": int(matches[0]), "executed_passed": passed, "skipped": skipped}


def reusable_class(receipt_path, transcript_path, signature, class_name):
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt.get("signature") != signature or receipt.get("class") != class_name:
            return None
        if receipt.get("transcript_sha256") != file_sha256(transcript_path):
            return None
        counts = parse_android(transcript_path.read_text(encoding="utf-8"))
        return receipt if receipt.get("counts") == counts else None
    except (OSError, ValueError, RuntimeError):
        return None


def selected_classes(value, include_workflows):
    classes = tuple(part.strip() for part in value.split(",")) if value else CLASSES
    if not value and include_workflows:
        classes = (*classes, "ClothingWorkflowTest")
    allowed = {*CLASSES, "ClothingWorkflowTest"}
    if not classes or len(classes) != len(set(classes)) or any(name not in allowed for name in classes):
        raise ValueError("Choose distinct known Android test class names")
    if "ClothingWorkflowTest" in classes and not include_workflows:
        raise ValueError("ClothingWorkflowTest requires --include-workflows")
    return classes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--include-workflows", action="store_true")
    parser.add_argument("--classes", help="Comma-separated known test class names; default is the complete core suite")
    parser.add_argument("--resume", action="store_true", help="Reuse verified class receipts for the identical sources and APKs")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Choose a disposable emulator")
    classes = selected_classes(args.classes, args.include_workflows)
    output = ROOT / "app/build/local-core-verification"
    output.mkdir(parents=True, exist_ok=True)
    class_output = output / "classes"
    class_output.mkdir(exist_ok=True)
    receipt_path = output / "receipt.json"
    receipt_path.unlink(missing_ok=True)
    (output / "android.txt").unlink(missing_ok=True)
    before = snapshot()
    started = time.time()

    def run(command, filename):
        path = output / filename
        with path.open("w", encoding="utf-8") as transcript_file:
            result = subprocess.run(command, cwd=ROOT, stdout=transcript_file, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
        transcript = path.read_text(encoding="utf-8")
        if result.returncode:
            raise RuntimeError(f"{filename} failed with exit {result.returncode}. See {output}")
        return transcript

    run(["python", "-m", "unittest", "worker.test_ocr_worker", "worker.test_service",
         "tools.test_verify_local_core", "-v"], "host.txt")
    print("Host tests passed", flush=True)
    run(["cmd", "/c", "gradlew.bat", "--offline", ":app:assembleFeasibility",
         ":app:assembleFeasibilityAndroidTest", ":app:lintDebug"], "build.txt")
    print("Build and lint passed", flush=True)
    if before != snapshot():
        raise RuntimeError("Sources or staged models changed during build")
    apks = ("app/build/outputs/apk/feasibility/app-feasibility.apk",
            "app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk")
    build_hashes = {name: file_sha256(ROOT / name) for name in apks}
    identity = {"source_and_model_sha256": before, "apk_sha256": build_hashes,
                "serial": args.serial, "include_workflows": args.include_workflows,
                "runner": "org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner"}
    signature = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
    command = [str(args.adb), "-s", args.serial]
    for apk in apks:
        result = run([*command, "install", "-r", apk], Path(apk).name + ".txt")
        if "Success" not in result:
            raise RuntimeError("APK install did not report success")
    print("Installed both APKs", flush=True)
    workflow = ["-e", "clothingWorkflow", "true"] if args.include_workflows else []
    receipts = []
    with (output / "android.txt").open("w", encoding="utf-8") as combined:
        for name in classes:
            class_receipt = class_output / (name + ".json")
            transcript_path = class_output / (name + ".txt")
            receipt = reusable_class(class_receipt, transcript_path, signature, name) if args.resume else None
            if receipt is None:
                class_receipt.unlink(missing_ok=True)
                class_started = time.time()
                transcript = run([*command, "shell", "am", "instrument", "-w", "-r", *workflow,
                                  "-e", "class", "org.photolibrary.app.feasibility." + name,
                                  identity["runner"]], "classes/" + name + ".txt")
                counts = parse_android(transcript)
                if before != snapshot() or build_hashes != {apk: file_sha256(ROOT / apk) for apk in apks}:
                    raise RuntimeError("Sources, models or APKs changed during verification")
                receipt = {"class": name, "signature": signature, "counts": counts,
                           "started_epoch": class_started, "finished_epoch": time.time(),
                           "transcript_sha256": file_sha256(transcript_path)}
                atomic_json(class_receipt, receipt)
                reused = False
            else:
                transcript = transcript_path.read_text(encoding="utf-8")
                reused = True
            combined.write("\nClass " + name + (" (reused verified receipt)" if reused else "") + "\n" + transcript)
            combined.flush()
            receipts.append({**receipt, "reused": reused})
            print(name + (" reused" if reused else " passed") + ": " + str(receipt["counts"]), flush=True)
    if before != snapshot() or build_hashes != {apk: file_sha256(ROOT / apk) for apk in apks}:
        raise RuntimeError("Sources, models or APKs changed during verification")
    receipt = {
        "started_epoch": started, "finished_epoch": time.time(), **identity,
        "signature": signature,
        "android_tests_reported": sum(item["counts"]["reported"] for item in receipts),
        "android_executed_passed": sum(item["counts"]["executed_passed"] for item in receipts),
        "android_skipped": sum(item["counts"]["skipped"] for item in receipts),
        "classes": classes, "class_receipts": receipts,
        "scope": "Per-class core functional regression. Live services, benchmarks and hardware qualification are separate.",
    }
    atomic_json(receipt_path, receipt)
    print("Core verification passed", flush=True)


if __name__ == "__main__":
    main()
