import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHOTOS = ROOT / "app" / "build" / "public-evaluation" / "selective-ocr"
DESTINATION = ROOT / "app" / "build" / "garment-evaluation" / "device"
PARSER_MODEL = ROOT / ".local-models" / "garment" / "selfie_multiclass_256x256.tflite"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cohort", type=Path)
    parser.add_argument("--output", type=Path, default=DESTINATION)
    args = parser.parse_args()
    manifest = json.loads((PHOTOS / "manifest.json").read_text(encoding="utf-8"))
    annotations = {
        "0569d065055bafb6": [
            {"face": [.29, .17, .48, .43], "color": "GREEN"},
            {"face": [.57, .36, .72, .59], "color": "GRAY"},
        ],
        "87566e496632db67": [
            {"face": [.18, 0, .44, .4], "color": "WHITE"},
            {"face": [.61, 0, .94, .43], "color": "RED"},
        ],
        "b03c06fee07435de": [
            {"face": [.34, .07, .52, .42], "color": "WHITE"},
            {"face": [.75, .06, .96, .45], "color": "WHITE"},
        ],
        "444183da0584478c": [
            {"face": [.4, .03, .64, .22], "color": "BLACK"},
        ],
    }
    cohort = json.loads(args.cohort.read_text(encoding="utf-8")) if args.cohort else None
    definitions = {row["image_id"]: row for row in cohort["images"]} if cohort else {}
    if cohort:
        if len(definitions) != len(cohort["images"]):
            raise ValueError("Duplicate cohort image")
        if cohort.get("max_image_edge") != 1600:
            raise ValueError("Cohort must use the production 1600-pixel decode limit")
        annotations = {key: value["annotations"] for key, value in definitions.items()}
    args.output.mkdir(parents=True, exist_ok=True)
    staged = []
    for row in manifest["images"]:
        key = row["image_id"]
        if key not in annotations:
            continue
        source = PHOTOS / f"{key}.jpg"
        if hashlib.sha256(source.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"Photo changed: {key}")
        shutil.copyfile(source, args.output / source.name)
        staged.append({**row, **definitions.get(key, {}), "annotations": annotations[key]})
    if len(staged) != len(annotations):
        raise ValueError("Missing evaluated photo")
    for row in staged:
        if row["image_license"] != "https://creativecommons.org/licenses/by/2.0/":
            raise ValueError("Unexpected photo license")
        for annotation in row["annotations"]:
            left, top, right, bottom = annotation["face"]
            if not 0 <= left < right <= 1 or not 0 <= top < bottom <= 1:
                raise ValueError("Invalid face box")
            labels = annotation.get("colors", [annotation.get("color")])
            if not isinstance(labels, list) or not labels or any(not isinstance(color, str) or not color.isupper() for color in labels):
                raise ValueError("Missing color labels")
    payload = {
        "evaluation_only": True,
        "parser_sha256": hashlib.sha256(PARSER_MODEL.read_bytes()).hexdigest(),
        "max_image_edge": 1600,
        "corpus_manifest_sha256": hashlib.sha256((PHOTOS / "manifest.json").read_bytes()).hexdigest(),
        "cohort_sha256": hashlib.sha256(args.cohort.read_bytes()).hexdigest() if args.cohort else None,
        "images": staged,
        "annotation_note": cohort["annotation_note"] if cohort else "Coarse manually inspected face boxes and upper-clothing colors. Four-image smoke cohort.",
    }
    (args.output / "manifest.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
