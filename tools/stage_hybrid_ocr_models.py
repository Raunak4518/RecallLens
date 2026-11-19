import argparse
import json
import shutil
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from benchmark_public_datasets import BUILD, digest, fetch


MODEL_ROOT = BUILD / "ocr-hybrid-models"
SDK_ROOT = BUILD / "ocr-android-sdk"
ANDROID_ASSETS = SDK_ROOT / "assets" / "paddle-mobile"
PADDLE_COMMIT = "dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf"
SDK_PATH = "deploy/ppocr-android/ppocr-sdk/src/main/java/"
MODELS = {
    ("tiny", "det"): ("2ba1506c0380b8f0b03dd142459aac66d4421f6c",
                      "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
                      "3ac018be6f97499a08faa3bbdeb33640968d9307f6736d152902747a9f259593"),
    ("tiny", "rec"): ("2612ab37152ae0a677521bae4e1e3d4fb4cf7c30",
                      "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
                      "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1"),
    ("small", "det"): ("28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
                       "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
                       "193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516"),
    ("small", "rec"): ("b8f84f0b80c529de40b4fbb3544b84fa7233a513",
                       "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
                       "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1"),
    ("medium", "det"): ("61323801669c338b7891481ec7bac61ce31b576a",
                        "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",
                        "7298d5ead546584af2504d03355f881ac7a7bc0eb1e282d3e159277c1d0af871"),
    ("medium", "rec"): ("50c7eacafc52fa7bcf4194e8cd08e46f8558504b",
                        "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
                        "991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129"),
}


def stage_model(item):
    tier, stage = item
    revision, weights_sha, config_sha = MODELS[item]
    name = f"PP-OCRv6_{tier}_{stage}"
    folder = MODEL_ROOT / name
    folder.mkdir(parents=True, exist_ok=True)
    for suffix, expected in (("onnx", weights_sha), ("yml", config_sha)):
        file = folder / f"inference.{suffix}"
        previous = MODEL_ROOT / tier / f"{stage}.{suffix}"
        if not file.exists() and previous.is_file():
            shutil.copyfile(previous, file)
        file = fetch(f"https://huggingface.co/PaddlePaddle/{name}_onnx/resolve/{revision}/{file.name}", file)
        if digest(file) != expected:
            raise ValueError(f"Digest mismatch for {file}")
    return tier, stage, folder


def stage_sdk(models):
    raw = f"https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/{PADDLE_COMMIT}/"
    fetch(raw + "LICENSE", SDK_ROOT / "LICENSE")
    url = f"https://api.github.com/repos/PaddlePaddle/PaddleOCR/git/trees/{PADDLE_COMMIT}?recursive=1"
    metadata = fetch(url, SDK_ROOT / "tree.json")
    entries = json.loads(metadata.read_text(encoding="utf-8"))
    paths = [row["path"] for row in entries["tree"] if row["path"].startswith(SDK_PATH) and row["path"].endswith(".kt")]
    if not paths:
        raise ValueError("Pinned SDK sources missing")
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda path: fetch(raw + path, SDK_ROOT / "java" / path.removeprefix(SDK_PATH)), paths))
    rec_preprocessor = SDK_ROOT / "java/com/paddle/ocr/preprocess/RecPreprocessor.kt"
    preprocess = rec_preprocessor.read_text(encoding="utf-8")
    begin = "        // Convert BGR to RGB and resize"
    patched = "    fun preprocessBatch(crops: List<Mat>): RecPreprocessResult {\n        val resizedMats = crops.map { crop ->"
    if begin in preprocess:
        start = preprocess.index(begin)
        end = preprocess.index("        // Convert to float and normalize", start)
        replacement = """        val resizedMats = crops.map { crop ->
            val aspectRatio = crop.cols().toDouble() / crop.rows().coerceAtLeast(1)
            val width = ceil(FIXED_HEIGHT * aspectRatio).toInt().coerceIn(1, MAX_IMG_W)
            Mat().also { Imgproc.resize(crop, it, Size(width.toDouble(), FIXED_HEIGHT.toDouble())) }
        }.toMutableList()

"""
        rec_preprocessor.write_text(preprocess[:start] + replacement + preprocess[end:], encoding="utf-8")
    elif patched not in preprocess:
        raise ValueError("Pinned SDK recognition preprocessor changed")
    for tier, stage, source in models:
        if tier == "medium":
            continue
        target = ANDROID_ASSETS / tier
        target.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / "inference.onnx", target / f"{stage}.onnx")
        if stage == "rec":
            shutil.copyfile(source / "inference.yml", target / "rec.yml")
    print(f"Staged {len(paths)} SDK sources and mobile assets in {SDK_ROOT}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mobile", action="store_true")
    parser.add_argument("--tiny", action="store_true")
    args = parser.parse_args()
    tiers = {"tiny"} if args.tiny else {"tiny", "small", "medium"} if args.mobile else {"medium"}
    desired = [entry for entry in MODELS if entry[0] in tiers]
    with ThreadPoolExecutor(max_workers=6) as executor:
        staged = list(executor.map(stage_model, desired))
    if args.mobile or args.tiny:
        stage_sdk(staged)
