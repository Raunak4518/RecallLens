import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import tarfile
import time

import cv2
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


SOURCE = "https://vision.seas.harvard.edu/pubfig83/"
SFACE_SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
SEED = 17291


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sample(archive_path, errata_path, per_identity):
    excluded = set()
    for line in errata_path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(.+?) (\d+(?:,\s*\d+)*)", line.strip())
        if match:
            excluded.update((match[1], int(index)) for index in re.findall(r"\d+", match[2]))
    by_identity = defaultdict(list)
    ownership = defaultdict(set)
    with tarfile.open(archive_path, "r|gz") as archive:
        for member in archive:
            parts = member.name.split("/")
            if len(parts) != 3 or not member.isfile() or not re.fullmatch(r"\d+\.jpg", parts[-1]):
                continue
            if not 0 < member.size <= 2_000_000:
                raise ValueError("Unexpected PubFig image size")
            person, number = parts[1], int(parts[-1][:-4])
            payload = archive.extractfile(member).read()
            digest = hashlib.sha256(payload).digest()
            ownership[digest].add(person)
            if (person, number) not in excluded:
                priority = hashlib.sha256(f"{SEED}/{person}/{number}".encode()).digest()
                by_identity[person].append((priority, digest, payload))
    selected = {}
    for identity, rows in by_identity.items():
        seen = set()
        unique = []
        for _, digest, payload in sorted(rows):
            if digest in seen or len(ownership[digest]) != 1:
                continue
            seen.add(digest)
            unique.append(payload)
            if len(unique) == per_identity:
                break
        selected[identity] = unique
    if len(selected) != 83 or any(len(rows) != per_identity for rows in selected.values()):
        raise ValueError("Archive does not match the expected PubFig83 identity count or sample size")
    return selected


