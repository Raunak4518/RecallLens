import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import html
from io import BytesIO
import json
from pathlib import Path
import re
from threading import Lock
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
from sklearn.linear_model import LogisticRegression

from tools.model_eval.clip_text_precision_sweep import encode_text_graph
from tools.model_eval.encoders import AppClipOnnxEncoder


LANDMARKS = (
    ("Taj Mahal", "Q9141"), ("Eiffel Tower", "Q243"), ("Colosseum", "Q10285"),
    ("Tower Bridge", "Q83125"), ("Golden Gate Bridge", "Q44440"),
    ("Arc de Triomphe", "Q64436"), ("Sagrada Família", "Q48435"), ("Sydney Opera House", "Q45178"),
)
EXTENDED_LANDMARKS = LANDMARKS + (
    ("Big Ben", "Q41225"), ("Burj Khalifa", "Q12495"), ("Statue of Liberty", "Q9202"),
    ("Great Pyramid of Giza", "Q37200"), ("Leaning Tower of Pisa", "Q39054"),
    ("Brandenburg Gate", "Q82425"), ("Mount Fuji", "Q39231"), ("Petra", "Q5788"),
)
UNKNOWN_DEV = (
    ("Tokyo Tower", "Q183536"), ("Amphitheatre of El Jem", "Q2914326"),
    ("Gateway of India", "Q864862"), ("Hagia Sophia", "Q12506"),
)
UNKNOWN_TEST = (
    ("Brooklyn Bridge", "Q125006"), ("Bibi Ka Maqbara", "Q856259"),
    ("Milan Cathedral", "Q18068"), ("London Bridge", "Q130206"),
)
LICENSE = re.compile(r"^CC BY(?:-SA)? (?:2\.0|2\.5|3\.0|4\.0)$|^CC0(?: 1\.0)?$|^Public domain$", re.I)
MEMBERS = ("train", "train", "train", "train", "train", "dev", "dev", "dev", "test", "test", "test", "test")
USER_AGENT = "PhotoLibraryResearch/0.1 (bounded Wikimedia Commons landmark benchmark)"
IMAGE_LIMIT = 4 * 1024**2
MEDIA_HOSTS = {"upload.wikimedia.org", "thumb.wikimedia.org"}


class DownloadBudget:
    def __init__(self, limit):
        self.limit = limit
        self.transferred = 0
        self.requests = 0
        self.lock = Lock()

    def get(self, url):
        with self.lock:
            if self.requests >= 512 or self.transferred + IMAGE_LIMIT > self.limit:
                raise RuntimeError("Landmark image transfer budget exhausted")
            self.requests += 1
            self.transferred += IMAGE_LIMIT
        data = read_url(url, IMAGE_LIMIT)
        with self.lock:
            self.transferred -= IMAGE_LIMIT - len(data)
        return data


def read_url(url, limit):
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=20) as source:
        origin = urlparse(url).hostname
        destination = urlparse(source.geturl()).hostname
        if destination != origin and not (origin in MEDIA_HOSTS and destination in MEDIA_HOSTS):
            raise ValueError("Unexpected response host")
        contents = source.read(limit + 1)
    if not 0 < len(contents) <= limit:
        raise ValueError("Unexpected response size")
    return contents


def discover(landmark):
    name, qid = landmark
    query = urlencode({
        "action": "query", "generator": "search", "gsrsearch": f'haswbstatement:P180={qid} filetype:bitmap',
        "gsrnamespace": 6, "gsrlimit": 90, "prop": "imageinfo", "iiprop": "url|mime|extmetadata",
        "iiurlwidth": 500, "format": "json", "formatversion": 2,
    })
    url = "https://commons.wikimedia.org/w/api.php?" + query
    for attempt in range(3):
        try:
            response = json.loads(read_url(url, 12 * 1024**2))
            break
        except HTTPError as error:
            if error.code not in (429, 503) or attempt == 2:
                raise
            retry = error.headers.get("Retry-After", "")
            delay = max(20 * (attempt + 1), int(retry) if retry.isdecimal() else 0)
            if delay > 120:
                raise
            print(f"Wikimedia requested a metadata retry for {name}; waiting {delay}s", flush=True)
            time.sleep(delay)
    pages = response.get("query", {}).get("pages", ())
    candidates = []
    for page in sorted(pages, key=lambda row: row["index"]):
        metadata_rows = page.get("imageinfo", ())
        if not metadata_rows:
            continue
        info = metadata_rows[0]
        metadata = info.get("extmetadata", {})
        license_name = metadata.get("LicenseShortName", {}).get("value", "")
        license_url = metadata.get("LicenseUrl", {}).get("value", "")
        attribution = metadata.get("Artist", {}).get("value", "")
        artist = html.unescape(re.sub(r"<[^>]+>", " ", attribution)).strip().split("\n")[0]
        artist_key = re.sub(r"\W", "", artist.casefold())
        media_url = info.get("thumburl", info.get("url", ""))
        if (info["mime"] != "image/jpeg" or not LICENSE.fullmatch(license_name) or not artist_key
                or (license_name.startswith("CC") and urlparse(license_url).hostname != "creativecommons.org")
                or urlparse(media_url).hostname not in MEDIA_HOSTS):
            continue
        candidates.append({
            "landmark": name, "wikidata": qid, "commonsPage": info["descriptionurl"],
            "title": page["title"], "url": media_url, "artist": artist, "artistKey": artist_key,
            "license": license_name, "licenseUrl": license_url,
        })
    return candidates


