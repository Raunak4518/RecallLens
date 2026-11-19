import argparse
import hashlib
import json
import math
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_recall_fscore_support


def normalize(value):
    value = unicodedata.normalize("NFKC", value).casefold()
    return " ".join("".join(character if character.isalnum() else " " for character in value).split())


def levenshtein(left, right):
    if len(left) < len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for i, left_char in enumerate(left, 1):
        current = [i]
        for j, right_char in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (left_char != right_char)))
        previous = current
    return previous[-1]


def similarity(left, right):
    if not left and not right:
        return 1.0
    return 1.0 - levenshtein(left, right) / max(len(left), len(right), 1)


def dice(left, right, size=2):
    left_grams = {left[index:index + size] for index in range(max(0, len(left) - size + 1))}
    right_grams = {right[index:index + size] for index in range(max(0, len(right) - size + 1))}
    if not left_grams and not right_grams:
        return float(left == right)
    return 2 * len(left_grams & right_grams) / max(len(left_grams) + len(right_grams), 1)


def candidate_windows(lines, query):
    query_words = query.split()
    windows = []
    for line in lines:
        words = normalize(line).split()
        if not words:
            continue
        for width in range(max(1, len(query_words) - 1), min(len(words), len(query_words) + 1) + 1):
            for start in range(len(words) - width + 1):
                windows.append(" ".join(words[start:start + width]))
    return windows


def features(query_text, lines):
    query = normalize(query_text)
    words = query.split()
    normalized_lines = [normalize(line) for line in lines]
    windows = candidate_windows(normalized_lines, query)
    if not windows:
        windows = [""]
    token_set = set(words)
    best_token_f1 = 0.0
    for window in windows:
        overlap = len(token_set & set(window.split()))
        precision = overlap / max(len(window.split()), 1)
        recall = overlap / max(len(token_set), 1)
        best_token_f1 = max(best_token_f1, 2 * precision * recall / max(precision + recall, 1e-12))
    edits = [similarity(query, window) for window in windows]
    bigrams = [dice(query, window) for window in windows]
    exact_substring = any(query and query in line for line in normalized_lines)
    exact_tokens = any(re.search(rf"(?:^| ){re.escape(query)}(?:$| )", line) for line in normalized_lines)
    exact_window = any(query == window for window in windows)
    query_chars = len(query)
    nonempty_lines = [line for line in normalized_lines if line]
    return [
        float(exact_substring), float(exact_tokens), float(exact_window),
        max(edits), max(bigrams), best_token_f1,
        float(any(word in set(line.split()) for line in nonempty_lines for word in words)),
        math.log1p(query_chars), math.log1p(sum(map(len, nonempty_lines))), math.log1p(len(nonempty_lines)),
        float(sum(query in line for line in nonempty_lines)),
    ]


def split_for_image(image_id, seed):
    value = hashlib.sha256(f"ocr-document-ranker-v1/{seed}/{image_id}".encode()).digest()[0] % 100
    return "train" if value < 60 else "validation" if value < 80 else "test"


