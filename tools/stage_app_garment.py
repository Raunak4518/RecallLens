import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
MODEL_URL = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite"
MODEL_CARD = "https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf"
SHA256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"


def fetch(url, path, limit):
    if not path.is_file():
        with urlopen(url, timeout=60) as response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise ValueError("Artifact too large")
            path.write_bytes(data)
    return path.read_bytes()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "app" / "build" / "garment-model")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    args.source.mkdir(parents=True, exist_ok=True)
    name = "selfie_multiclass_256x256.tflite"
    model = args.source / name
    if not model.is_file() and not args.download:
        raise SystemExit("Pinned model missing. Use --download.")
    data = fetch(MODEL_URL, model, 17_000_000)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit("Unexpected MediaPipe model SHA-256")
    card = fetch(MODEL_CARD, args.source / "model-card.pdf", 2_000_000)
    license_text = fetch("https://www.apache.org/licenses/LICENSE-2.0.txt", args.source / "Apache-2.0.txt", 20_000)
    target = ROOT / ".local-models" / "garment"
    target.mkdir(parents=True, exist_ok=True)
    for filename, artifact in ((name, data), ("model-card.pdf", card), ("Apache-2.0.txt", license_text)):
        pending = target / (filename + ".staging")
        pending.write_bytes(artifact)
        pending.replace(target / filename)
    provenance = {
        "model_url": MODEL_URL, "sha256": SHA256, "license": "Apache-2.0", "model_card_url": MODEL_CARD,
        "model_card_sha256": hashlib.sha256(card).hexdigest(),
        "capability": "clothing mask in upper person region; no garment subtype or identity",
        "normalization": {"mean": [127.5], "std": [127.5]},
    }
    (target / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    print(f"Staged {name}, {len(data)} bytes, SHA-256 {SHA256}")


if __name__ == "__main__":
    main()
