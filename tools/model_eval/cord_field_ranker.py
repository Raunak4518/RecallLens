import argparse
import json
import re
import time
from collections import Counter
from pathlib import Path

import joblib
import numpy as np
import pyarrow.parquet as pq
from scipy.sparse import csr_matrix, hstack
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler


DEFAULT_DATA = Path(".benchmark-data/document-field-cord-001/raw")
DEFAULT_OUTPUT = Path(".benchmark-data/model-quality/cord-field-ranker-001")
FILES = {
    "train": "train-00000-of-00004-b4aaeceff1d90ecb.parquet",
    "validation": "validation-00000-of-00001-cc3c5779fe22e8ca.parquet",
    "test": "test-00000-of-00001-9c204eb3f4e11791.parquet",
}
FIELDS = {
    "total": {"total.total_price"},
    "subtotal": {"sub_total.subtotal_price"},
    "tax": {"sub_total.tax_price"},
    "cash_paid": {"total.cashprice"},
    "change_due": {"total.changeprice"},
    "service_fee": {"sub_total.service_price"},
    "discount": {"sub_total.discount_price", "menu.discountprice"},
}
RULE_TERMS = {
    "total": ("total", "grand", "payable", "amount due", "balance due"),
    "subtotal": ("subtotal", "sub total", "sub-total"),
    "tax": ("tax", "vat", "ppn", "pb1"),
    "cash_paid": ("cash", "tendered", "paid"),
    "change_due": ("change", "returned"),
    "service_fee": ("service", "service charge", "tip"),
    "discount": ("discount", "disc", "promo"),
}


def row_candidate(line, width, height):
    words = line.get("words", [])
    if not words:
        return None
    text = " ".join(str(word.get("text", "")) for word in words).strip()
    if not re.search(r"\d", text):
        return None
    xs = []
    ys = []
    for word in words:
        quad = word.get("quad", {})
        for key in ("x1", "x2", "x3", "x4"):
            if key in quad:
                xs.append(float(quad[key]))
        for key in ("y1", "y2", "y3", "y4"):
            if key in quad:
                ys.append(float(quad[key]))
    if not xs or not ys:
        return None
    x0, x1 = min(xs), max(xs)
    y0, y1 = min(ys), max(ys)
    digit_count = sum(char.isdigit() for char in text)
    amount_groups = re.findall(r"\d[\d.,]*", text)
    numeric = [
        len(text), len(words), digit_count / max(1, len(text)),
        len(amount_groups), sum(char.isalpha() for char in text) / max(1, len(text)),
        float(any(char in text for char in ",.")),
        float(any(char in text for char in ("$", "€", "£", "¥", "₹"))),
        (x0 + x1) / (2 * width), (y0 + y1) / (2 * height),
        (x1 - x0) / width, (y1 - y0) / height,
    ]
    return {"text": text, "numeric": numeric, "category": line.get("category", "")}


def load_split(path):
    table = pq.read_table(path, columns=["ground_truth"])
    documents = []
    for ordinal, row in enumerate(table.to_pylist()):
        truth = json.loads(row["ground_truth"])
        size = truth.get("meta", {}).get("image_size", {})
        width = max(1, int(size.get("width", 1)))
        height = max(1, int(size.get("height", 1)))
        candidates = [candidate for line in truth.get("valid_line", [])
                     if (candidate := row_candidate(line, width, height)) is not None]
        documents.append({"id": ordinal, "candidates": candidates})
    return documents


def samples(documents):
    texts = []
    numbers = []
    labels = []
    groups = []
    role_counts = Counter()
    for document in documents:
        for candidate_index, candidate in enumerate(document["candidates"]):
            category = candidate["category"]
            label = next((name for name, categories in FIELDS.items() if category in categories), "other")
            texts.append(candidate["text"])
            numbers.append(candidate["numeric"])
            labels.append(label)
            groups.append((document["id"], candidate_index))
            role_counts[label] += 1
    return texts, np.asarray(numbers, dtype=np.float32), np.asarray(labels), groups, role_counts


def score_groups(documents, texts, numbers, vectorizer, scaler, model, roles):
    rows = []
    flat_index = 0
    class_index = {name: index for index, name in enumerate(model.classes_)}
    for document in documents:
        start = flat_index
        flat_index += len(document["candidates"])
        indices = list(range(start, flat_index))
        doc_text = texts[start:flat_index]
        doc_numeric = numbers[start:flat_index]
        if not indices:
            continue
        x = hstack((vectorizer.transform(doc_text), csr_matrix(scaler.transform(doc_numeric))), format="csr")
        probabilities = model.predict_proba(x)
        for role in roles:
            target_categories = FIELDS[role]
            positive = [i for i, candidate in enumerate(document["candidates"])
                        if candidate["category"] in target_categories]
            if not positive:
                continue
            candidates = []
            for i, candidate in enumerate(document["candidates"]):
                text = candidate["text"].casefold()
                terms = RULE_TERMS[role]
                lexical_score = sum(2.0 if term in text else 0.0 for term in terms)
                y = candidate["numeric"][8]
                if role == "total":
                    lexical_score += y * 0.25
                score = float(probabilities[i, class_index.get(role, class_index["other"])])
                candidates.append({"index": i, "score": score, "rule": lexical_score, "text": candidate["text"]})
            rows.append({"doc": document["id"], "role": role, "positive": positive, "candidates": candidates})
    return rows


