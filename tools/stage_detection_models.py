import argparse
import ast
import hashlib
import os
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
YOLOX = "https://raw.githubusercontent.com/Megvii-BaseDetection/YOLOX/0.1.1rc0/"
YUNET = "opencv/opencv_zoo/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_detection_yunet/"
FILES = {
    "yolox_nano.onnx": (
        "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx",
        "c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d",
    ),
    "coco_classes.py": (
        YOLOX + "yolox/data/datasets/coco_classes.py",
        "b38193c481a73f1f674cedab9e551b15b39b1a7aaed3e09e16505362cc54ad51",
    ),
    "yolox-LICENSE.txt": (YOLOX + "LICENSE", "577c03d505ec80f667ebf96ebd0cc4f6825c817ca1088ee348c48aaabd51bd92"),
    "yunet.onnx": (
        "https://media.githubusercontent.com/media/" + YUNET + "face_detection_yunet_2026may.onnx",
        "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0",
    ),
    "yunet-LICENSE.txt": (
        "https://raw.githubusercontent.com/" + YUNET + "LICENSE",
        "c83b8120c50ccbd4c4f96edf53141bdd566ebb8f8e9227e415326aa1b1aba958",
    ),
}


def stage(source: Path, destination: Path, download: bool) -> None:
    verified = {}
    for name, (url, expected) in FILES.items():
        path = source / name
        if not path.is_file() and download:
            with urlopen(url, timeout=45) as response:
                data = response.read(8_000_001)
            if hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit(f"Unexpected SHA-256 for {name}")
            source.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        if not path.is_file():
            raise SystemExit(f"Missing {path}. Use --download to fetch the pinned models.")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f"Unexpected SHA-256 for {name}")
        verified[name] = data
    label_source = ast.parse(verified.pop("coco_classes.py"))
    labels = ast.literal_eval(label_source.body[0].value)
    verified["coco-labels.txt"] = ("\n".join(labels) + "\n").encode("utf-8")
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in verified.items():
        pending = destination / (name + ".staging")
        pending.write_bytes(data)
        os.replace(pending, destination / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "app" / "build" / "detection-models")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    stage(args.source, ROOT / ".local-models" / "detection", args.download)
