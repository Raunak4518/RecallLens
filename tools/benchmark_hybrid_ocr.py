import argparse
import csv
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from base64 import b64encode
from collections import Counter
from contextlib import contextmanager
from hashlib import sha256
from pathlib import Path
from uuid import uuid4

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from benchmark_full_photo_ocr import latency, score_search
from benchmark_public_datasets import BUILD, digest, write_json
from compare_ocr_engines import APP, RUNNER
from prepare_hybrid_ocr_corpus import ASSETS


ROOT = BUILD.parents[1]
sys.path.insert(0, str(ROOT))
from worker.ocr_worker import OcrWorker


ANDROID_TEST = "org.photolibrary.app.feasibility.HybridPhotoOcrTest"
ANDROID_REPORT = "files/hybrid-ocr-android.json"
MODES = ("full", "redacted")


@contextmanager
def worker_engine(name, model_root):
    if name == "tesseract":
        from tesserocr import PSM, PyTessBaseAPI, tesseract_version
        tessdata = BUILD / "public-evaluation" / "tessdata"
        with PyTessBaseAPI(path=str(tessdata.resolve()), lang="eng", psm=PSM.AUTO) as engine:
            def run(image):
                engine.SetImage(image)
                return engine.GetUTF8Text().strip()

            yield run, {"name": name, "version": tesseract_version().splitlines()[0],
                        "trained_data_sha256": digest(tessdata / "eng.traineddata"), "page_segmentation": "AUTO"}
        return

    import numpy as np
    import onnxruntime
    from paddleocr import PaddleOCR, __version__ as pipeline_version

    names = {"det": f"PP-OCRv6_{name}_det", "rec": f"PP-OCRv6_{name}_rec"}
    paths = {stage: model_root.resolve() / model for stage, model in names.items()}
    for path in paths.values():
        if not (path / "inference.onnx").is_file():
            raise FileNotFoundError(f"Model missing at {path}")
    engine = PaddleOCR(
        text_detection_model_name=names["det"], text_detection_model_dir=str(paths["det"]),
        text_recognition_model_name=names["rec"], text_recognition_model_dir=str(paths["rec"]),
        text_det_limit_side_len=1600, text_det_limit_type="max", text_rec_score_thresh=0.0,
        use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
        device="cpu", engine="onnxruntime",
    )

    def run(image):
        pixels = np.ascontiguousarray(np.asarray(image)[:, :, ::-1])
        prediction = next(iter(engine.predict(pixels)))
        return "\n".join(str(line) for line in prediction["rec_texts"])

    try:
        yield run, {"name": f"PP-OCRv6 {name}", "pipeline_version": pipeline_version,
                    "runtime": onnxruntime.__version__,
                    "model_sha256": {key: digest(path / "inference.onnx") for key, path in paths.items()}}
    finally:
        engine.close()


def check_apks(apk, manifest):
    with zipfile.ZipFile(apk) as archive:
        if sha256(archive.read(f"assets/{ASSETS.name}/manifest.json")).hexdigest() != digest(ASSETS / "manifest.json"):
            raise ValueError("Rebuild the test APK with the frozen OCR corpus")
        for row in manifest["images"]:
            if sha256(archive.read(f"assets/{ASSETS.name}/{row['file']}")).hexdigest() != row["sha256"]:
                raise ValueError(f"Wrong fixture bytes for {row['id']}")


def process_corpus(adb, manifest, output, recognize):
    run_id = output.name
    key = X25519PrivateKey.generate()
    pub = b64encode(key.public_key().public_bytes_raw()).decode()
    worker_timings = []

    def measure(image):
        started = time.perf_counter()
        text = recognize(image)
        worker_timings.append((time.perf_counter() - started) * 1000)
        return text

    worker = OcrWorker(key, output / "worker-receipts.sqlite3", measure)
    records = []
    android_log = output / "android-instrumentation.txt"
    subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", ANDROID_REPORT], check=True)
    with android_log.open("wb") as log:
        process = subprocess.Popen([*adb, "shell", "am", "instrument", "-w", "-r",
                                    "-e", "class", ANDROID_TEST, "-e", "workerPublicKey", pub,
                                    "-e", "runId", run_id, RUNNER], stdout=log, stderr=subprocess.STDOUT)
        try:
            for ordinal, photo in enumerate(manifest["images"]):
                for mode in MODES:
                    prefix = f"cache/{run_id}-{ordinal}-{mode}"
                    request = await_request(adb, process, prefix, android_log)
                    start = time.perf_counter()
                    result = worker.process(request)
                    total = (time.perf_counter() - start) * 1000
                    subprocess.run([*adb, "shell", "-T", "run-as", APP, "tee", prefix + ".incoming"],
                                   input=result, stdout=subprocess.DEVNULL, timeout=15, check=True)
                    subprocess.run([*adb, "shell", "run-as", APP, "mv", prefix + ".incoming", prefix + ".result"],
                                   timeout=15, check=True)
                    records.append({"id": photo["id"], "mode": mode, "ocr_ms": worker_timings[-1],
                                    "worker_ms": total, "request_bytes": len(request), "result_bytes": len(result)})
                if (ordinal + 1) % 10 == 0 or ordinal + 1 == len(manifest["images"]):
                    print(f"Processed {ordinal + 1}/{len(manifest['images'])} paired photos", flush=True)
            process.wait(timeout=90)
        finally:
            if process.poll() is None:
                process.terminate()
                subprocess.run([*adb, "shell", "am", "force-stop", APP], timeout=15, check=False)
    if "OK (1 test)" not in android_log.read_text(encoding="utf-8", errors="replace") or process.returncode:
        raise RuntimeError(f"Android OCR comparison failed. See {android_log}")
    report = json.loads(subprocess.check_output([*adb, "exec-out", "run-as", APP, "cat", ANDROID_REPORT], timeout=30))
    subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", ANDROID_REPORT], check=True)
    return report, records