def decode(data):
    with Image.open(BytesIO(data)) as image:
        if max(image.size) > 6000 or image.width * image.height > 12_000_000:
            raise ValueError("Image exceeds the bound")
        photo = ImageOps.exif_transpose(image).convert("RGB")
    pixels = np.asarray(photo.convert("L").resize((9, 8), Image.Resampling.LANCZOS))
    bits = np.packbits((pixels[:, 1:] > pixels[:, :-1]).ravel())
    return photo, int.from_bytes(bits.tobytes(), "big")


def download(row, budget):
    try:
        payload = budget.get(row["url"])
        photo, fingerprint = decode(payload)
        return photo, fingerprint, hashlib.sha256(payload).hexdigest()
    except (HTTPError, URLError, OSError, ValueError):
        return None


def take(candidates, artists, fingerprints, wanted, budget, pool):
    chosen = []
    for start in range(0, len(candidates), 4):
        batch = [row for row in candidates[start:start + 4] if row["artistKey"] not in artists]
        for row, loaded in zip(batch, pool.map(lambda entry: download(entry, budget), batch), strict=True):
            if loaded is None:
                continue
            photo, fingerprint, digest = loaded
            if (len(chosen) >= wanted or row["artistKey"] in artists
                    or any((fingerprint ^ other).bit_count() <= 5 for other in fingerprints)):
                photo.close()
                continue
            artists.add(row["artistKey"])
            fingerprints.append(fingerprint)
            chosen.append((row, photo, digest))
        if len(chosen) == wanted:
            return chosen
    for _, photo, _ in chosen:
        photo.close()
    landmark = candidates[0]["landmark"] if candidates else "unknown landmark"
    raise ValueError(f"Insufficient distinct licensed photos for {landmark}. Received {len(chosen)} of {wanted}")


def hits(scores, labels):
    ranked = np.argsort(-scores, axis=1, kind="stable")
    matches = ranked == labels[:, None]
    return {"queries": int(len(labels)), "hit1": int(matches[:, 0].sum()), "hit3": int(matches[:, :3].any(axis=1).sum())}


def rejection(known_scores, labels, unknown_scores, threshold):
    maxima = known_scores.max(axis=1).astype(np.float64)
    correct = known_scores.argmax(axis=1) == labels
    accepted = maxima >= threshold
    known_correct = accepted & correct
    unknown_accepted = unknown_scores.max(axis=1).astype(np.float64) >= threshold
    return {
        "known": int(len(labels)), "knownAcceptedCorrect": int(known_correct.sum()),
        "knownAcceptedWrong": int((accepted & ~correct).sum()), "knownRejected": int((~accepted).sum()),
        "unknown": int(len(unknown_scores)), "unknownAccepted": int(unknown_accepted.sum()),
    }


def score_methods(head, classes, train_x, train_y, text_embeddings):
    centroids = np.stack([train_x[train_y == label].mean(axis=0) for label in range(classes)])
    centroids /= np.linalg.norm(centroids, axis=1, keepdims=True)

    def exemplar_scores(items):
        similarities = items @ train_x.T
        return np.stack([similarities[:, train_y == label].max(axis=1) for label in range(classes)], axis=1)

    return {
        "zeroShot": lambda items: items @ text_embeddings.T,
        "trained": head.decision_function,
        "centroid": lambda items: items @ centroids.T,
        "exemplar": exemplar_scores,
    }


def collect(encoder, assignments, budget):
    authors, fingerprints, manifest, rows = set(), [], [], []
    with ThreadPoolExecutor(max_workers=4) as pool:
        for landmark, label, members in assignments:
            name, _ = landmark
            candidates = discover(landmark)
            pictures = take(candidates, authors, fingerprints, len(members), budget, pool)
            try:
                embeddings, _, _ = encoder.encode_images([photo for _, photo, _ in pictures])
                for (row, _, digest), vector, split in zip(pictures, embeddings, members, strict=True):
                    manifest.append({**row, "split": split, "sha256": digest})
                    rows.append((vector, label, split))
            finally:
                for _, photo, _ in pictures:
                    photo.close()
            print(f"Encoded {name}: {len(pictures)} distinct Commons contributors", flush=True)
    return manifest, rows


