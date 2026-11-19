import argparse
import hashlib
import os
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
SFACE = "opencv/opencv_zoo/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_recognition_sface/"
DINO = "https://huggingface.co/Xenova/dinov2-small/resolve/c2bb04a51fab207c420665f1946016107bffc701/"
MODELS = {
    "sface.onnx": ("https://media.githubusercontent.com/media/" + SFACE + "face_recognition_sface_2021dec.onnx",
                  "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"),
    "sface-LICENSE.txt": ("https://raw.githubusercontent.com/" + SFACE + "LICENSE",
                         "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    "dinov2.onnx": (DINO + "onnx/model_quantized.onnx",
                    "3afdc8bc63b50558d6e5770f5b799bb82455c2311183a2de43803f343a29d917"),
    "dinov2-LICENSE.txt": ("https://raw.githubusercontent.com/facebookresearch/dinov2/main/LICENSE",
                           "600cc67cc4cb2f5ea317dcfc687ad1c74dc4bec8782bbe9db0afd83513b935b7"),
}


def stage(source: Path, download: bool) -> None:
    target = ROOT / ".local-models" / "identity"
    cache = ROOT / "app" / "build" / "feasibility-models" / "dinov2-c2bb04a" / "model_quantized.onnx"
    target.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in MODELS.items():
        path = source / name
        destination = target / name
        if not path.is_file() and name == "dinov2.onnx" and cache.is_file():
            path = cache
        if not path.is_file() and destination.is_file():
            path = destination
        if path.is_file():
            data = path.read_bytes()
        elif download:
            with urlopen(url, timeout=60) as response:
                data = response.read(64_000_001)
        else:
            raise SystemExit(f"Missing {name}. Use --download to fetch the pinned files.")
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f"Unexpected SHA-256 for {name}")
        pending = destination.with_suffix(destination.suffix + ".staging")
        pending.write_bytes(data)
        os.replace(pending, destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "app" / "build" / "identity-models")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    stage(args.source, args.download)