def await_request(adb, process, prefix, android_log):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline and process.poll() is None:
        result = subprocess.run([*adb, "exec-out", "run-as", APP, "cat", prefix + ".request"],
                                timeout=15, capture_output=True)
        if result.returncode == 0 and result.stdout.startswith(b"{"):
            return result.stdout
        time.sleep(.12)
    raise RuntimeError(f"Android did not deliver {prefix}. See {android_log}")


def collect_mobile(adb, output, tiers):
    mobile_report = "files/hybrid-ocr-paddle-mobile.json"
    test = "org.photolibrary.app.feasibility.PaddleMobileOcrTest"
    results = {}
    for tier in tiers:
        subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", mobile_report], check=True)
        log = subprocess.check_output([*adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", test,
                                       "-e", "modelTier", tier, RUNNER], stderr=subprocess.STDOUT, timeout=900)
        log_path = output / f"mobile-{tier}-instrumentation.txt"
        log_path.write_bytes(log)
        if b"OK (1 test)" not in log:
            raise RuntimeError(f"Paddle mobile comparison failed. See {log_path}")
        row = json.loads(subprocess.check_output([*adb, "exec-out", "run-as", APP, "cat", mobile_report], timeout=30))
        name = "local" if tier == "mlkit" else f"paddle_{tier}"
        results[name] = row
        write_json(output / "mobile-results.json", results)
        print(f"Finished {name} on the emulator", flush=True)
    subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", mobile_report], check=True)
    return results


def compare_query_results(manifest, variants):
    scored = {key: score_search(manifest["queries"], records)[1] for key, records in variants.items()}
    summary = {}
    by_query = []
    for ordinal, query in enumerate(manifest["queries"]):
        checks = {}
        for name, rows in scored.items():
            row = rows[ordinal]
            checks[name] = {"found": row["target_rank"] is not None,
                            "matches": row["matches"], "unlabeled_matches": row["unlabeled_matches"]}
        by_query.append({**query, "results": checks})

    for name in scored:
        public = [row for row in by_query if row["kind"] in ("word", "phrase")]
        protected = [row for row in by_query if row["kind"] == "protected"]
        absent = [row for row in by_query if row["kind"] == "absent"]
        public_hits = sum(row["results"][name]["found"] for row in public)
        groups = {}
        for group in sorted({row["group"] for row in public}):
            subset = [row for row in public if row["group"] == group]
            groups[group] = [sum(row["results"][name]["found"] for row in subset), len(subset)]
        by_kind = {kind: [
            sum(row["results"][name]["found"] for row in public if row["kind"] == kind),
            sum(row["kind"] == kind for row in public),
        ] for kind in ("word", "phrase")}
        hits = Counter()
        photo_counts = Counter(row["target"] for row in public)
        for row in public:
            if row["results"][name]["found"]:
                hits[row["target"]] += 1
        summary[name] = {"public": [public_hits, len(public)], "at_least_one_public": [len(hits), len(photo_counts)],
                         "both_public": [sum(hits[photo] == count for photo, count in photo_counts.items()), len(photo_counts)],
                         "protected_matches": [sum(row["results"][name]["found"] for row in protected), len(protected)],
                         "absent_with_matches": [sum(bool(row["results"][name]["matches"]) for row in absent), len(absent)],
                         "unlabeled_public_matches": sum(len(row["results"][name]["unlabeled_matches"]) for row in public),
                         "gained_vs_local": sum(row["results"][name]["found"] and not row["results"]["local"]["found"] for row in public),
                         "lost_vs_local": sum(row["results"]["local"]["found"] and not row["results"][name]["found"] for row in public),
                         "by_challenge": groups, "by_query_kind": by_kind}
    return summary, by_query


