import argparse
import json
from pathlib import Path
import random
import time

import numpy as np

from tools.model_eval.benchmark import validate_source_files
from tools.model_eval.core import load_manifest
from tools.model_eval.encoders import (
    AppClipOnnxEncoder,
    HuggingFaceVisionLanguageEncoder,
    resolve_device,
)


def encode_batches(encoder, values, batch_size, method_name):
    vectors = []
    preprocess_seconds = 0.0
    inference_seconds = 0.0
    method = getattr(encoder, method_name)
    for offset in range(0, len(values), batch_size):
        output, preprocess, inference = method(values[offset:offset + batch_size])
        vectors.append(output)
        preprocess_seconds += preprocess
        inference_seconds += inference
    return np.concatenate(vectors, axis=0), preprocess_seconds, inference_seconds


def similarity_summary(left, right):
    scores = np.sum(left * right, axis=1)
    return {
        "mean": float(np.mean(scores)),
        "median": float(np.median(scores)),
        "p05": float(np.quantile(scores, 0.05)),
        "minimum": float(np.min(scores)),
    }


def run(args):
    import onnxruntime
    import torch
    import transformers

    manifest, manifest_sha256, manifest_path = load_manifest(args.manifest, args.expected_manifest_sha256)
    paths, source_verification_seconds = validate_source_files(
        manifest, manifest_path, args.gallery_root, args.workers, args.max_image_bytes)
    randomizer = random.Random(args.seed)
    images = randomizer.sample(manifest["images"], min(args.images, len(manifest["images"])))
    queries = randomizer.sample(manifest["queries"], min(args.queries, len(manifest["queries"])))
    from tools.model_eval.encoders import decode_verified_image, resolve_device

    decoded = [decode_verified_image(paths[row["id"]], row["sha256"], args.max_image_bytes, args.max_image_pixels)
               for row in images]
    app = AppClipOnnxEncoder("appclip", "app-clip:" + str(args.app_model), args.app_model, onnxruntime)
    device = resolve_device(args.device, torch)
    candidate = HuggingFaceVisionLanguageEncoder(
        alias="candidate",
        model_ref=args.candidate_model,
        revision=args.candidate_revision,
        local_files_only=not args.allow_model_download,
        device=device,
        dtype_name=args.dtype,
        batch_size=args.batch_size,
        torch=torch,
        transformers=transformers,
        trust_remote_code=False,
    )
    try:
        app_image_vectors, app_image_preprocess, app_image_inference = encode_batches(
            app, decoded, args.batch_size, "encode_images")
        candidate_image_vectors, candidate_image_preprocess, candidate_image_inference = encode_batches(
            candidate, decoded, args.batch_size, "encode_images")
        app_text_vectors, app_text_preprocess, app_text_inference = encode_batches(
            app, [row["query"] for row in queries], args.batch_size, "encode_texts")
        candidate_text_vectors, candidate_text_preprocess, candidate_text_inference = encode_batches(
            candidate, [row["query"] for row in queries], args.batch_size, "encode_texts")
    finally:
        for image in decoded:
            image.close()
    app_scores = app_text_vectors @ app_image_vectors.T
    candidate_scores = candidate_text_vectors @ candidate_image_vectors.T
    app_orders = np.argsort(-app_scores, axis=1, kind="stable")
    candidate_orders = np.argsort(-candidate_scores, axis=1, kind="stable")
    top5_overlap = [len(set(left[:5]) & set(right[:5])) / 5
                    for left, right in zip(app_orders, candidate_orders, strict=True)]
    record = {
        "schema_version": 1,
        "manifest_sha256": manifest_sha256,
        "sample_seed": args.seed,
        "sampled_images": len(images),
        "sampled_queries": len(queries),
        "sampled_image_ids": [row["id"] for row in images],
        "sampled_query_ids": [row["query_id"] for row in queries],
        "source_sha256_verification_seconds": source_verification_seconds,
        "candidate_model": args.candidate_model,
        "candidate_revision": candidate.resolved_revision,
        "app_model_sha256": app.model_sha256,
        "candidate_model_sha256": candidate.model_sha256,
        "preprocessing_fingerprints": {
            "app": app.preprocess_sha256,
            "candidate": candidate.preprocess_sha256,
        },
        "image_embedding_cosine": similarity_summary(app_image_vectors, candidate_image_vectors),
        "text_embedding_cosine": similarity_summary(app_text_vectors, candidate_text_vectors),
        "sample_gallery_top1_agreement": float(np.mean(np.argmax(app_scores, axis=1) == np.argmax(candidate_scores, axis=1))),
        "sample_gallery_top5_mean_overlap": float(np.mean(top5_overlap)),
        "sample_gallery_score_correlation": float(np.corrcoef(app_scores.ravel(), candidate_scores.ravel())[0, 1]),
        "host_only_timings_seconds": {
            "app_image_preprocess": app_image_preprocess,
            "app_image_inference": app_image_inference,
            "candidate_image_preprocess": candidate_image_preprocess,
            "candidate_image_inference": candidate_image_inference,
            "app_text_preprocess": app_text_preprocess,
            "app_text_inference": app_text_inference,
            "candidate_text_preprocess": candidate_text_preprocess,
            "candidate_text_inference": candidate_text_inference,
        },
        "scope": "Host-only encoder and ranking parity check on the sampled photos and prompts. This is not an Android preprocessing or runtime parity test.",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return record


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Compare packaged app encoder outputs with a Hugging Face candidate on shared inputs.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--app-model", type=Path, required=True)
    parser.add_argument("--candidate-model", required=True)
    parser.add_argument("--candidate-revision", default="main")
    parser.add_argument("--expected-manifest-sha256")
    parser.add_argument("--gallery-root", type=Path)
    parser.add_argument("--images", type=int, default=64)
    parser.add_argument("--queries", type=int, default=64)
    parser.add_argument("--seed", type=int, default=91427)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--max-image-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--max-image-pixels", type=int, default=32_000_000)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--dtype", choices=("float32", "float16"), default="float16")
    parser.add_argument("--allow-model-download", action="store_true")
    parser.add_argument("--output", type=Path)
    return parser.parse_args(argv)


def main():
    args = parse_args()
    started = time.perf_counter()
    result = run(args)
    result["wall_seconds"] = time.perf_counter() - started
    if args.output:
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
