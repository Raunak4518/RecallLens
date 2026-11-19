import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
from io import BytesIO
import json
from pathlib import Path
import random
import re
import sys
import tarfile
from urllib.error import HTTPError
from urllib.request import urlopen

import cv2
import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/build/identity-benchmark"
TARGET = ROOT / "app/build/public-evaluation/identity-benchmark"
FACE_URL = "https://vision.seas.harvard.edu/pubfig83/"
OBJECT_URL = "https://storage.googleapis.com/objectron/"
SEED = 38457
CATEGORIES = ("camera", "cup", "shoe")
OBJECTS_PER_CATEGORY = 16
FRAMES = (0.20, 0.50, 0.80)


def fetch(url, target, maximum):
    if not target.is_file():
        target.parent.mkdir(exist_ok=True, parents=True)
        with urlopen(url, timeout=45) as response:
            data = response.read(maximum + 1)
        if not data or len(data) > maximum:
            raise ValueError(f"Unexpected download size for {url}")
        temporary = target.with_suffix(".part")
        temporary.write_bytes(data)
        temporary.replace(target)
    return target


def write_manifest(domain, records):
    folder = TARGET / domain
    folder.mkdir(exist_ok=True, parents=True)
    (folder / "manifest.json").write_text(json.dumps(records, indent=2), encoding="utf-8")


def faces():
    archive_file = fetch(FACE_URL + "pubfig83.v1.tgz", SOURCE / "pubfig83.v1.tgz", 45_000_000)
    errata = fetch(FACE_URL + "pubfig83_errata.txt", SOURCE / "pubfig83_errata.txt", 10_000)
    excluded = set()
    for match in re.finditer(r"^(.+?) (\d+(?:,\s*\d+)*)$", errata.read_text().strip(), re.MULTILINE):
        excluded.update((match[1], int(index)) for index in re.findall(r"\d+", match[2]))
    pictures = defaultdict(dict)
    with tarfile.open(archive_file, "r|gz") as archive:
        for member in archive:
            parts = Path(member.name).parts
            if not member.isfile() or len(parts) != 3 or not re.fullmatch(r"\d+\.jpg", parts[-1]):
                continue
            identity, number = parts[1], int(Path(parts[-1]).stem)
            if (identity, number) not in excluded:
                pictures[identity][number] = archive.extractfile(member).read()
    randomizer = random.Random(SEED)
    queried = randomizer.sample(sorted(identity for identity, images in pictures.items() if len(images) >= 3), 48)
    captures = [(identity, randomizer.sample(sorted(pictures[identity]), 3)) for identity in queried]
    hog = cv2.HOGDescriptor((64, 64), (16, 16), (8, 8), (8, 8), 9)

    def feature(data):
        with Image.open(BytesIO(data)) as photo:
            pixels = np.asarray(photo.convert("L").resize((64, 64), Image.Resampling.BILINEAR))
        vector = hog.compute(pixels).flatten()
        return vector / max(float(np.linalg.norm(vector)), 1e-9)

    probes = np.stack([feature(pictures[identity][indices[0]]) for identity, indices in captures])
    folder = TARGET / "face"
    folder.mkdir(exist_ok=True, parents=True)
    rows = []

    def emit(identity, person, number, role, index):
        filename = f"{identity}-{index}.jpg"
        data = pictures[person][number]
        (folder / filename).write_bytes(data)
        rows.append({"id": identity, "file": filename, "role": role, "group": "faces",
                     "source": f"{person}/{number}.jpg", "sha256": hashlib.sha256(data).hexdigest()})

    for index, (person, numbers) in enumerate(captures):
        for position, number in enumerate(numbers):
            emit(f"face-{index:02d}", person, number, "anchor" if position == 0 else "gallery", position)
    for index, person in enumerate(sorted(set(pictures) - set(queried))):
        pool = randomizer.sample(sorted(pictures[person]), min(16, len(pictures[person])))
        number = max(pool, key=lambda candidate: float(np.max(probes @ feature(pictures[person][candidate]))))
        emit(f"distractor-{index:02d}", person, number, "gallery", 0)
    write_manifest("face", rows)
    print(f"PubFig83: {len(rows)} photos, {len(captures)} queries, {len(pictures) - len(queried)} extra HOG-selected impostors", flush=True)


