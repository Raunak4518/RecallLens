import argparse
import gzip
import json
import random
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

from benchmark_full_photo_ocr import bounds, eligible, lines, snippets, tokens
from benchmark_public_datasets import BUILD, DATA, attribution, digest, get_image, metadata, order_key, write_json


ASSETS = BUILD / "public-evaluation" / "hybrid-ocr"
FONTS = ("arial.ttf", "arialbd.ttf", "times.ttf", "consola.ttf")
CHALLENGES = ("small-print", "low-contrast", "blur", "rotation", "perspective", "compression")
NAMES = ("ORCHARD", "HARBOUR", "QUARRY", "RIVERSIDE", "SUMMIT", "LAGOON",
         "MEADOW", "REDWOOD", "FOUNDRY", "MERIDIAN", "HORIZON", "STATION")
WORDS = ("ACACIA", "BIRCH", "CEDAR", "DOGWOOD", "ELMWOOD", "FIRWOOD",
         "GINKGO", "HAZEL", "JUNIPER", "LINDEN", "SEQUOIA", "WILLOW")
SIZE = (1200, 800)
SENSITIVE_CARD = (78, 641, 925, 720)


def normalized(text):
    return " ".join(tokens(text))


def normalized_box(box, size):
    return [min(1.0, max(0.0, value / size[axis % 2])) for axis, value in enumerate(box)]


