import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
IMAGE_ID = "53e5999176303196"
ATTRIBUTION = {
    "id": IMAGE_ID,
    "sha256": "aeab440d87a2418680c092f8ab1d4fbf2fd894a0075d6c9762f735d0429e6b7a",
    "author": "Anthony Topper",
    "source": "https://www.flickr.com/photos/ice5nake/25180333",
    "license": "https://creativecommons.org/licenses/by/2.0/",
    "license_review": "publisher_metadata_only_not_independently_reverified",
}


def prepare(source, target, download=False):
    if source.is_file():
        with source.open("rb") as handle:
            data = handle.read(8 * 1024 * 1024 + 1)
    elif download:
        url = "https://open-images-dataset.s3.amazonaws.com/validation/" + IMAGE_ID + ".jpg"
        with urlopen(url, timeout=60) as response:
            data = response.read(8 * 1024 * 1024 + 1)
    else:
        raise ValueError("Missing public fixture. Supply --source or explicitly allow --download for one image.")
    if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != ATTRIBUTION["sha256"]:
        raise ValueError("Fixture bytes do not match the pinned photo")
    target.mkdir(parents=True, exist_ok=True)
    image = target / (IMAGE_ID + ".jpg")
    if not image.is_file() or image.read_bytes() != data:
        temporary = image.with_suffix(".jpg.tmp")
        temporary.write_bytes(data)
        temporary.replace(image)
    (target / "attribution.json").write_text(json.dumps(ATTRIBUTION, indent=2), encoding="utf-8")
    return {"image": str(image.resolve()), "bytes": len(data), "sha256": ATTRIBUTION["sha256"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / ".benchmark-data/gallery-v1/images" / (IMAGE_ID + ".jpg"))
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, ROOT / "app/build/public-evaluation/complex-content", args.download)))


if __name__ == "__main__":
    main()
