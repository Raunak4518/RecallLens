import argparse
import html
import json
from pathlib import Path
import textwrap

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=ROOT / ".benchmark-data/gallery-v1")
    parser.add_argument("--results", type=Path, default=ROOT / "app/build/large-gallery-benchmark")
    args = parser.parse_args()
    manifest = json.loads((args.corpus / "manifest.json").read_text(encoding="utf-8"))
    report = json.loads((args.results / "emulator.json").read_text(encoding="utf-8"))
    if not report.get("complete"):
        raise ValueError("Only complete benchmark results may be visualized")
    images = {row["id"]: row for row in manifest["images"]}
    queries = {row["query"]: row for row in manifest["queries"]}
    font_path = Path("C:/Windows/Fonts/arial.ttf")
    font = ImageFont.truetype(str(font_path), 19) if font_path.is_file() else ImageFont.load_default(size=19)
    small = ImageFont.truetype(str(font_path), 14) if font_path.is_file() else ImageFont.load_default(size=14)
    attributions, pages = {}, []
    for family in sorted({row["type"] for row in report["queries"]}):
        selected = [row for row in report["queries"] if row["type"] == family]
        selected.sort(key=lambda row: (row["ranking"][0]["id"] in queries[row["query"]]["positive"], row["query"]))
        selected = selected[:4]
        canvas = Image.new("RGB", (1400, 90 + len(selected) * 330), "#f7f7f7")
        draw = ImageDraw.Draw(canvas)
        draw.text((24, 18), "Actual top-5 results: " + family.replace("_", " "), font=font, fill="#202020")
        page = ["<!doctype html><meta charset='utf-8'><title>Actual photo retrieval</title>",
                "<style>body{font:16px system-ui;background:#f7f7f7;color:#222;margin:30px}section{margin:40px 0}.photos{display:flex;gap:16px}figure{width:18%;margin:0}img{width:100%;height:180px;object-fit:contain;background:white}figcaption{font-size:12px}</style>",
                "<p>Frozen real images and actual rankings. Known positives, explicit negatives and unjudged items stay separate. These examples include failures.</p>"]
        for position, result in enumerate(selected):
            query = queries[result["query"]]
            positive, negative = set(query["positive"]), set(query["explicit_negative"])
            top = 70 + position * 330
            for line_number, line in enumerate(textwrap.wrap(result["query"], 105)):
                draw.text((24, top + 22 * line_number), line, font=font, fill="#202020")
            page.append("<section><h2>" + html.escape(result["query"]) + "</h2><div class='photos'>")
            for rank, row in enumerate(result["ranking"][:5]):
                image = images[row["id"]]
                status = "known positive" if row["id"] in positive else "explicit negative" if row["id"] in negative else "unjudged"
                color = {"known positive": "#22663c", "explicit negative": "#9a3030", "unjudged": "#666666"}[status]
                left, image_top = 24 + rank * 275, top + 60
                with Image.open(args.corpus / image["file"]) as original:
                    thumbnail = ImageOps.contain(ImageOps.exif_transpose(original).convert("RGB"), (250, 190))
                    canvas.paste(thumbnail, (left + (250 - thumbnail.width) // 2, image_top + (190 - thumbnail.height) // 2))
                draw.text((left, image_top + 198), f"#{rank + 1} {status} | {row['score']:.3f}", font=small, fill=color)
                draw.text((left, image_top + 219), row["id"], font=small, fill="#333333")
                draw.text((left, image_top + 240), "CC BY 2.0; credits in paired HTML", font=small, fill="#555555")
                source = (args.corpus / image["file"]).resolve().as_uri()
                page.append("<figure><img src='" + html.escape(source, quote=True) + "'><figcaption>" +
                    html.escape(f"#{rank + 1} {status}; score {row['score']:.3f}. Photo by {image['author']}. ") +
                    "<a href='" + html.escape(image["source"], quote=True) + "'>Original</a> · <a href='" +
                    html.escape(image["license"], quote=True) + "'>License</a></figcaption></figure>")
                attributions[row["id"]] = {key: image[key] for key in ("id", "author", "source", "license", "sha256")}
            page.append("</div></section>")
        name = "examples-" + family
        canvas.save(args.results / (name + ".png"))
        (args.results / (name + ".html")).write_text("\n".join(page), encoding="utf-8")
        pages.append(name)
    (args.results / "visual-attributions.json").write_text(json.dumps(attributions, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"pages": pages, "attributed_images": len(attributions)}))


if __name__ == "__main__":
    main()