def face_feature(payload, detector, encoder):
    image = cv2.imdecode(np.frombuffer(payload, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    detector.setInputSize((width, height))
    _, boxes = detector.detect(image)
    if boxes is None or len(boxes) != 1:
        return None
    face = boxes[0]
    aligned = encoder.alignCrop(image, face)
    feature = encoder.feature(aligned).ravel()
    norm = np.linalg.norm(feature)
    if not np.isfinite(norm) or norm <= 0:
        return None
    points = face[4:14].reshape(5, 2)
    eye_span = float(np.linalg.norm(points[0] - points[1]))
    left = float(np.linalg.norm(points[2] - points[0]))
    right = float(np.linalg.norm(points[2] - points[1]))
    gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
    quality = np.array([
        face[-1],
        eye_span / min(width, height),
        abs(left - right) / max(left + right, 1e-4),
        np.log1p(cv2.Laplacian(gray, cv2.CV_32F).var()),
    ], dtype=np.float32)
    if not np.isfinite(quality).all():
        return None
    return feature / norm, quality


def encode(selected, detector_path, encoder_path):
    cv2.setNumThreads(2)
    detector = cv2.FaceDetectorYN.create(str(detector_path), "", (160, 160), 0.85, 0.3, 500)
    encoder = cv2.FaceRecognizerSF.create(str(encoder_path), "")
    features = {}
    for count, (identity, payloads) in enumerate(selected.items(), 1):
        good = [row for payload in payloads if (row := face_feature(payload, detector, encoder)) is not None]
        features[identity] = good
        if count % 10 == 0:
            print(f"Encoded {count}/{len(selected)} identity groups", flush=True)
    return features


def pair_rows(features, identities):
    embeddings, quality, labels = [], [], []
    for number, identity in enumerate(identities):
        for appearance, attributes in features[identity]:
            embeddings.append(appearance)
            quality.append(attributes)
            labels.append(number)
    vectors = np.asarray(embeddings)
    attributes = np.asarray(quality)
    labels = np.asarray(labels)
    left, right = np.triu_indices(len(labels), k=1)
    cosine = np.sum(vectors[left] * vectors[right], axis=1)
    minimum = np.minimum(attributes[left], attributes[right])
    difference = np.abs(attributes[left] - attributes[right])
    design = np.column_stack([cosine, minimum, difference, cosine[:, None] * minimum])
    truth = labels[left] == labels[right]
    return design, truth


def fit(training, truth):
    positive = np.flatnonzero(truth)
    negative = np.flatnonzero(~truth)
    generator = np.random.default_rng(SEED)
    random = generator.choice(negative, size=min(len(positive) * 4, len(negative)), replace=False)
    hardest = negative[np.argsort(training[negative, 0], kind="stable")[-len(positive):]]
    rows = np.unique(np.concatenate([positive, random, hardest]))
    head = make_pipeline(StandardScaler(), LogisticRegression(C=0.1, solver="lbfgs", max_iter=300))
    return head.fit(training[rows], truth[rows])


def threshold_at(scores, truth, maximum_far):
    negatives = np.sort(scores[~truth].astype(np.float64))[::-1]
    if not len(negatives):
        raise ValueError("Development split has no impostor pairs")
    permitted = int(np.floor(maximum_far * len(negatives)))
    return float(np.nextafter(negatives[permitted], np.inf))


def metrics(scores, truth, threshold):
    scores = scores.astype(np.float64)
    accepted = scores >= threshold
    confusion = Counter(zip(truth, accepted))
    tp = confusion[True, True]
    fn = confusion[True, False]
    fp = confusion[False, True]
    tn = confusion[False, False]
    return {
        "threshold": threshold, "trueAccepts": tp, "falseRejects": fn,
        "falseAccepts": fp, "trueRejects": tn, "tar": tp / (tp + fn),
        "far": fp / (tn + fp), "rocAuc": float(roc_auc_score(truth, scores)),
    }


def run(args):
    if sha256(args.sface) != SFACE_SHA256:
        raise ValueError("Expected app-pinned SFace weights")
    sampled = sample(args.archive, args.errata, args.photos_per_identity)
    groups = sorted(sampled, key=lambda identity: hashlib.sha256(f"{SEED}/{identity}".encode()).digest())
    splits = {"train": groups[:33], "dev": groups[33:58], "test": groups[58:]}
    if len(set().union(*splits.values())) != sum(map(len, splits.values())):
        raise ValueError("Identity crosses splits")
    started = time.perf_counter()
    features = encode(sampled, args.yunet, args.sface)
    train, train_labels = pair_rows(features, splits["train"])
    dev, dev_labels = pair_rows(features, splits["dev"])
    test, test_labels = pair_rows(features, splits["test"])
    fitted = fit(train, train_labels)
    dev_head = fitted.decision_function(dev)
    test_head = fitted.decision_function(test)
    cosine_threshold = threshold_at(dev[:, 0], dev_labels, args.target_far)
    head_threshold = threshold_at(dev_head, dev_labels, args.target_far)
    definitions = [
        ("reference_cosine", dev[:, 0], test[:, 0], 0.363),
        ("dev_calibrated_cosine", dev[:, 0], test[:, 0], cosine_threshold),
        ("trained_quality_head", dev_head, test_head, head_threshold),
    ]
    return {
        "source": SOURCE,
        "sourceSha256": {name: sha256(getattr(args, name)) for name in ("archive", "errata", "yunet", "sface")},
        "seed": SEED,
        "targetDevFar": args.target_far,
        "photosPerIdentity": args.photos_per_identity,
        "opencvVersion": cv2.__version__,
        "metrics": {name: {"dev": metrics(development, dev_labels, threshold),
                            "test": metrics(held_out, test_labels, threshold)}
                    for name, development, held_out, threshold in definitions},
        "splits": {name: {"identities": len(identities),
                           "sampled": sum(len(sampled[identity]) for identity in identities),
                           "encoded": sum(len(features[identity]) for identity in identities),
                           "identitiesWithPairs": sum(len(features[identity]) >= 2 for identity in identities)}
                   for name, identities in splits.items()},
        "elapsedSeconds": time.perf_counter() - started,
        "scope": "Local research pilot on identity-disjoint PubFig83 near-frontal crops with OpenCV CPU alignment. Scores are conditional on exactly one detected face; no app or raw-gallery performance claim. Pair counts are correlated within identity, and pretraining overlap is unknown.",
    }


def main():
    parser = argparse.ArgumentParser(description="Fit and evaluate a small face verification scorer locally.")
    for name in ("archive", "errata", "yunet", "sface", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--photos-per-identity", type=int, default=12)
    parser.add_argument("--target-far", type=float, default=0.01)
    args = parser.parse_args()
    if not 4 <= args.photos_per_identity <= 30 or not 0 < args.target_far < 0.1:
        parser.error("Expected 4–30 photos per identity and a target FAR below 0.1")
    if args.output.exists():
        raise FileExistsError(args.output)
    result = run(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(json.dumps(result["metrics"]))


if __name__ == "__main__":
    main()