def run(args):
    started = time.perf_counter()
    landmarks = EXTENDED_LANDMARKS if args.expanded else LANDMARKS
    assignments = [(landmark, label, MEMBERS) for label, landmark in enumerate(landmarks)]
    if args.expanded:
        assignments += [(landmark, -1, (split,) * 6)
                        for rows, split in ((UNKNOWN_DEV, "unknownDev"), (UNKNOWN_TEST, "unknownTest"))
                        for landmark in rows]
    budget = DownloadBudget(args.image_budget_mib * 1024**2)
    encoder = AppClipOnnxEncoder("appclip", "app-clip", args.model, ort)
    prompts = [(qid, {"query": f"a photograph of the {name}"}) for name, qid in landmarks]
    vectors, _ = encode_text_graph(args.text_graph, prompts, encoder.model_directory / "tokenizer.json", ort, 2)
    text_embeddings = np.stack([vectors[qid] for _, qid in landmarks])
    manifest, rows = collect(encoder, assignments, budget)

    def data(split):
        x, y = zip(*[(vector, label) for vector, label, member in rows if member == split])
        return np.stack(x), np.asarray(y)

    train_x, train_y = data("train")
    dev_x, dev_y = data("dev")
    test_x, test_y = data("test")
    trials = []
    for strength in (0.1, 1.0, 10.0):
        head = LogisticRegression(C=strength, max_iter=300).fit(train_x, train_y)
        trials.append((head, hits(head.decision_function(dev_x), dev_y)))
    head, _ = max(trials, key=lambda trial: (trial[1]["hit1"], trial[1]["hit3"], -trial[0].C))
    methods = score_methods(head, len(landmarks), train_x, train_y, text_embeddings)
    dev_scores = {name: score(dev_x) for name, score in methods.items()}
    test_scores = {name: score(test_x) for name, score in methods.items()}
    open_set = {}
    if args.expanded:
        unknown_dev, _ = data("unknownDev")
        unknown_test, _ = data("unknownTest")
        for name, score in methods.items():
            dev_unknown = score(unknown_dev)
            test_unknown = score(unknown_test)
            allowed = int(0.15 * len(unknown_dev))
            sorted_maxima = np.sort(dev_unknown.max(axis=1).astype(np.float64))[::-1]
            threshold = float(np.nextafter(sorted_maxima[allowed], np.inf))
            open_set[name] = {
                "threshold": threshold,
                "dev": rejection(dev_scores[name], dev_y, dev_unknown, threshold),
                "test": rejection(test_scores[name], test_y, test_unknown, threshold),
            }
    return {
        "source": "https://commons.wikimedia.org/",
        "modelSha256": encoder.model_sha256,
        "textGraphSha256": hashlib.sha256(args.text_graph.read_bytes()).hexdigest(),
        "trainPhotos": int(len(train_y)),
        "classes": dict(landmarks),
        "unknownClasses": {"dev": dict(UNKNOWN_DEV), "test": dict(UNKNOWN_TEST)} if args.expanded else {},
        "dev": {name: hits(scores, dev_y) for name, scores in dev_scores.items()},
        "test": {name: hits(scores, test_y) for name, scores in test_scores.items()},
        "openSet": open_set,
        "maxDevelopmentUnknownAcceptRate": 0.15 if args.expanded else None,
        "imageRequests": budget.requests,
        "imageBytesCharged": budget.transferred,
        "selectedC": head.C,
        "elapsedSeconds": time.perf_counter() - started,
        "images": manifest,
        "scope": "Commons structured depicts annotations with per-image reuse metadata. All photos have distinct normalized artist labels and near-duplicates are filtered by dHash. Artist labels and depicts annotations are contributor supplied. Rejection applies only to the listed lookalike classes and is calibrated on distinct classes. Host evaluation.",
    }


def main():
    parser = argparse.ArgumentParser(description="Fit a bounded landmark classifier on frozen CLIP features.")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--text-graph", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expanded", action="store_true")
    parser.add_argument("--image-budget-mib", type=int, default=96)
    args = parser.parse_args()
    if not 16 <= args.image_budget_mib <= 512:
        parser.error("Image transfer budget must be 16–512 MiB")
    if args.output.exists():
        raise FileExistsError(args.output)
    result = run(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2, ensure_ascii=False)
        output.write("\n")
    print(json.dumps({"dev": result["dev"], "test": result["test"], "elapsedSeconds": result["elapsedSeconds"]}))


if __name__ == "__main__":
    main()
