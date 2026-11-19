import argparse
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import re
import time
import zipfile

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    balanced_accuracy_score,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from tokenizers import Tokenizer


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / ".benchmark-data" / "model-quality" / "visual-specialists-run-001"
RELATIONS = ROOT / ".benchmark-data" / "relations-v2"
RELATION_MANIFEST = RELATIONS / "manifest.json"
COIL_ARCHIVE = ROOT / "app" / "build" / "datasets" / "coil-100-legacy.zip"
CLIP = ROOT / ".local-models" / "clip"
DINO = ROOT / ".local-models" / "identity"
DINO_PREPROCESSOR = ROOT / "app" / "build" / "feasibility-models" / "dinov2-c2bb04a" / "preprocessor_config.json"
NEAR_CARS = (6, 8, 15, 23, 27, 69, 76, 91)
COIL_ANGLES = (0, 60, 120, 180, 240, 300)
POSITIVE_TARGETS = {
    "/m/09j2d": ("clothing", "a photo of clothing", "a photo with no clothing"),
    "/m/09j5n": ("footwear", "a photo of footwear", "a photo with no footwear"),
}


def sha256_file(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def sha256_bytes(payload):
    return hashlib.sha256(payload).hexdigest()


def normalize_rows(values):
    matrix = np.asarray(values, dtype=np.float32)
    lengths = np.linalg.norm(matrix, axis=1, keepdims=True)
    if not np.isfinite(matrix).all() or not np.isfinite(lengths).all() or np.any(lengths <= 0):
        raise ValueError("Encoder returned invalid features")
    return matrix / lengths


def cosine(left, right):
    first = np.asarray(left, dtype=np.float32)
    second = np.asarray(right, dtype=np.float32)
    return float(first @ second / (np.linalg.norm(first) * np.linalg.norm(second)))


def image_tensor(image, kind, settings):
    image = ImageOps.exif_transpose(image).convert("RGB")
    if kind == "clip":
        edge = int(settings["crop_size"]["width"])
        side = min(image.size)
        left = (image.width - side) // 2
        top = (image.height - side) // 2
        image = image.crop((left, top, left + side, top + side)).resize((edge, edge), Image.Resampling.BILINEAR)
    else:
        edge = int(settings["crop_size"]["width"])
        shortest = int(settings["size"]["shortest_edge"])
        factor = shortest / min(image.size)
        width, height = (int(value * factor) for value in image.size)
        image = image.resize((width, height), Image.Resampling(settings["resample"]))
        left, top = (width - edge) // 2, (height - edge) // 2
        image = image.crop((left, top, left + edge, top + edge))
    pixels = np.asarray(image, dtype=np.float32) * float(settings["rescale_factor"])
    pixels -= np.asarray(settings["image_mean"], dtype=np.float32)
    pixels /= np.asarray(settings["image_std"], dtype=np.float32)
    return pixels.transpose(2, 0, 1)


@dataclass
class Encoder:
    name: str
    session: object
    settings: dict
    input_name: str
    output_name: str
    kind: str
    model_sha256: str

    def encode(self, images, batch_size):
        outputs = []
        started = time.perf_counter()
        for offset in range(0, len(images), batch_size):
            batch = images[offset:offset + batch_size]
            pixels = np.stack([image_tensor(image, self.kind, self.settings) for image in batch]).astype(np.float32)
            encoded = self.session.run([self.output_name], {self.input_name: pixels})[0]
            if self.kind == "dino":
                encoded = encoded[:, 0, :]
            outputs.extend(encoded)
        duration = time.perf_counter() - started
        return normalize_rows(outputs), duration


def open_encoder(name, model_path, config_path, kind, threads):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])
    settings = json.loads(Path(config_path).read_text(encoding="utf-8"))
    return Encoder(name, session, settings, session.get_inputs()[0].name,
                   "image_embeds" if kind == "clip" else "last_hidden_state", kind,
                   sha256_file(model_path))


def verify_relation_images(manifest):
    paths = {}
    for row in manifest["images"]:
        path = RELATIONS / row["file"]
        if not path.is_file():
            raise FileNotFoundError(path)
        if sha256_file(path) != row["sha256"]:
            raise ValueError(f"Open Images fixture digest mismatch: {row['id']}")
        paths[row["id"]] = path
    return paths


