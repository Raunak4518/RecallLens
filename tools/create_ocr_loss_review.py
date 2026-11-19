import argparse
import csv
import difflib
import hashlib
import html
import json
from pathlib import Path
import textwrap
import unicodedata
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]


def normalized(value):
    text = unicodedata.normalize("NFKC", value).lower()
    return " ".join("".join(char if char.isalnum() else " " for char in text).split())


def best_line(lines, query):
    exact = [line for line in lines if " " + normalized(query) + " " in " " + normalized(line) + " "]
    return next(iter(exact), max(lines, key=lambda line: difflib.SequenceMatcher(None, query.lower(), line.lower()).ratio(), default=""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=ROOT / "app/build/large-ocr-comparison/emulator.json")
    parser.add_argument("--font", type=Path, default=Path("C:/Windows/Fonts/arial.ttf"))
    args = parser.parse_args()
    photos = ROOT / "app/build/public-evaluation/selective-ocr"
    manifest_path = photos / "manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    if hashlib.sha256(manifest_bytes).hexdigest() != report["manifest_sha256"]:
        raise ValueError("Report and corpus differ")
    metadata = {row["image_id"]: row for row in manifest["images"]}
    records = {row["id"]: row for row in report["images"]}
    with (args.report.parent / "lost-queries.csv").open(encoding="utf-8-sig", newline="") as stream:
        losses = list(csv.DictReader(stream))
    queries = {row["id"]: row for row in report["queries"]}
    output = args.report.parent / "loss-review"
    output.mkdir(exist_ok=True)
    font = ImageFont.truetype(str(args.font), 16)
    rows = []
    for page in range((len(losses) + 3) // 4):
        sheet = Image.new("RGB", (1440, 1200), "white")
        draw = ImageDraw.Draw(sheet)
        for index, row in enumerate(losses[page * 4:page * 4 + 4]):
            result = queries[row["id"]]
            if row["target"] not in result["mlkit"] or row["target"] in result["tiny"]:
                raise ValueError("CSV contains a non-loss query")
            source = photos / (row["target"] + ".jpg")
            if hashlib.sha256(source.read_bytes()).hexdigest() != metadata[row["target"]]["sha256"]:
                raise ValueError("Photo checksum changed")
            x, y = index % 2 * 720, index // 2 * 600
            with Image.open(source) as image:
                photo = image.convert("RGB")
            photo.thumbnail((700, 390))
            sheet.paste(photo, (x + (720 - photo.width) // 2, y + 4))
            record = records[row["target"]]
            one = best_line(record["mlkit"]["lines"], row["query"])
            two = best_line(record["tiny"]["lines"], row["query"])
            captions = [f"{row['id']} Remembered: {row['query']}", f"ML Kit matching line: {one}",
                        f"Tiny closest line: {two}", f"{row['target']} | {html.unescape(row['author'])} | CC BY 2.0"]
            cursor = y + 400
            for caption in captions:
                for line in textwrap.wrap(caption, width=78):
                    draw.text((x + 12, cursor), line, fill="black", font=font)
                    cursor += 21
            rows.append({**row, "mlkit_line": one, "tiny_closest_line": two,
                         "mlkit_all_lines": record["mlkit"]["lines"], "tiny_all_lines": record["tiny"]["lines"],
                         "image_sha256": metadata[row["target"]]["sha256"]})
        sheet.save(output / f"losses-{page + 1}.jpg", quality=92)
    (output / "review.json").write_text(json.dumps({
        "scope": "All lost queries. Closest lines use string similarity and are not a causal diagnosis. Images resized for review.",
        "manifest_sha256": report["manifest_sha256"], "rows": rows,
    }, indent=2), encoding="utf-8")
    print(f"Reviewed artifacts: {len(rows)} query rows in {output}")


if __name__ == "__main__":
    main()
