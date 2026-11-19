import argparse
import hashlib
import io
import json
import re
import statistics
import time
import zipfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "app" / "build" / "datasets"
REFERENCE_ANGLES = (0, 120, 240)
PROBE_ANGLES = (60, 180, 300)
ANGLES = REFERENCE_ANGLES + PROBE_ANGLES
DATASET = "https://www.cs.columbia.edu/CAVE/software/softlib/coil-100.php"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_photos(archive):
    records = {}
    with zipfile.ZipFile(archive) as source:
        for name in source.namelist():
            match = re.fullmatch(r"obj(\d+)__(\d+)\.png", Path(name).name)
            if match:
                identity, angle = map(int, match.groups())
                if angle in ANGLES:
                    with Image.open(io.BytesIO(source.read(name))) as photo:
                        records[identity, angle] = photo.convert("RGB")
    expected = {(identity, angle) for identity in range(1, 101) for angle in ANGLES}
    if records.keys() != expected:
        raise ValueError(f"COIL-100 views differ from the protocol: missing {len(expected - records.keys())}")
    return records


def preprocessing(image, settings, mode):
    edge = settings["crop_size"]["width"]
    if mode == "clip":
        side = min(image.size)
        left, top = (image.width - side) // 2, (image.height - side) // 2
        image = image.crop((left, top, left + side, top + side)).resize((edge, edge), Image.Resampling.BILINEAR)
    else:
        shortest = settings["size"]["shortest_edge"]
        factor = shortest / min(image.size)
        width, height = (int(value * factor) for value in image.size)
        image = image.resize((width, height), settings["resample"])
        x, y = (width - edge) // 2, (height - edge) // 2
        image = image.crop((x, y, x + edge, y + edge))
    values = np.asarray(image, dtype=np.float32) * settings["rescale_factor"]
    values -= np.asarray(settings["image_mean"], dtype=np.float32)
    values /= np.asarray(settings["image_std"], dtype=np.float32)
    return values.transpose(2, 0, 1)


def embeddings(images, weights, config, mode):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(weights), options, providers=["CPUExecutionProvider"])
    settings = json.loads(config.read_text(encoding="utf-8"))
    ordered = sorted(images)
    output = "image_embeds" if mode == "clip" else "last_hidden_state"
    values = []
    start = time.perf_counter()
    batch_times = []
    for offset in range(0, len(ordered), 6):
        keys = ordered[offset:offset + 6]
        pixels = np.stack([preprocessing(images[key], settings, mode) for key in keys])
        begin = time.perf_counter()
        vectors = session.run([output], {"pixel_values": pixels})[0]
        batch_times.append((time.perf_counter() - begin) / len(keys) * 1000)
        if mode != "clip":
            vectors = vectors[:, 0, :]
        values.extend(vectors / np.linalg.norm(vectors, axis=1, keepdims=True))
    elapsed = time.perf_counter() - start
    if not np.isfinite(values).all():
        raise ValueError(f"{mode} produced invalid embeddings")
    return dict(zip(ordered, values)), {"batch_inference_ms_per_image_median": round(statistics.median(batch_times), 3),
                                        "total_preprocess_inference_s": round(elapsed, 3)}


def separation(vectors, identities, angles):
    references = np.stack([[vectors[i, angle] for angle in angles] for i in identities])
    probes = np.stack([[vectors[i, angle] for angle in PROBE_ANGLES] for i in identities])
    dots = np.einsum("ipd,jrd->ipjr", probes, references).max(axis=-1)
    targets = np.eye(len(identities), dtype=bool)[:, None, :].repeat(len(PROBE_ANGLES), axis=1)
    return dots, targets


def measure(scores, mask, threshold):
    accepted = scores > threshold
    true_scores = scores[mask]
    false_scores = scores[~mask]
    top_correct = (scores.argmax(axis=2) == np.arange(scores.shape[0])[:, None])
    return {
        "top1_correct": int(top_correct.sum()), "probe_count": int(top_correct.size),
        "opposite_view_top1_correct": int(top_correct[:, PROBE_ANGLES.index(180)].sum()),
        "opposite_view_probe_count": int(top_correct.shape[0]),
        "correct_accepts": int((accepted & mask).sum()), "genuine_pairs": int(true_scores.size),
        "false_accepts": int((accepted & ~mask).sum()), "impostor_pairs": int(false_scores.size),
        "false_accepts_by_probe": int((accepted & ~mask).any(axis=2).sum()),
    }


def evaluate(vectors, development, held_out, near):
    results = {}
    for name, angles in (("one_reference", REFERENCE_ANGLES[:1]), ("three_references", REFERENCE_ANGLES)):
        reference_scores, reference_labels = separation(vectors, development, angles)
        threshold = float(np.quantile(reference_scores[~reference_labels], .99, method="higher"))
        scores, labels = separation(vectors, held_out, angles)
        results[name] = {"threshold": threshold, "development": measure(reference_scores, reference_labels, threshold),
                         "held_out": measure(scores, labels, threshold)}
        if near:
            near_scores, near_labels = separation(vectors, near, angles)
            results[name]["similar_toy_vehicles"] = measure(near_scores, near_labels, threshold)
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, default=DATA / "coil-100.zip")
    parser.add_argument("--dino", type=Path)
    parser.add_argument("--near", nargs="+", type=int)
    args = parser.parse_args()
    near = sorted(set(args.near or []))
    others = sorted(set(range(1, 101)) - set(near), key=lambda i: hashlib.sha256(f"coil-id-v1:{i}".encode()).digest())
    development = sorted(others[:35])
    held_out = sorted(set(range(1, 101)) - set(development))
    photos = load_photos(args.archive)
    clip = ROOT / ".local-models" / "clip"
    models = [("clip", clip / "vision_model_quantized.onnx", clip / "preprocessor_config.json")]
    if args.dino:
        models.append(("dinov2", args.dino / "model_quantized.onnx", args.dino / "preprocessor_config.json"))
    report = {
        "dataset": DATASET, "archive_sha256": sha(args.archive), "runtime": "ONNX Runtime " + ort.__version__ + " desktop CPU",
        "image_count": len(photos), "reference_angles": REFERENCE_ANGLES, "probe_angles": PROBE_ANGLES,
        "development_object_ids": development, "held_out_object_ids": held_out, "similar_toy_vehicle_ids": near,
        "threshold_rule": "Accept above the 99th percentile of development impostor scores",
        "results": {},
    }
    for mode, weights, config in models:
        vectors, timing = embeddings(photos, weights, config, mode)
        report["results"][mode] = {"weights_sha256": sha(weights), "preprocessor_sha256": sha(config),
                                   "timing": timing, **evaluate(vectors, development, held_out, near)}
        print(mode, json.dumps(report["results"][mode], indent=2), flush=True)
    path = DATA / "local-object-identity-results.json"
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Report: {path}", flush=True)


if __name__ == "__main__":
    main()