def public_photos():
    data = metadata()
    annotations = DATA / "hiertext-validation.jsonl.gz"
    with gzip.open(annotations, "rt", encoding="utf-8") as source:
        available = {photo["image_id"]: photo for photo in json.load(source)["annotations"]}

    def stage(image_id):
        row = available[image_id]
        candidates = [line for line in lines(row) if eligible(line) and list(snippets(line["text"], 2))]
        if not candidates:
            return None
        ordered = sorted(candidates, key=lambda line: (text_height(line), order_key(line["text"])))
        target = ordered[len(ordered) // 3]
        size = row["image_width"], row["image_height"]
        target_box = normalized_box(bounds(target["vertices"]), size)
        candidates = [line for line in candidates if line is not target and normalized(line["text"]) != normalized(target["text"])]
        others = [line for line in candidates if separate(target_box, normalized_box(bounds(line["vertices"]), size))]
        original = get_image(image_id)
        with Image.open(original) as loaded:
            oriented = ImageOps.exif_transpose(loaded)
            if abs((oriented.width / oriented.height) / (row["image_width"] / row["image_height"]) - 1) > .015:
                return None
            oriented.thumbnail((1600, 1600))
            filename = image_id + ".jpg"
            oriented.convert("RGB").save(ASSETS / filename, quality=95)
        text = list(snippets(target["text"], 2))[0]
        keywords = list(snippets(target["text"], 1))
        if not keywords:
            return None
        covered = max(others, key=text_height, default=None)
        sensitive = normalized_box(bounds(covered["vertices"]), size) if covered else [0.002, 0.002, .025, .025]
        if sensitive[0] >= sensitive[2] or sensitive[1] >= sensitive[3]:
            raise ValueError(f"Invalid annotation mask for {image_id}")
        return {"id": image_id, "file": filename, "group": "hiertext", "sensitive": sensitive,
                "target_box": target_box,
                "public": [max(keywords, key=len), text], "protected": None,
                "lines": [line["text"] for line in lines(row) if line["legible"]],
                "provenance": attribution(data[image_id], original), "sha256": digest(ASSETS / filename)}

    ids = sorted(data.keys() & available.keys(), key=order_key)
    with ThreadPoolExecutor(max_workers=8) as pool:
        return [item for item in pool.map(stage, ids) if item is not None]


def text_height(line):
    _, top, _, bottom = bounds(line["vertices"])
    return bottom - top


def separate(left, right):
    return left[2] + .025 < right[0] or right[2] + .025 < left[0] or left[3] + .025 < right[1] or right[3] + .025 < left[1]


def synthetic_photos(font_dir):
    for group, challenge in enumerate(CHALLENGES):
        for position, name in enumerate(NAMES):
            number = group * len(NAMES) + position + 1
            rng = random.Random(8391 + number)
            image = Image.new("RGB", SIZE, (193, 188, 177))
            draw = ImageDraw.Draw(image)
            for y in range(0, SIZE[1], 3):
                shade = rng.randrange(164, 195)
                draw.line((0, y, SIZE[0], y), fill=(shade + 14, shade + 11, shade + 4), width=3)
            card = Image.new("RGB", (860, 460), (238, 236, 225))
            text = ImageDraw.Draw(card)
            font_path = font_dir / FONTS[position % len(FONTS)]
            display = f"{name} {WORDS[(position + group) % len(WORDS)]}"
            font_size = 13 + 3 * (position % 6) if group == 0 else 28 + 6 * (position % 5)
            text.rectangle((1, 1, 858, 458), outline=(65, 62, 55), width=3)
            text.text((38, 42), "COMMUNITY EXCHANGE", fill=(61, 60, 55),
                      font=ImageFont.truetype(font_dir / FONTS[1], 46))
            text.text((44, 178), display, fill=(42, 43, 41), font=ImageFont.truetype(font_path, font_size))
            text.text((44, 286), "KEEP THIS NOTICE FOR REFERENCE", fill=(72, 70, 69),
                      font=ImageFont.truetype(font_path, 20))
            if group == 1:
                card = ImageEnhance.Contrast(card).enhance(.14 + .04 * (position % 6))
            if group == 2:
                card = card.filter(ImageFilter.GaussianBlur(1.7 + .5 * (position % 6)))
            if group == 3:
                card = card.rotate((-1 if position % 2 else 1) * (8 + 3 * (position % 6)),
                                   expand=True, resample=Image.Resampling.BICUBIC, fillcolor=(197, 194, 184))
            if group == 4:
                strength = .00014 + .000065 * (position % 6)
                card = card.transform(card.size, Image.Transform.PERSPECTIVE, (1, .11, -10, .07, 1, -20, strength, .0001),
                                      Image.Resampling.BICUBIC, fillcolor=(197, 194, 184))
            image.paste(card, ((SIZE[0] - card.width) // 2, max(20, 309 - card.height // 2)))
            generated_email = f"fixture{number:03}@example.test"
            draw.rectangle(SENSITIVE_CARD, fill=(250, 248, 237))
            draw.text((97, 658), f"Internal contact {generated_email}", fill=(32, 32, 32),
                      font=ImageFont.truetype(font_dir / FONTS[0], 28))
            filename = f"synthetic-{number:03}.jpg"
            image.save(ASSETS / filename, quality=9 + 2 * (position % 6) if group == 5 else 89)
            yield {"id": Path(filename).stem, "file": filename, "group": challenge,
                   "sensitive": normalized_box(SENSITIVE_CARD, SIZE), "target_box": None,
                   "public": [name.lower(), display.lower()], "protected": normalized(generated_email),
                   "lines": [display, "COMMUNITY EXCHANGE", "KEEP THIS NOTICE FOR REFERENCE",
                             f"Internal contact {generated_email}"],
                   "sha256": digest(ASSETS / filename)}


def prepare(font_dir):
    ASSETS.mkdir(parents=True, exist_ok=True)
    photos = public_photos() + list(synthetic_photos(font_dir))
    if sum(photo["group"] == "hiertext" for photo in photos) < 20:
        raise ValueError("Too few verified HierText photos")
    queries = []
    for photo in photos:
        for index, text in enumerate(photo["public"]):
            queries.append({"text": text, "target": photo["id"], "kind": ("word", "phrase")[index], "group": photo["group"]})
        if photo["protected"]:
            queries.append({"text": photo["protected"], "target": photo["id"], "kind": "protected", "group": photo["group"]})
    for index in range(12):
        queries.append({"text": f"mauve capybara {index:02}", "target": None, "kind": "absent", "group": "control"})
    relevant = defaultdict(set)
    requested = {row["text"] for row in queries}
    lengths = {len(text.split()) for text in requested}
    for photo in photos:
        for line in photo["lines"]:
            words = tokens(line)
            for size in lengths:
                for at in range(len(words) - size + 1):
                    text = " ".join(words[at:at + size])
                    if text in requested:
                        relevant[text].add(photo["id"])
    for index, query in enumerate(queries, 1):
        query.update(id=f"q{index:03}", relevant=sorted(relevant[query["text"]]))
    for row in queries:
        if row["target"] is not None and row["target"] not in row["relevant"]:
            raise ValueError(f"Invalid remembered text for {row['target']}: {row['text']}")
    output = {"corpus": {"annotations_sha256": digest(DATA / "hiertext-validation.jsonl.gz"),
                         "metadata_sha256": digest(DATA / "open-images-metadata.csv"),
                         "fonts_sha256": {font: digest(font_dir / font) for font in FONTS}},
              "images": photos, "queries": queries}
    write_json(ASSETS / "manifest.json", output)
    print(f"Frozen {len(photos)} photos and {len(queries)} queries at {ASSETS}. Manifest {digest(ASSETS / 'manifest.json')}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", type=Path, default=Path("C:/Windows/Fonts"))
    prepare(parser.parse_args().font_dir)
