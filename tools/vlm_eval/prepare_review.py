import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile

from PIL import Image, ImageDraw, ImageFont, ImageOps


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(corpus, baseline, output):
    manifest_path = corpus / "manifest.json"
    manifest_sha = digest(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    report = json.loads(baseline.read_text(encoding="utf-8"))
    images = {row["id"]: row for row in manifest["images"]}
    queries = {row["query"]: row for row in manifest["queries"]}
    query_ids = [row["id"] for row in manifest["queries"]]
    if len(set(query_ids)) != len(query_ids) or any(not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", value) for value in query_ids):
        raise ValueError("Need unique safe query IDs")
    rows = report["queries"]
    if (not queries or not report.get("complete") or report.get("manifest_sha256") != manifest_sha
            or len(images) != len(manifest["images"]) or len(queries) != len(manifest["queries"])
            or len(rows) != len(queries) or {row["query"] for row in rows} != set(queries)):
        raise ValueError("Need complete baseline results for the exact frozen corpus and queries")
    target = output / "judgments.json"
    if target.exists():
        raise ValueError("Existing judgments must not be overwritten")
    document = {
        "version": 2,
        "corpus_sha256": manifest_sha,
        "baseline_sha256": digest(baseline),
        "review_status": "pending_visual_review_before_vlm_outputs",
        "review_scope": "Fixed top8 pools; AI-only judgments remain provisional and are not independent human truth",
        "presentation": "Candidate IDs ordered by sha256(corpus_sha/query_id/image_id), independently of baseline rank and score; baseline file retains original ranking",
        "queries": [],
    }
    for row in rows:
        ranking = row["ranking"]
        ids = [candidate["id"] for candidate in ranking]
        if len(ids) != len(images) or set(ids) != set(images) or len(ids) < 8:
            raise ValueError("Incomplete or duplicate baseline ranking")
        previous = math.inf
        for candidate in ranking:
            score = candidate["score"]
            if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or score > previous:
                raise ValueError("Need finite descending baseline scores")
            previous = score
        query = queries[row["query"]]
        candidates = []
        for candidate in ranking[:8]:
            image = images[candidate["id"]]
            path = (corpus / image["file"]).resolve()
            if not path.is_relative_to(corpus.resolve()) or digest(path) != image["sha256"]:
                raise ValueError("Candidate path or original photo hash changed")
            candidates.append({
                "id": candidate["id"],
                "file": image["file"], "sha256": image["sha256"],
                "author": image["author"], "source": image["source"], "license": image["license"],
                "judgment": None, "reason": "",
            })
        candidates.sort(key=lambda candidate: hashlib.sha256(
            f"{manifest_sha}/{query['id']}/{candidate['id']}".encode("utf-8")).hexdigest())
        document["queries"].append({"id": query["id"], "query": row["query"], "type": query["type"], "candidates": candidates})
    output.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default(size=18)
    title_font = ImageFont.load_default(size=23)
    for query in document["queries"]:
        sheet = Image.new("RGB", (1600, 820), "white")
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 10), query["id"] + " - " + query["query"], fill="black", font=title_font)
        for index, candidate in enumerate(query["candidates"]):
            x, y = (index % 4) * 400, 65 + (index // 4) * 375
            with Image.open(corpus / candidate["file"]) as original:
                photo = ImageOps.exif_transpose(original).convert("RGB")
                photo.thumbnail((390, 320))
                sheet.paste(photo, (x + 5, y))
            draw.text((x + 5, y + 323), f"ID {candidate['id']}", fill="black", font=font)
        sheet.save(output / (query["id"] + ".jpg"), quality=94)
    with tempfile.NamedTemporaryFile(dir=output, prefix="judgments-", suffix=".tmp", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write((json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    try:
        os.link(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    return {"queries": len(document["queries"]), "candidate_slots": 8 * len(document["queries"]), "judgments": str(target)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.corpus, args.baseline, args.output)))


if __name__ == "__main__":
    main()