def load_verified_pil(path, expected_sha256):
    payload = Path(path).read_bytes()
    if sha256_bytes(payload) != expected_sha256:
        raise ValueError(f"Fixture digest mismatch: {path}")
    with Image.open(io.BytesIO(payload)) as source:
        source.load()
        return ImageOps.exif_transpose(source).convert("RGB")


def feature_cache(cache_path, key, ids, encoder, image_source, batch_size):
    if cache_path.is_file():
        try:
            with np.load(cache_path, allow_pickle=False) as saved:
                if str(saved["key"].item()) == key and saved["ids"].tolist() == ids:
                    return saved["vectors"], 0.0, True
        except (OSError, ValueError, KeyError):
            pass
    parts = []
    duration = 0.0
    for offset in range(0, len(ids), batch_size):
        images = []
        try:
            for item_id in ids[offset:offset + batch_size]:
                images.append(image_source(item_id))
            vectors, elapsed = encoder.encode(images, len(images))
            parts.append(vectors)
            duration += elapsed
        finally:
            for image in images:
                image.close()
    vectors = np.concatenate(parts, axis=0)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = cache_path.with_suffix(".part.npz")
    np.savez(temporary, key=np.asarray(key), ids=np.asarray(ids), vectors=vectors)
    temporary.replace(cache_path)
    return vectors, duration, False