def save_query_sheet(path, queries, variants):
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(["query", "type", "challenge", "target_photo", *variants])
        for row in queries:
            statuses = []
            for name in variants:
                result = row["results"][name]
                statuses.append(("unexpected return" if result["matches"] else "empty")
                                if row["target"] is None else ("found" if result["found"] else "missed"))
            writer.writerow([row["text"], row["kind"], row["group"], row["target"], *statuses])


def write_report(output, manifest, android, mobile, engine, worker, device):
    if [record["id"] for record in android["images"]] != [photo["id"] for photo in manifest["images"]]:
        raise ValueError("Saved photo measurements differ from the manifest")
    variants = {name: android[name] for name in ("local", *MODES)}
    timings = {"local": latency([photo["local_ocr_ms"] for photo in android["images"]])}
    for name, row in (mobile or {}).items():
        if name == "local" and row["queries"] != variants[name]:
            raise RuntimeError("Isolated ML Kit and paired run returned different query results")
        variants[name] = row["queries"]
        timings[name] = latency([image["ocr_ms"] for image in row["images"]])
    summary, queries = compare_query_results(manifest, variants)
    baseline_hits = summary["local"]["public"][0]
    for name, row in summary.items():
        row["public_hits_if_local_is_retained"] = baseline_hits + row["gained_vs_local"]
    synthetic_measurements = [photo for photo in android["images"] if photo["id"].startswith("synthetic-")]
    result = {"manifest_sha256": digest(ASSETS / "manifest.json"), "worker": engine,
              "emulator": device, "photos": len(manifest["images"]), "queries": len(queries),
              "summary": summary, "phone_ocr_ms": timings,
              "phone_decode_ms": latency([photo["local_decode_ms"] for photo in android["images"]]),
              "phone_prepare_ms": {mode: latency([photo[f"{mode}_prepare_ms"] for photo in android["images"]]) for mode in MODES},
              "generated_photos_with_automatic_text_mask": [sum(photo["automatic_text_masks"] > 0 for photo in synthetic_measurements),
                                                            len(synthetic_measurements)],
              "worker_ocr_ms": {mode: latency([photo["ocr_ms"] for photo in worker if photo["mode"] == mode]) for mode in MODES} if worker else None,
              "worker_photos": worker, "per_query": queries}
    write_json(output / "results.json", result)
    save_query_sheet(output / "remembered-text.csv", queries, variants)
    return result


def benchmark(args):
    if not re.fullmatch(r"emulator-\d+", args.device):
        raise ValueError("Pass the serial of an Android emulator")
    adb = [str(args.adb), "-s", args.device]
    if subprocess.check_output([*adb, "shell", "settings", "get", "global", "airplane_mode_on"]).strip() != b"1":
        raise ValueError("Enable airplane mode on the test emulator")
    if b"Wifi is disabled" not in subprocess.check_output([*adb, "shell", "cmd", "wifi", "status"]):
        raise ValueError("Disable Wi-Fi on the test emulator")
    manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
    output = BUILD / "hybrid-ocr-benchmark" / uuid4().hex
    output.mkdir(parents=True)
    test_apk = BUILD / "outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"
    check_apks(test_apk, manifest)
    for apk in (BUILD / "outputs/apk/feasibility/app-feasibility.apk", test_apk):
        subprocess.run([*adb, "install", "-r", str(apk)], check=True, timeout=180)
    print(f"Writing evidence to {output}", flush=True)
    if args.mobile_only:
        mobile = collect_mobile(adb, output, args.mobile_models)
        write_json(output / "mobile-results.json", mobile)
        print(output / "mobile-results.json")
        return
    setup_start = time.perf_counter()
    with worker_engine(args.worker, args.model_root) as (recognize, engine):
        engine["setup_ms"] = (time.perf_counter() - setup_start) * 1000
        android, worker = process_corpus(adb, manifest, output, recognize)
    write_json(output / "android-results.json", android)
    write_json(output / "worker-results.json", {"engine": engine, "photos": worker})
    mobile = collect_mobile(adb, output, args.mobile_models) if args.include_mobile else None
    result = write_report(output, manifest, android, mobile, engine, worker, args.device)
    print(json.dumps({key: result[key] for key in ("summary", "phone_ocr_ms", "phone_prepare_ms", "worker_ocr_ms")}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, default=Path(os.environ.get("ANDROID_HOME", ".")) / "platform-tools/adb.exe")
    parser.add_argument("--device", required=True)
    parser.add_argument("--worker", default="medium", choices=("tesseract", "tiny", "small", "medium"))
    parser.add_argument("--model-root", type=Path, default=BUILD / "ocr-hybrid-models")
    parser.add_argument("--include-mobile", action="store_true")
    parser.add_argument("--mobile-only", action="store_true")
    parser.add_argument("--mobile-models", nargs="+", choices=("mlkit", "tiny", "small"), default=("mlkit", "tiny", "small"))
    benchmark(parser.parse_args())