def rank_metrics(rows, score_key):
    if not rows:
        return {"queries": 0, "hit_at": {}, "mrr": None}
    reciprocal = []
    hits = {1: 0, 3: 0, 5: 0}
    per_role = {}
    candidate_counts = []
    for row in rows:
        ranked = sorted(row["candidates"], key=lambda candidate: candidate[score_key], reverse=True)
        candidate_counts.append(len(ranked))
        positive = set(row["positive"])
        rank = next((i + 1 for i, candidate in enumerate(ranked) if candidate["index"] in positive), None)
        reciprocal.append(0.0 if rank is None else 1.0 / rank)
        for k in hits:
            hits[k] += int(rank is not None and rank <= k)
        per_role.setdefault(row["role"], []).append((rank, len(ranked)))
    return {
        "queries": len(rows),
        "hit_at": {str(k): hits[k] / len(rows) for k in hits},
        "mrr": float(np.mean(reciprocal)),
        "median_candidates": float(np.median(candidate_counts)),
        "per_role": {
            role: {"queries": len(items), "hit_at_1": sum(rank is not None and rank == 1 for rank, _ in items) / len(items),
                  "mrr": float(np.mean([0.0 if rank is None else 1.0 / rank for rank, _ in items])),
                  "median_candidates": float(np.median([count for _, count in items]))}
            for role, items in per_role.items()
        },
    }


def evaluate(data_root, output_root):
    started = time.perf_counter()
    splits = {split: load_split(data_root / filename) for split, filename in FILES.items()}
    train_texts, train_numbers, train_labels, _, train_counts = samples(splits["train"])
    val_texts, val_numbers, _, _, val_counts = samples(splits["validation"])
    test_texts, test_numbers, _, _, test_counts = samples(splits["test"])
    vectorizer = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 5), min_df=1, max_features=50000,
                                 sublinear_tf=True, lowercase=True)
    train_text_matrix = vectorizer.fit_transform(train_texts)
    scaler = StandardScaler()
    train_numeric_matrix = scaler.fit_transform(train_numbers)
    train_x = hstack((train_text_matrix, csr_matrix(train_numeric_matrix)), format="csr")
    validation_x = hstack((vectorizer.transform(val_texts), csr_matrix(scaler.transform(val_numbers))), format="csr")
    role_names = list(FIELDS)
    choices = []
    models = {}
    for c_value in (0.1, 1.0, 10.0):
        model = LogisticRegression(C=c_value, class_weight="balanced", max_iter=1000, solver="lbfgs",
                                   random_state=42)
        model.fit(train_x, train_labels)
        validation_rows = score_groups(splits["validation"], val_texts, val_numbers, vectorizer, scaler, model,
                                       role_names)
        metrics = rank_metrics(validation_rows, "score")
        choices.append({"c": c_value, "validation_mrr": metrics["mrr"], "validation_hit_at_1": metrics["hit_at"]["1"]})
        models[c_value] = (model, validation_rows)
    chosen_c = max(choices, key=lambda row: (row["validation_mrr"], row["validation_hit_at_1"]))["c"]
    model, validation_rows = models[chosen_c]
    test_rows = score_groups(splits["test"], test_texts, test_numbers, vectorizer, scaler, model, role_names)
    result = {
        "dataset": "naver-clova-ix/cord-v2",
        "revision": "7f0115a4b758a71d6473b8d085751692da2fef98",
        "license": "CC BY 4.0",
        "scope": "Official validation and test splits kept intact; training uses only the first 200-row official train shard, 1 of 4 shards.",
        "input_limit": "Candidate text and normalized geometry come from CORD valid_line ground-truth annotations, not a live OCR engine. These are oracle-transcript field-ranking results, not end-to-end OCR accuracy.",
        "candidate_count": {"train": int(len(train_texts)), "validation": int(len(val_texts)), "test": int(len(test_texts))},
        "label_counts": {"train": dict(train_counts), "validation": dict(val_counts), "test": dict(test_counts)},
        "ranked_queries": {"validation": len(validation_rows), "test": len(test_rows)},
        "selected_c": chosen_c,
        "selection_grid": choices,
        "validation_model": rank_metrics(validation_rows, "score"),
        "validation_keyword_rule": rank_metrics(validation_rows, "rule"),
        "test_model": rank_metrics(test_rows, "score"),
        "test_keyword_rule": rank_metrics(test_rows, "rule"),
        "test_roles": sorted({row["role"] for row in test_rows}),
        "attribution": "CORD v2 by NAVER CLOVA; Park et al., CORD: A Consolidated Receipt Dataset for Post-OCR Parsing, 2019",
        "elapsed_seconds": time.perf_counter() - started,
    }
    output_root.mkdir(parents=True, exist_ok=True)
    joblib.dump({"vectorizer": vectorizer, "scaler": scaler, "model": model, "fields": FIELDS},
                output_root / "cord-field-ranker.joblib", compress=3)
    (output_root / "results.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    print(json.dumps(evaluate(args.data, args.output), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