def extract_hue(image):
    import cv2

    pixels = np.asarray(ImageOps.exif_transpose(image).convert("RGB"), dtype=np.uint8)
    step = max(max(image.size) // 64, 1)
    pixels = pixels[::step, ::step]
    hsv = cv2.cvtColor(pixels, cv2.COLOR_RGB2HSV).astype(np.float32)
    hue = hsv[:, :, 0].reshape(-1) * 2.0
    saturation = hsv[:, :, 1].reshape(-1) / 255.0
    value = hsv[:, :, 2].reshape(-1) / 255.0
    strength = np.maximum(saturation - .22, 0) * np.maximum(value - .15, 0)
    position = hue * 18 / 360
    bins = np.floor(position).astype(np.int32)
    fraction = position - bins
    histogram = np.zeros(18, dtype=np.float32)
    np.add.at(histogram, bins % 18, strength * (1 - fraction))
    np.add.at(histogram, (bins + 1) % 18, strength * fraction)
    if strength.sum() < strength.size * .01:
        return None
    norm = np.linalg.norm(histogram)
    return histogram / norm if norm else None


def fit_metric_head(features, identities, dimensions, ridge):
    features = np.asarray(features, dtype=np.float64)
    labels = np.asarray(identities)
    overall = features.mean(axis=0)
    within = np.zeros((features.shape[1], features.shape[1]), dtype=np.float64)
    between = np.zeros_like(within)
    for identity in np.unique(labels):
        group = features[labels == identity]
        center = group.mean(axis=0)
        residual = group - center
        within += residual.T @ residual
        delta = center - overall
        between += len(group) * np.outer(delta, delta)
    within /= max(len(features) - len(np.unique(labels)), 1)
    between /= max(len(features), 1)
    scale = max(float(np.trace(within) / within.shape[0]), 1e-10)
    regularized = within + ridge * scale * np.eye(within.shape[0])
    lower = np.linalg.cholesky(regularized)
    transformed = np.linalg.solve(lower, between)
    whitened = np.linalg.solve(lower, transformed.T).T
    whitened = (whitened + whitened.T) * .5
    values, vectors = np.linalg.eigh(whitened)
    count = min(dimensions, len(np.unique(labels)) - 1, features.shape[1])
    basis = np.linalg.solve(lower.T, vectors[:, np.argsort(values)[-count:]])
    return overall.astype(np.float32), basis.astype(np.float32)


def apply_metric_head(features, center, basis):
    projected = (np.asarray(features, dtype=np.float32) - center) @ basis
    return normalize_rows(projected)


def ranking_metrics(scores, targets, gallery_ids):
    orders = np.argsort(-scores, axis=1, kind="stable")
    rank_positions = {identity: index for index, identity in enumerate(gallery_ids)}
    correct = np.asarray([gallery_ids[int(orders[i, 0])] == targets[i] for i in range(len(targets))])
    hit5 = np.asarray([targets[i] in [gallery_ids[int(j)] for j in orders[i, :5]] for i in range(len(targets))])
    target_scores = np.asarray([scores[i, rank_positions[target]] for i, target in enumerate(targets)])
    impostor_scores = np.asarray([np.max(np.delete(scores[i], rank_positions[target])) for i, target in enumerate(targets)])
    return {
        "queries": len(targets),
        "top1_correct": int(correct.sum()),
        "top1_accuracy": float(correct.mean()),
        "hit_at_5": int(hit5.sum()),
        "hit_at_5_rate": float(hit5.mean()),
        "target_similarity_mean": float(target_scores.mean()),
        "impostor_similarity_mean": float(impostor_scores.mean()),
        "target_margin_mean": float((target_scores - impostor_scores).mean()),
    }


def query_scores(features, ids, angle_to_identity, query_identities, angles):
    vectors = {}
    targets = []
    for item_id, vector in zip(ids, features, strict=True):
        identity, angle = angle_to_identity[item_id]
        if identity in query_identities and angle in angles:
            vectors[(identity, angle)] = vector
    for identity in query_identities:
        for angle in angles:
            item_id = next(item_id for item_id in ids if angle_to_identity[item_id] == (identity, angle))
            targets.append((identity, angle, item_id, vectors[(identity, angle)]))
    return targets


def rank_queries(features, ids, angle_to_identity, gallery_identities, query_identities, reference_angles, query_angles,
                 color_vectors=None):
    queries = query_scores(features, ids, angle_to_identity, query_identities, query_angles)
    gallery_order = sorted(gallery_identities)
    scores = np.zeros((len(queries), len(gallery_order)), dtype=np.float32)
    targets = []
    groups = []
    item_ids = {(identity, angle): item_id for item_id, (identity, angle) in angle_to_identity.items()}
    vector_by_item = dict(zip(ids, features, strict=True))
    for row_index, (identity, angle, query_id, vector) in enumerate(queries):
        targets.append(identity)
        groups.append(identity)
        for col_index, candidate in enumerate(gallery_order):
            reference_scores = []
            for reference_angle in reference_angles:
                reference_id = item_ids[(candidate, reference_angle)]
                visual = float(vector @ vector_by_item[reference_id])
                if color_vectors is not None:
                    query_color = color_vectors.get(query_id)
                    reference_color = color_vectors.get(reference_id)
                    if query_color is not None and reference_color is not None:
                        visual = .85 * visual + .15 * float(query_color @ reference_color)
                reference_scores.append(visual)
            scores[row_index, col_index] = max(reference_scores)
    return scores, targets, groups, gallery_order


def max_impostor_calibration(features, ids, angle_to_identity, calibration_ids, reference_angles, query_angles,
                             color_vectors=None):
    gallery_ids = sorted(calibration_ids)
    scores, _, _, gallery_order = rank_queries(
        features, ids, angle_to_identity, gallery_ids, calibration_ids, reference_angles, query_angles, color_vectors)
    maxima = []
    for row_index, identity in enumerate([identity for identity in calibration_ids for _ in query_angles]):
        maxima.append(float(np.max(np.delete(scores[row_index], gallery_order.index(identity)))))
    return np.asarray(maxima, dtype=np.float32)


def summarize_open_set(features, ids, angle_to_identity, test_known, test_unknown, calibration_ids,
                       reference_angles, query_angles, color_vectors=None):
    known_scores, known_targets, _, gallery_ids = rank_queries(
        features, ids, angle_to_identity, test_known, test_known, reference_angles, query_angles, color_vectors)
    absent_scores, _, _, _ = rank_queries(
        features, ids, angle_to_identity, test_known, test_unknown, reference_angles, query_angles, color_vectors)
    calibration = max_impostor_calibration(
        features, ids, angle_to_identity, calibration_ids, reference_angles, query_angles, color_vectors)
    threshold = float(np.quantile(calibration, .99, method="higher"))
    known_max = known_scores.max(axis=1)
    known_pred = np.asarray([gallery_ids[int(index)] for index in known_scores.argmax(axis=1)])
    known_targets_arr = np.asarray(known_targets)
    accepted_known = known_max >= threshold
    accepted_unknown = absent_scores.max(axis=1) >= threshold
    return {
        "threshold": threshold,
        "threshold_rule": "99th percentile with higher interpolation of calibration-query maximum impostor similarity; calibrated on separate identities only",
        "calibration_unknown_comparisons": int(calibration.size),
        "known_query_count": int(len(known_targets_arr)),
        "known_top1": int((known_pred == known_targets_arr).sum()),
        "known_accepted": int(accepted_known.sum()),
        "known_correctly_accepted": int(((known_pred == known_targets_arr) & accepted_known).sum()),
        "known_false_accepts": int(((known_pred != known_targets_arr) & accepted_known).sum()),
        "known_false_rejects": int((~accepted_known).sum()),
        "unknown_query_count": int(len(absent_scores)),
        "unknown_false_accepts": int(accepted_unknown.sum()),
        "unknown_false_accept_rate": float(accepted_unknown.mean()),
        "unknown_max_similarity_mean": float(absent_scores.max(axis=1).mean()),
    }


def fit_object_head(features, ids, angle_to_identity, train_ids, tune_ids):
    train_rows = [i for i, item_id in enumerate(ids) if angle_to_identity[item_id][0] in train_ids]
    tune_rows = [i for i, item_id in enumerate(ids) if angle_to_identity[item_id][0] in tune_ids]
    best = None
    for ridge in (.01, .05, .2, 1.0):
        for dimensions in (8, 16, 24):
            center, basis = fit_metric_head(features[train_rows],
                                            [angle_to_identity[ids[i]][0] for i in train_rows],
                                            dimensions, ridge)
            projected = apply_metric_head(features, center, basis)
            scores, targets, _, gallery = rank_queries(
                projected, ids, angle_to_identity, tune_ids, tune_ids, (0,), (60, 180, 300))
            accuracy = float(np.mean([gallery[index] == targets[row] for row, index in enumerate(scores.argmax(axis=1))]))
            candidate = (accuracy, -ridge, -dimensions, ridge, dimensions, center, basis)
            if best is None or candidate[:3] > best[:3]:
                best = candidate
    return {"tune_top1_accuracy": best[0], "ridge": best[3], "dimensions": best[4],
            "center": best[5], "basis": best[6]}


def run_object_experiment(clip_vectors, dino_vectors, coil_ids, angle_to_identity, color_vectors, split, report,
                         head_parameters):
    train_ids = split["train"]
    tune_ids = split["tune"]
    calibration_ids = split["calibration"]
    test_known = split["test_known"]
    test_unknown = split["test_unknown"]
    dino_head = fit_object_head(dino_vectors, coil_ids, angle_to_identity, train_ids, tune_ids)
    clip_head = fit_object_head(clip_vectors, coil_ids, angle_to_identity, train_ids, tune_ids)
    head_parameters["coil_dinov2_lda_center"] = dino_head["center"]
    head_parameters["coil_dinov2_lda_basis"] = dino_head["basis"]
    head_parameters["coil_clip_lda_center"] = clip_head["center"]
    head_parameters["coil_clip_lda_basis"] = clip_head["basis"]
    dino_projected = apply_metric_head(dino_vectors, dino_head["center"], dino_head["basis"])
    clip_projected = apply_metric_head(clip_vectors, clip_head["center"], clip_head["basis"])
    models = {
        "clip_base": (clip_vectors, None),
        "clip_lda": (clip_projected, clip_head),
        "dinov2_base": (dino_vectors, None),
        "dinov2_lda": (dino_projected, dino_head),
        "dinov2_app_hue_policy": (dino_vectors, None),
    }
    results = {}
    for model_name, (features, head) in models.items():
        for reference_angles in ((0,), (0, 120, 240)):
            score, targets, _, gallery_order = rank_queries(
                features, coil_ids, angle_to_identity, test_known, test_known, reference_angles, (60, 180, 300),
                color_vectors if model_name == "dinov2_app_hue_policy" else None)
            key = "one_reference" if len(reference_angles) == 1 else "three_references"
            ranked = ranking_metrics(score, targets, gallery_order)
            near_rows = [index for index, identity in enumerate(targets) if identity in NEAR_CARS]
            if near_rows:
                ranked["near_car_queries"] = len(near_rows)
                ranked["near_car_top1_correct"] = int(sum(gallery_order[int(score[index].argmax())] == targets[index]
                                                           for index in near_rows))
                ranked["near_car_top1_accuracy"] = ranked["near_car_top1_correct"] / len(near_rows)
            ranked["tune_top1_accuracy"] = head["tune_top1_accuracy"] if head else None
            ranked["learned_head"] = head is not None
            ranked["open_set"] = summarize_open_set(
                features, coil_ids, angle_to_identity, test_known, test_unknown, calibration_ids,
                reference_angles, (60, 180, 300),
                color_vectors if model_name == "dinov2_app_hue_policy" else None)
            results.setdefault(model_name, {})[key] = ranked
    report["exact_object"] = {
        "dataset": "COIL-100, six selected views per identity; 100 distinct object IDs",
        "archive_sha256": report["dataset_sources"]["coil_archive_sha256"],
        "split_policy": "Identity-disjoint; eight similar toy-car IDs forced into known-object test; train, tune, calibration, known test, and absent-query test identities never overlap.",
        "identity_splits": {name: sorted(values) for name, values in split.items()},
        "train_identities": len(train_ids),
        "tune_identities": len(tune_ids),
        "calibration_identities": len(calibration_ids),
        "known_test_identities": len(test_known),
        "unknown_test_identities": len(test_unknown),
        "similar_toy_car_ids": list(NEAR_CARS),
        "reference_angles": [[0], [0, 120, 240]],
        "query_angles": [60, 180, 300],
        "head_training": "Frozen image encoder plus regularized supervised linear discriminant projection fitted on train identities; ridge and output dimension selected only on tune identities. Encoder weights remain frozen.",
        "results": results,
    }


def label_status(row, label):
    if label in row.get("positive_labels", []):
        return 1
    if label in row.get("negative_labels", []):
        return 0
    return None


def split_bucket(row):
    digest = hashlib.sha256(("clothing-openimages-split-v1:" + row["sha256"]).encode("utf-8")).hexdigest()
    value = int(digest[:8], 16) % 100
    return "train" if value < 70 else "development" if value < 85 else "test"


def binary_metrics(targets, scores, threshold):
    predictions = scores >= threshold
    return {
        "count": len(targets),
        "positive_count": int(np.sum(targets == 1)),
        "negative_count": int(np.sum(targets == 0)),
        "threshold": float(threshold),
        "balanced_accuracy": float(balanced_accuracy_score(targets, predictions)),
        "precision": float(precision_score(targets, predictions, zero_division=0)),
        "recall": float(recall_score(targets, predictions, zero_division=0)),
        "f1": float(f1_score(targets, predictions, zero_division=0)),
        "roc_auc": float(roc_auc_score(targets, scores)),
        "average_precision": float(average_precision_score(targets, scores)),
        "true_positive": int(np.sum((targets == 1) & predictions)),
        "false_positive": int(np.sum((targets == 0) & predictions)),
        "true_negative": int(np.sum((targets == 0) & ~predictions)),
        "false_negative": int(np.sum((targets == 1) & ~predictions)),
    }


def best_balanced_threshold(targets, scores):
    candidates = np.unique(np.concatenate((scores, np.asarray([0.0, 1.0]))))
    ranked = [(balanced_accuracy_score(targets, scores >= threshold), -abs(float(threshold) - .5), float(threshold))
              for threshold in candidates]
    return max(ranked)[2]


def encode_text_prompts(encoder, tokenizer_path, prompts):
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    values = []
    for prompt in prompts:
        ids = tokenizer.encode(prompt).ids
        if len(ids) > 77:
            ids = ids[:76] + [tokenizer.token_to_id("<|endoftext|>")]
        tensor = np.asarray([ids], dtype=np.int64)
        values.append(encoder.run(["text_embeds"], {"input_ids": tensor})[0][0])
    return normalize_rows(values)


def evaluate_classifier(train_x, train_y, dev_x, dev_y, test_x, test_y):
    candidates = []
    for penalty in (0.01, .1, 1.0, 10.0):
        model = LogisticRegression(C=penalty, class_weight="balanced", max_iter=1000, solver="liblinear", random_state=641)
        model.fit(train_x, train_y)
        dev_scores = model.decision_function(dev_x)
        ap = float(average_precision_score(dev_y, dev_scores))
        candidates.append((ap, -penalty, penalty, model))
    _, _, penalty, model = max(candidates, key=lambda value: value[:2])
    dev_scores = model.decision_function(dev_x)
    threshold = best_balanced_threshold(dev_y, dev_scores)
    return model, {"selected_C": penalty, "threshold_selected_on": "development only",
                   "development": binary_metrics(dev_y, dev_scores, threshold),
                   "test": binary_metrics(test_y, model.decision_function(test_x), threshold)}


def run_clothing_experiment(manifest, clip_vectors, dino_vectors, float_text_encoder, report, head_parameters):
    rows = manifest["images"]
    id_positions = {row["id"]: index for index, row in enumerate(rows)}
    splits = {name: [row for row in rows if split_bucket(row) == name]
              for name in ("train", "development", "test")}
    unknown_rows = {label: [row for row in rows if label_status(row, label) is None] for label in POSITIVE_TARGETS}
    results = {}
    for label, (name, positive_prompt, negative_prompt) in POSITIVE_TARGETS.items():
        category = {}
        labeled = {split: [row for row in splits[split] if label_status(row, label) is not None]
                   for split in splits}
        counts = {split: {"positive": sum(label_status(row, label) == 1 for row in values),
                          "explicit_negative": sum(label_status(row, label) == 0 for row in values),
                          "unknown_excluded": sum(split_bucket(row) == split for row in unknown_rows[label])}
                  for split, values in labeled.items()}
        for split in ("train", "development", "test"):
            if min(counts[split]["positive"], counts[split]["explicit_negative"]) < 8:
                raise ValueError(f"Not enough explicit labels for {name} in {split}: {counts[split]}")
        split_x = {split: np.stack([clip_vectors[id_positions[row["id"]]] for row in values])
                   for split, values in labeled.items()}
        split_y = {split: np.asarray([label_status(row, label) for row in values], dtype=np.int64)
                   for split, values in labeled.items()}
        for encoder_name, matrices in (("clip", clip_vectors), ("dinov2", dino_vectors)):
            model, scores = evaluate_classifier(
                np.stack([matrices[id_positions[row["id"]]] for row in labeled["train"]]), split_y["train"],
                np.stack([matrices[id_positions[row["id"]]] for row in labeled["development"]]), split_y["development"],
                np.stack([matrices[id_positions[row["id"]]] for row in labeled["test"]]), split_y["test"])
            head_parameters[f"clothing_{name}_{encoder_name}_coef"] = model.coef_.astype(np.float32)
            head_parameters[f"clothing_{name}_{encoder_name}_intercept"] = model.intercept_.astype(np.float32)
            category[encoder_name + "_linear_head"] = scores
        text = encode_text_prompts(float_text_encoder, CLIP / "tokenizer.json", [positive_prompt, negative_prompt])
        positive_scores = {}
        margin_scores = {}
        image_vectors = {split: np.stack([clip_vectors[id_positions[row["id"]]] for row in labeled[split]])
                         for split in labeled}
        for split in ("train", "development", "test"):
            positive_scores[split] = image_vectors[split] @ text[0]
            margin_scores[split] = image_vectors[split] @ (text[0] - text[1])
        positive_threshold = best_balanced_threshold(split_y["development"], positive_scores["development"])
        forward_ap = average_precision_score(split_y["development"], margin_scores["development"])
        reverse_ap = average_precision_score(split_y["development"], -margin_scores["development"])
        margin_direction = 1.0 if forward_ap >= reverse_ap else -1.0
        for split in margin_scores:
            margin_scores[split] *= margin_direction
        margin_threshold = best_balanced_threshold(split_y["development"], margin_scores["development"])
        category["clip_zero_shot_text_margin"] = {
            "positive_prompt": positive_prompt,
            "negative_prompt": negative_prompt,
            "score_orientation": "positive_prompt_minus_negative_prompt" if margin_direction > 0 else "negative_prompt_minus_positive_prompt",
            "threshold_selected_on": "development only",
            "orientation_selected_on": "development average precision only",
            "development": binary_metrics(split_y["development"], margin_scores["development"], margin_threshold),
            "test": binary_metrics(split_y["test"], margin_scores["test"], margin_threshold),
        }
        category["clip_zero_shot_positive_prompt"] = {
            "positive_prompt": positive_prompt,
            "threshold_selected_on": "development only",
            "development": binary_metrics(split_y["development"], positive_scores["development"], positive_threshold),
            "test": binary_metrics(split_y["test"], positive_scores["test"], positive_threshold),
        }
        results[name] = {"label_id": label, "split_counts": counts,
                         "positive_plus_explicit_negative_total": sum(len(values) for values in labeled.values()),
                         "head_results": category}
    report["clothing_categories"] = {
        "dataset": "Open Images v6 annotation subset, 2,000 images; image-disjoint deterministic 70/15/15 split.",
        "split_policy": "SHA-256 of each unique source-image SHA-256 with fixed split namespace; train, development and test share no source-image hashes. Only explicit positive and explicit-negative annotations are scored or used; all unannotated labels remain unknown and are excluded.",
        "annotation_license": manifest.get("annotation_license"),
        "source_status": manifest.get("status"),
        "scope_limit": "This is broad image-level clothing/footwear presence classification; the labels do not identify a specific garment instance, color attribute, person association or exact repeated item.",
        "results": results,
    }


def make_splits(seed=28403):
    near = set(NEAR_CARS)
    remainder = sorted(set(range(1, 101)) - near, key=lambda item: hashlib.sha256(f"coil-visual-specialist-{seed}:{item}".encode()).digest())
    train = remainder[:25]
    tune = remainder[25:35]
    calibration = remainder[35:45]
    test_known = sorted(near | set(remainder[45:67]))
    test_unknown = remainder[67:]
    groups = {"train": train, "tune": tune, "calibration": calibration,
              "test_known": test_known, "test_unknown": test_unknown}
    flattened = [identity for values in groups.values() for identity in values]
    if len(flattened) != 100 or len(set(flattened)) != 100 or len(test_unknown) != 25 or len(test_known) != 30:
        raise ValueError("COIL identity split is incomplete or overlapping")
    return groups


def load_coil_payloads(archive_path):
    payloads = {}
    angle_to_identity = {}
    pattern = re.compile(r"(?:^|/)obj(\d+)__(\d+)\.png$")
    with zipfile.ZipFile(archive_path) as archive:
        for member in archive.namelist():
            match = pattern.search(member.replace("\\", "/"))
            if not match:
                continue
            identity, angle = map(int, match.groups())
            if angle not in COIL_ANGLES:
                continue
            item_id = f"coil-{identity:03d}-{angle:03d}"
            payloads[item_id] = archive.read(member)
            angle_to_identity[item_id] = (identity, angle)
    expected = {(identity, angle) for identity in range(1, 101) for angle in COIL_ANGLES}
    actual = {angle_to_identity[item_id] for item_id in payloads}
    if actual != expected or len(payloads) != len(expected):
        raise ValueError(f"COIL views differ from protocol: expected {len(expected)} and found {len(payloads)}")
    return payloads, angle_to_identity


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.batch_size < 1 or args.threads < 1:
        raise ValueError("Batch size and thread count must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(RELATION_MANIFEST.read_text(encoding="utf-8"))
    manifest_sha = sha256_file(RELATION_MANIFEST)
    relation_paths = verify_relation_images(manifest)
    coil_sha = sha256_file(COIL_ARCHIVE)
    clip_model_path = CLIP / "vision_model_quantized.onnx"
    dino_model_path = DINO / "dinov2.onnx"
    clip_settings_path = CLIP / "preprocessor_config.json"
    dino_settings_path = DINO_PREPROCESSOR
    clip = open_encoder("app_clip_image", clip_model_path, clip_settings_path, "clip", args.threads)
    dino = open_encoder("app_dinov2_identity", dino_model_path, dino_settings_path, "dino", args.threads)
    text_options = ort.SessionOptions()
    text_options.intra_op_num_threads = min(args.threads, 2)
    text_options.inter_op_num_threads = 1
    float_text = ort.InferenceSession(
        str(ROOT / ".benchmark-data" / "hf-cache" / "hub" / "models--Xenova--clip-vit-base-patch32" /
            "snapshots" / "6b7c523703ae26e39be4b03774966f8571c7f523" / "onnx" / "text_model.onnx"),
        text_options, providers=["CPUExecutionProvider"])
    clip.model_sha256 = sha256_file(CLIP / "vision_model_quantized.onnx")
    cache_salt = {"manifest_sha256": manifest_sha, "coil_archive_sha256": coil_sha,
                  "clip_sha256": clip.model_sha256, "dino_sha256": dino.model_sha256,
                  "preprocess_version": "center-square-clip-v1,dinov2-processor-v1"}
    clip_cache_key = sha256_bytes(json.dumps({**cache_salt, "encoder": "clip"}, sort_keys=True).encode("utf-8"))
    dino_cache_key = sha256_bytes(json.dumps({**cache_salt, "encoder": "dino"}, sort_keys=True).encode("utf-8"))
    image_ids = [row["id"] for row in manifest["images"]]
    coil_payloads, coil_mapping = load_coil_payloads(COIL_ARCHIVE)
    coil_ids = sorted(coil_payloads)
    coil_clip_key = sha256_bytes(json.dumps({**cache_salt, "coil_ids": coil_ids, "encoder": "clip"}, sort_keys=True).encode("utf-8"))
    coil_dino_key = sha256_bytes(json.dumps({**cache_salt, "coil_ids": coil_ids, "encoder": "dino"}, sort_keys=True).encode("utf-8"))
    relation_rows = {row["id"]: row for row in manifest["images"]}
    def relation_source(item_id):
        row = relation_rows[item_id]
        return load_verified_pil(relation_paths[item_id], row["sha256"])
    def coil_source(item_id):
        with Image.open(io.BytesIO(coil_payloads[item_id])) as image:
            image.load()
            return ImageOps.exif_transpose(image).convert("RGB")
    if args.force:
        for name in ("clothing_clip.npz", "clothing_dino.npz", "coil_clip.npz", "coil_dino.npz"):
            (args.output / name).unlink(missing_ok=True)
    timings = {}
    started = time.perf_counter()
    clothing_clip, timing, cached = feature_cache(args.output / "clothing_clip.npz", clip_cache_key, image_ids,
                                                   clip, relation_source, args.batch_size)
    timings["clothing_clip_seconds"] = timing
    timings["clothing_clip_cache_hit"] = cached
    clothing_dino, timing, cached = feature_cache(args.output / "clothing_dino.npz", dino_cache_key, image_ids,
                                                   dino, relation_source, args.batch_size)
    timings["clothing_dino_seconds"] = timing
    timings["clothing_dino_cache_hit"] = cached
    coil_clip, timing, cached = feature_cache(args.output / "coil_clip.npz", coil_clip_key, coil_ids,
                                               clip, coil_source, args.batch_size)
    timings["coil_clip_seconds"] = timing
    timings["coil_clip_cache_hit"] = cached
    coil_dino, timing, cached = feature_cache(args.output / "coil_dino.npz", coil_dino_key, coil_ids,
                                               dino, coil_source, args.batch_size)
    timings["coil_dino_seconds"] = timing
    timings["coil_dino_cache_hit"] = cached
    color_vectors = {}
    for item_id in coil_ids:
        with Image.open(io.BytesIO(coil_payloads[item_id])) as image:
            image.load()
            color_vectors[item_id] = extract_hue(image)
    report = {
        "schema_version": 1,
        "execution": {"host": "local CPU", "onnxruntime": ort.__version__, "threads": args.threads,
                      "batch_size": args.batch_size, "wall_seconds_features": time.perf_counter() - started},
        "dataset_sources": {
            "openimages_manifest": str(RELATION_MANIFEST.relative_to(ROOT)),
            "openimages_manifest_sha256": manifest_sha,
            "openimages_image_count": len(image_ids),
            "openimages_license": manifest.get("annotation_license"),
            "openimages_overlap_with_existing_10k_set": 1987,
            "coil_archive": str(COIL_ARCHIVE.relative_to(ROOT)),
            "coil_archive_sha256": coil_sha,
            "coil_selected_view_count": len(coil_ids),
            "clip_image_encoder_sha256": clip.model_sha256,
            "dino_encoder_sha256": dino.model_sha256,
            "openimages_images_rehashed_before_inference": True,
        },
        "feature_extraction_timings_seconds": timings,
        "data_use": "Image files and labels remain in ignored benchmark data. Output stores feature caches, trained head weights, split IDs and aggregate metrics only.",
    }
    head_parameters = {}
    split = make_splits()
    run_object_experiment(clip_vectors=coil_clip, dino_vectors=coil_dino, coil_ids=coil_ids,
                          angle_to_identity=coil_mapping, color_vectors=color_vectors, split=split, report=report,
                          head_parameters=head_parameters)
    run_clothing_experiment(manifest, clothing_clip, clothing_dino, float_text, report, head_parameters)
    np.savez_compressed(args.output / "heads.npz", **head_parameters)
    (args.output / "results.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
