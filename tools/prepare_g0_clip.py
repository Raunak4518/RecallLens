import argparse
import hashlib
import shutil
import subprocess
import time
import urllib.request
import uuid
from pathlib import Path

from PIL import Image, ImageDraw

REVISION = "6b7c523703ae26e39be4b03774966f8571c7f523"
ROOT = Path(__file__).resolve().parents[1] / "app" / "build" / "feasibility-models" / "clip-6b7c523"
ARTIFACTS = {
    "onnx/vision_model_quantized.onnx": "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299",
    "onnx/text_model_quantized.onnx": "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a",
    "tokenizer.json": "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd",
    "preprocessor_config.json": "6f638fb9401a6d6296feff533ee7efe657b787c49f954f82f5906b36ef2a1b1f",
}
PACKAGE = "org.photolibrary.app.feasibility"


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare_artifacts() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    for path, digest in ARTIFACTS.items():
        target = ROOT / Path(path).name
        if not target.is_file() or sha256(target) != digest:
            url = f"https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/{REVISION}/{path}"
            temporary = target.with_name(target.name + ".part")
            with urllib.request.urlopen(f"{url}?download=true&g0={time.time_ns()}", timeout=90) as source:
                with temporary.open("wb") as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
            if sha256(temporary) != digest:
                raise ValueError(f"Checksum mismatch for {path}")
            temporary.replace(target)
        print(f"Verified {target.name}", flush=True)

    red = Image.new("RGB", (224, 224), "white")
    ImageDraw.Draw(red).rectangle((40, 40, 183, 183), fill="red")
    red.save(ROOT / "red.png")
    blue = Image.new("RGB", (224, 224), "white")
    ImageDraw.Draw(blue).ellipse((40, 40, 183, 183), fill="blue")
    blue.save(ROOT / "blue.png")


def stage(adb: str, serial: str) -> None:
    device = [adb, "-s", serial]
    location = "files/g0-clip"
    subprocess.run(
        [*device, "shell", "run-as", PACKAGE, "mkdir", "-p", location],
        check=True,
        timeout=30,
    )
    for name in ("vision_model_quantized.onnx", "text_model_quantized.onnx", "red.png", "blue.png"):
        source = ROOT / name
        destination = f"{location}/{name}"
        temporary = f"/data/local/tmp/photo-library-g0-{uuid.uuid4().hex}-{name}"
        try:
            subprocess.run([*device, "push", str(source), temporary], check=True, timeout=120)
            subprocess.run(
                [*device, "shell", "run-as", PACKAGE, "cp", temporary, destination],
                check=True,
                timeout=120,
            )
        finally:
            subprocess.run([*device, "shell", "rm", "-f", temporary], check=False, timeout=30)
        checksum = subprocess.check_output(
            [*device, "shell", "run-as", PACKAGE, "sha256sum", destination],
            text=True,
            timeout=30,
        )
        if checksum.split()[0] != sha256(source):
            raise ValueError(f"Staged checksum mismatch for {name}")
        print(f"Staged {name}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    prepare_artifacts()
    if args.serial:
        stage(args.adb, args.serial)


if __name__ == "__main__":
    main()