def load_inputs(manifest_path, result_path, seed):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    result = json.loads(result_path.read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or result.get("manifest_sha256") != "ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd":
        raise ValueError("The frozen OCR corpus or emulator result does not match the recorded version")
    image_rows = {row["image_id"]: row for row in manifest["images"] if row["cohort"] == "hiertext"}
    output_rows = {row["id"]: row for row in result["images"]}
    query_rows = result["queries"]
    if len(image_rows) != 180 or len(query_rows) != 360 or set(image_rows) - set(output_rows):
        raise ValueError("The expected 180 annotated photos and 360 queries are not present")
    if {row["id"] for row in query_rows} != {row["id"] for row in manifest["queries"]}:
        raise ValueError("The OCR result query IDs differ from the frozen manifest")
    folds = {image_id: split_for_image(image_id, seed) for image_id in image_rows}
    return manifest, result, image_rows, output_rows, query_rows, folds


def make_records(queries, image_rows, output_rows, folds, engine):
    records = {fold: [] for fold in ("train", "validation", "test")}
    for query in queries:
        target = query["target"]
        fold = folds.get(target)
        if fold is None:
            continue
        gallery = [image_id for image_id in image_rows if folds[image_id] == fold]
        relevant = set(query["annotated_matches"]) & set(gallery)
        if target not in relevant:
            raise ValueError(f"The query target is not judged positive: {query['id']}")
        for image_id in gallery:
            lines = output_rows[image_id][engine]["lines"]
            records[fold].append({
                "query_id": query["id"],
                "query": query["text"],
                "target": target,
                "image_id": image_id,
                "features": features(query["text"], lines),
                "label": int(image_id in relevant),
            })
    return records


def rank_metrics(records, scores, query_rows, gallery_by_query, cutoffs=(1, 5, 10)):
    grouped = defaultdict(list)
    for record, score in zip(records, scores, strict=True):
        grouped[record["query_id"]].append((record["image_id"], float(score)))
    query_success = {cutoff: [] for cutoff in cutoffs}
    reciprocal_ranks = []
    for query_id, rows in grouped.items():
        positives = set(query_rows[query_id]["annotated_matches"]) & set(gallery_by_query[query_id])
        ordered = sorted(rows, key=lambda item: (-item[1], item[0]))
        ranks = [index + 1 for index, (image_id, _) in enumerate(ordered) if image_id in positives]
        first = min(ranks) if ranks else None
        reciprocal_ranks.append(1 / first if first else 0.0)
        for cutoff in cutoffs:
            query_success[cutoff].append(bool(first and first <= cutoff))
    return {
        "queries": len(grouped),
        "hit_at": {str(cutoff): float(np.mean(values)) for cutoff, values in query_success.items()},
        "mrr": float(np.mean(reciprocal_ranks)),
    }


def best_threshold(labels, probabilities):
    candidates = sorted(set([0.0, 1.0, *map(float, probabilities)]))
    best = None
    for threshold in candidates:
        predicted = probabilities >= threshold
        precision, recall, f1, _ = precision_recall_fscore_support(labels, predicted, average="binary", zero_division=0)
        value = (float(f1), float(precision), float(recall), float(threshold))
        if best is None or value[0] > best[0] or value[0] == best[0] and value[1] > best[1]:
            best = value
    return {"f1": best[0], "precision": best[1], "recall": best[2], "threshold": best[3]}


def run(args):
    manifest, result, image_rows, output_rows, query_rows_list, folds = load_inputs(args.manifest, args.result, args.seed)
    query_rows = {row["id"]: row for row in query_rows_list}
    output = {"schema_version": 1, "seed": args.seed, "manifest_sha256": result["manifest_sha256"],
              "scope": "Photo-grouped calibration of query-to-OCR-line relevance on HierText-derived image search. This is not OCR engine fine-tuning or document-type field classification.",
              "photo_counts": {split: sum(value == split for value in folds.values()) for split in ("train", "validation", "test")},
              "query_counts": {split: sum(folds.get(row["target"]) == split for row in query_rows_list) for split in ("train", "validation", "test")},
              "engines": {}}
    for engine in ("mlkit", "tiny"):
        records = make_records(query_rows_list, image_rows, output_rows, folds, engine)
        x = {split: np.asarray([row["features"] for row in rows], dtype=np.float64) for split, rows in records.items()}
        y = {split: np.asarray([row["label"] for row in rows], dtype=np.int32) for split, rows in records.items()}
        query_ids = {split: sorted({row["query_id"] for row in rows}) for split, rows in records.items()}
        gallery_by_query = {query_id: sorted({row["image_id"] for row in records["validation"] if row["query_id"] == query_id}) for query_id in query_ids["validation"]}
        test_gallery_by_query = {query_id: sorted({row["image_id"] for row in records["test"] if row["query_id"] == query_id}) for query_id in query_ids["test"]}
        best = None
        for c_value in (0.01, 0.1, 1.0, 10.0):
            model = LogisticRegression(C=c_value, max_iter=1000, solver="liblinear", random_state=args.seed)
            model.fit(x["train"], y["train"])
            validation_scores = model.decision_function(x["validation"])
            quality = rank_metrics(records["validation"], validation_scores, query_rows, gallery_by_query)
            key = (quality["mrr"], quality["hit_at"]["1"], -c_value)
            if best is None or key > best[0]:
                best = (key, c_value, model, quality)
        _, selected_c, model, validation_quality = best
        validation_probabilities = model.predict_proba(x["validation"])[:, 1]
        threshold = best_threshold(y["validation"], validation_probabilities)
        test_probabilities = model.predict_proba(x["test"])[:, 1]
        test_quality = rank_metrics(records["test"], model.decision_function(x["test"]), query_rows, test_gallery_by_query)
        test_pairs = threshold
        precision, recall, f1, _ = precision_recall_fscore_support(
            y["test"], test_probabilities >= threshold["threshold"], average="binary", zero_division=0)
        test_pairs = {"precision": float(precision), "recall": float(recall), "f1": float(f1),
                      "threshold": threshold["threshold"], "positive_pairs": int(y["test"].sum()),
                      "all_pairs": len(y["test"])}
        exact_scores = np.asarray([row["features"][2] for row in records["test"]], dtype=np.float32)
        fuzzy_scores = np.asarray([row["features"][3] for row in records["test"]], dtype=np.float32)
        baseline = {
            "exact_window": rank_metrics(records["test"], exact_scores, query_rows, test_gallery_by_query),
            "fuzzy_edit_similarity": rank_metrics(records["test"], fuzzy_scores, query_rows, test_gallery_by_query),
        }
        output["engines"][engine] = {
            "train_pairs": len(y["train"]), "validation_pairs": len(y["validation"]), "test_pairs": len(y["test"]),
            "train_positive_pairs": int(y["train"].sum()), "validation_positive_pairs": int(y["validation"].sum()),
            "selected_c": selected_c,
            "validation_rank_metrics": validation_quality,
            "calibrated_test_pair_metrics": test_pairs,
            "test_rank_metrics": test_quality,
            "test_baselines": baseline,
            "coefficients": [float(value) for value in model.coef_[0]],
            "intercept": float(model.intercept_[0]),
        }
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / "results.json"
    destination.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2, sort_keys=True))
    return output


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("app/build/public-evaluation/selective-ocr/manifest.json"))
    parser.add_argument("--result", type=Path, default=Path("app/build/large-ocr-comparison/emulator.json"))
    parser.add_argument("--output", type=Path, default=Path(".benchmark-data/model-quality/ocr-document-ranker-001"))
    parser.add_argument("--seed", type=int, default=20261119)
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