def object_sequences():
    sys.path.insert(0, str(SOURCE))
    sys.path.insert(0, str(SOURCE / "proto-tools"))
    from annotation_data_pb2 import Sequence

    output = TARGET / "object"
    output.mkdir(exist_ok=True, parents=True)
    rows = []

    def inspect(sequence):
        folder = SOURCE / "objectron" / sequence
        try:
            annotation_file = fetch(OBJECT_URL + f"annotations/{sequence}.pbdata", folder / "annotation.pbdata", 3_000_000)
            annotation = Sequence.FromString(annotation_file.read_bytes())
            if len(annotation.objects) != 1 or not annotation.frame_annotations:
                return sequence, []
            visible = [frame.frame_id for frame in annotation.frame_annotations
                       if len(frame.annotations) == 1 and any(
                           0 <= point.point_2d.x <= 1 and 0 <= point.point_2d.y <= 1
                           for point in frame.annotations[0].keypoints)]
            if len(visible) < 10:
                return sequence, []
            last = annotation.frame_annotations[-1].frame_id
            frames = [min(visible, key=lambda frame: abs(frame - round(last * fraction))) for fraction in FRAMES]
            if len(set(frames)) != len(FRAMES) or min(np.diff(frames)) < .12 * last:
                return sequence, []
            return sequence, frames
        except (HTTPError, TimeoutError, OSError, ValueError):
            return sequence, []

    def extract(item):
        sequence, frames = item
        target = SOURCE / "objectron" / sequence / "video.MOV"
        frame_images = []
        try:
            target = fetch(OBJECT_URL + f"videos/{sequence}/video.MOV", target, 80_000_000)
            video = cv2.VideoCapture(str(target))
            try:
                for frame in frames:
                    video.set(cv2.CAP_PROP_POS_FRAMES, frame)
                    success, raster = video.read()
                    if not success:
                        return sequence, []
                    image = Image.fromarray(cv2.cvtColor(raster, cv2.COLOR_BGR2RGB))
                    image.thumbnail((768, 768), Image.Resampling.LANCZOS)
                    stream = BytesIO()
                    image.save(stream, format="JPEG", quality=92)
                    frame_images.append((frame, stream.getvalue()))
            finally:
                video.release()
        except (HTTPError, TimeoutError, OSError, ValueError):
            return sequence, []
        return sequence, frame_images

    for offset, category in enumerate(CATEGORIES):
        index_file = fetch(OBJECT_URL + f"v1/index/{category}_annotations_test", SOURCE / f"{category}-test.txt", 30_000)
        index = sorted(set(index_file.read_text().split()))
        random.Random(SEED + offset).shuffle(index)
        chosen = 0
        with ThreadPoolExecutor(max_workers=8) as pool:
            for start in range(0, len(index), 16):
                viable = [(sequence, frames) for sequence, frames in pool.map(inspect, index[start:start + 16]) if frames]
                needed = OBJECTS_PER_CATEGORY - chosen
                for sequence, images in pool.map(extract, viable[:needed]):
                    if not images:
                        continue
                    identity = f"{category}-{chosen:02d}"
                    for position, (frame, data) in enumerate(images):
                        filename = f"{identity}-{position}.jpg"
                        (output / filename).write_bytes(data)
                        rows.append({"id": identity, "file": filename, "role": "anchor" if position == 0 else "gallery",
                                     "group": category, "source": sequence, "frame": frame,
                                     "sha256": hashlib.sha256(data).hexdigest()})
                    chosen += 1
                if chosen == OBJECTS_PER_CATEGORY:
                    break
        if chosen != OBJECTS_PER_CATEGORY:
            raise RuntimeError(f"Only {chosen} usable Objectron tracks in {category}")
        print(f"Objectron {category}: {chosen} tracks, {chosen * 3} frames", flush=True)
    write_manifest("object", rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("domain", choices=("face", "object"))
    args = parser.parse_args()
    (faces if args.domain == "face" else object_sequences)()
