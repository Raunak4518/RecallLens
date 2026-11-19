import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import time
import unicodedata

import numpy as np
from PIL import Image
from tokenizers import Tokenizer

from tools.model_eval.benchmark import (
    candidate_jsonl_rows,
    evaluate_chunk_routes,
    evaluate_model,
    judgment_jsonl_rows,
    paired_quality_comparisons,
)
from tools.model_eval.core import (
    EmbeddingCache,
    cache_key,
    load_manifest,
    query_identifier,
    sha256_file,
    write_json,
    write_jsonl,
)
from tools.model_eval.encoders import AppClipOnnxEncoder, clip_token_windows


def parse_candidate(value):
    if not isinstance(value, str) or "=" not in value:
        raise ValueError("Candidates must use alias=ONNX-path")
    alias, filename = value.split("=", 1)
    if not alias or not alias[0].isalnum() or any(not (character.isalnum() or character in "_.-") for character in alias):
        raise ValueError(f"Invalid candidate alias: {alias}")
    if not filename.strip():
        raise ValueError("Candidate ONNX path must not be empty")
    return alias, Path(filename).expanduser().resolve()


def app_query_tokens(tokenizer, query, context=77, character_limit=512):
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", query)).strip()[:character_limit]
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", normalized)).lower()
    tokens = tokenizer.encode(normalized).ids
    if len(tokens) > context:
        stop = tokenizer.token_to_id("<|endoftext|>")
        if stop is None:
            raise ValueError("CLIP tokenizer is missing its end-of-text token")
        tokens = tokens[:context - 1] + [stop]
    return tokens


def app_query_token_chunks(tokenizer, query, context=77, overlap_tokens=10):
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", query)).strip().lower()
    return clip_token_windows(tokenizer, normalized, context, overlap_tokens) or [app_query_tokens(tokenizer, query, context)]


def normalized_vectors(vectors):
    values = np.asarray(vectors, dtype=np.float32)
    if values.ndim != 2 or values.shape[0] < 1 or values.shape[1] < 1 or not np.isfinite(values).all():
        raise ValueError("Text encoder output must be a nonempty finite matrix")
    norms = np.linalg.norm(values, axis=1, keepdims=True)
    if not np.isfinite(norms).all() or np.any(norms <= 0):
        raise ValueError("Text encoder output contains a zero or invalid vector")
    return values / norms


def load_cached_app_gallery(manifest, manifest_sha256, encoder, cache, image_rows, workers):
    source_receipt = cache.root.parent / "provenance.json"
    if not source_receipt.is_file():
        raise ValueError(f"The shared image cache has no provenance receipt: {source_receipt}")
    receipt_bytes = source_receipt.read_bytes()
    receipt = json.loads(receipt_bytes)
    if receipt.get("manifest_sha256") != manifest_sha256:
        raise ValueError("The shared image cache was created from a different frozen manifest")
    app_record = next((row for row in receipt.get("models", []) if row.get("route") == "appclip"), None)
    expected_fingerprints = {
        "model_content_sha256": encoder.model_sha256,
        "preprocess_sha256": encoder.preprocess_sha256,
        "execution_sha256": encoder.execution_sha256,
    }
    if app_record is None or any(app_record.get(name) != value for name, value in expected_fingerprints.items()):
        raise ValueError("The shared image cache app model fingerprints do not match the current encoder")
    image_ids = []
    keys = []
    for row in image_rows:
        key = cache_key(row["sha256"], encoder.model_sha256, encoder.preprocess_sha256, "image", encoder.execution_sha256)
        image_ids.append(row["id"])
        keys.append(key)
    vectors = [None] * len(keys)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(cache.get, key): index for index, key in enumerate(keys)}
        for completed, future in enumerate(as_completed(futures), 1):
            index = futures[future]
            vector = future.result()
            if vector is None:
                raise ValueError(f"The shared app image embedding cache is missing photo {image_ids[index]}")
            vectors[index] = vector
            if completed % 2000 == 0:
                print(f"Loaded {completed}/{len(keys)} verified image embeddings", flush=True)
    matrix = np.stack(vectors).astype(np.float32, copy=False)
    if matrix.shape[0] != len(manifest["images"]):
        raise ValueError("The cached app image gallery does not match the manifest")
    return image_ids, matrix, hashlib.sha256(receipt_bytes).hexdigest()


def encode_text_graph(graph_path, queries, tokenizer_path, onnxruntime, intra_threads):
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = intra_threads
    options.inter_op_num_threads = 1
    session = onnxruntime.InferenceSession(str(graph_path), options, providers=["CPUExecutionProvider"])
    vectors = {}
    preprocessing_seconds = 0.0
    inference_seconds = 0.0
    for query_id, row in queries:
        started = time.perf_counter()
        tokens = app_query_tokens(tokenizer, row["query"])
        tensor = np.asarray([tokens], dtype=np.int64)
        preprocessing_seconds += time.perf_counter() - started
        started = time.perf_counter()
        output = session.run(["text_embeds"], {"input_ids": tensor})[0]
        inference_seconds += time.perf_counter() - started
        vector = normalized_vectors(output)
        if vector.shape[0] != 1 or vector.shape[1] != 512:
            raise ValueError(f"Unexpected CLIP text embedding shape for {graph_path}: {vector.shape}")
        vectors[query_id] = vector[0]
    return vectors, {
        "queries": len(vectors),
        "preprocessing_seconds": preprocessing_seconds,
        "inference_seconds": inference_seconds,
        "host_cpu_provider": "CPUExecutionProvider",
        "host_cpu_threads": intra_threads,
    }


def encode_chunked_text_graph(graph_path, queries, tokenizer_path, onnxruntime, intra_threads, direct_vectors):
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = intra_threads
    options.inter_op_num_threads = 1
    session = onnxruntime.InferenceSession(str(graph_path), options, providers=["CPUExecutionProvider"])
    chunk_vectors = {}
    preprocessing_seconds = 0.0
    inference_seconds = 0.0
    encoded_chunks = 0
    chunked_queries = 0
    for query_id, row in queries:
        started = time.perf_counter()
        chunks = app_query_token_chunks(tokenizer, row["query"])
        preprocessing_seconds += time.perf_counter() - started
        if len(chunks) == 1:
            chunk_vectors[query_id] = [direct_vectors[query_id]]
            continue
        chunked_queries += 1
        values = []
        for tokens in chunks:
            tensor = np.asarray([tokens], dtype=np.int64)
            started = time.perf_counter()
            output = session.run(["text_embeds"], {"input_ids": tensor})[0]
            inference_seconds += time.perf_counter() - started
            vector = normalized_vectors(output)
            if vector.shape != (1, 512):
                raise ValueError(f"Unexpected CLIP text embedding shape for chunk in {graph_path}: {vector.shape}")
            values.append(vector[0])
            encoded_chunks += 1
        chunk_vectors[query_id] = values
    return chunk_vectors, {
        "chunked_queries": chunked_queries,
        "encoded_chunks": encoded_chunks,
        "preprocessing_seconds": preprocessing_seconds,
        "inference_seconds": inference_seconds,
        "host_cpu_provider": "CPUExecutionProvider",
        "host_cpu_threads": intra_threads,
    }


def run(args):
    import onnxruntime

    manifest, manifest_sha256, manifest_path = load_manifest(args.manifest, args.expected_manifest_sha256)
    candidates = [parse_candidate(value) for value in args.candidate]
    if not candidates or len({alias for alias, _ in candidates}) != len(candidates):
        raise ValueError("At least one candidate is required and aliases must be unique")
    for _, path in candidates:
        if not path.is_file():
            raise ValueError(f"Candidate graph does not exist: {path}")
    output = args.output.resolve()
    if any((output / name).exists() for name in ("candidates.jsonl", "judgments.jsonl", "results.json", "provenance.json")):
        raise FileExistsError(f"Output already contains a report: {output}")
    output.mkdir(parents=True, exist_ok=True)
    app_encoder = AppClipOnnxEncoder("app_reference", "app-clip:" + str(args.app_model), args.app_model, onnxruntime)
    image_rows = manifest["images"]
    app_cache = EmbeddingCache(args.image_cache)
    cache_started = time.perf_counter()
    image_ids, image_matrix, cache_receipt_sha256 = load_cached_app_gallery(
        manifest, manifest_sha256, app_encoder, app_cache, image_rows, args.cache_workers)
    image_cache_load_seconds = time.perf_counter() - cache_started
    query_rows = [(query_identifier(row, index), row) for index, row in enumerate(manifest["queries"])]
    route_results = []
    route_rankings = {}
    route_provenance = []
    vectors_by_route = {}
    started_utc = datetime.now(timezone.utc).isoformat()
    for alias, graph_path in candidates:
        print(f"Encoding {alias}: {graph_path.name}", flush=True)
        digest = sha256_file(graph_path)
        try:
            text_vectors, timing = encode_text_graph(graph_path, query_rows, args.tokenizer, onnxruntime, args.intra_threads)
        except Exception as error:
            failed_route = {
                "route": alias,
                "status": "unsupported_or_failed",
                "graph_path_name": graph_path.name,
                "graph_sha256": digest,
                "graph_bytes": graph_path.stat().st_size,
                "error_type": type(error).__name__,
                "error": str(error)[:1000],
            }
            route_results.append(failed_route)
            route_provenance.append(failed_route)
            print(f"Skipped {alias}: {type(error).__name__}: {error}", flush=True)
            continue
        print(f"Finished {alias}", flush=True)
        vectors_by_route[alias] = text_vectors
        rankings, result = evaluate_model(
            manifest, alias, image_ids, image_matrix, query_rows, text_vectors, args.depth, args.k)
        route_rankings[alias] = rankings
        result.update({
            "status": "complete",
            "graph_path_name": graph_path.name,
            "graph_sha256": digest,
            "graph_bytes": graph_path.stat().st_size,
            "text_encoding": timing,
        })
        route_results.append(result)
        route_provenance.append({
            "route": alias,
            "graph_path_name": graph_path.name,
            "graph_sha256": digest,
            "graph_bytes": graph_path.stat().st_size,
            "text_encoding": timing,
        })
        if args.chunk_routes:
            try:
                chunks, chunk_timing = encode_chunked_text_graph(
                    graph_path, query_rows, args.tokenizer, onnxruntime, args.intra_threads, text_vectors)
                chunk_rankings, chunk_results = evaluate_chunk_routes(
                    manifest, alias, image_ids, image_matrix, query_rows, text_vectors, chunks, args.depth, args.k)
                for result in chunk_results:
                    result.update({
                        "status": "complete",
                        "graph_path_name": graph_path.name,
                        "graph_sha256": digest,
                        "graph_bytes": graph_path.stat().st_size,
                        "text_encoding": chunk_timing,
                    })
                    route_results.append(result)
                    route_rankings[result["route"]] = chunk_rankings[result["query_aggregation"]]
                    route_provenance.append({
                        "route": result["route"],
                        "graph_path_name": graph_path.name,
                        "graph_sha256": digest,
                        "graph_bytes": graph_path.stat().st_size,
                        "text_encoding": chunk_timing,
                    })
            except Exception as error:
                failed_route = {
                    "route": f"{alias}:chunked",
                    "status": "unsupported_or_failed",
                    "graph_path_name": graph_path.name,
                    "graph_sha256": digest,
                    "graph_bytes": graph_path.stat().st_size,
                    "error_type": type(error).__name__,
                    "error": str(error)[:1000],
                }
                route_results.append(failed_route)
                route_provenance.append(failed_route)
                print(f"Chunk routes skipped for {alias}: {type(error).__name__}: {error}", flush=True)
    reference_alias = next((alias for alias, _ in candidates if "float" in alias.lower() or "fp32" in alias.lower()), None)
    if reference_alias:
        reference = vectors_by_route[reference_alias]
        for result in (row for row in route_results if row.get("status") == "complete"):
            values = [float(np.dot(reference[qid], vectors_by_route[result["route"]][qid])) for qid, _ in query_rows]
            result["cosine_to_full_precision_text"] = {
                "reference_route": reference_alias,
                "mean": float(np.mean(values)),
                "median": float(np.median(values)),
                "p05": float(np.quantile(values, 0.05)),
                "minimum": float(np.min(values)),
            }
    candidate_index = {row["query_id"]: row for row in manifest["queries"]}
    all_candidates = {}
    for alias, rows in route_rankings.items():
        for query_id, ranked in rows:
            all_candidates.setdefault(query_id, {"query_id": query_id, "routes": {}})["routes"][alias] = ranked
    completed_results = [row for row in route_results if row.get("status") == "complete"]
    if not completed_results:
        raise RuntimeError("None of the supplied CLIP text graphs produced usable embeddings")
    comparison = paired_quality_comparisons(completed_results)
    write_jsonl(output / "candidates.jsonl", candidate_jsonl_rows(candidate_index, all_candidates))
    write_jsonl(output / "judgments.jsonl", judgment_jsonl_rows(candidate_index))
    write_json(output / "results.json", {
        "complete": True,
        "manifest_sha256": manifest_sha256,
        "gallery_images": len(image_rows),
        "queries": len(query_rows),
        "candidate_depth": args.depth,
        "quality_cutoff": args.k,
        "models": route_results,
        "paired_comparisons": comparison,
        "scope": "This is a controlled text-graph sweep using the same cached app image embeddings, query strings, gallery and labels. It measures retrieval quality and host CPU text-graph time only; it is not Android latency.",
    })
    write_json(output / "provenance.json", {
        "schema_version": 1,
        "started_utc": started_utc,
        "manifest_sha256": manifest_sha256,
        "source_images_rehashed": False,
        "manifest_and_cache_provenance_verified": True,
        "image_encoder": {
            "route": "existing app CLIP vision graph",
            "model_sha256": app_encoder.model_sha256,
            "preprocess_sha256": app_encoder.preprocess_sha256,
            "execution_sha256": app_encoder.execution_sha256,
            "cache_root_name": Path(args.image_cache).name,
            "cache_entries_reused": len(image_rows),
            "cache_load_seconds": image_cache_load_seconds,
            "cache_provenance_sha256": cache_receipt_sha256,
        },
        "text_graphs": route_provenance,
        "runtime": {
            "onnxruntime": onnxruntime.__version__,
            "python": platform.python_version(),
            "numpy": np.__version__,
            "pillow": Image.__version__,
            "machine": platform.machine(),
            "cpu_count": os.cpu_count(),
            "onnx_provider": "CPUExecutionProvider",
            "intra_threads": args.intra_threads,
        },
    })
    return {"output": str(output), "routes": len(route_results), "images": len(image_rows), "queries": len(query_rows)}


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Compare CLIP text graph precision using one frozen cached app image gallery.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--expected-manifest-sha256")
    parser.add_argument("--app-model", type=Path, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument("--image-cache", type=Path, required=True)
    parser.add_argument("--cache-workers", type=int, default=8)
    parser.add_argument("--candidate", action="append", required=True)
    parser.add_argument("--chunk-routes", action="store_true")
    parser.add_argument("--intra-threads", type=int, default=2)
    parser.add_argument("--depth", type=int, default=100)
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.expected_manifest_sha256 is not None and not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_manifest_sha256):
        parser.error("Expected manifest SHA-256 must contain exactly 64 hexadecimal characters")
    if args.depth < max(args.k, 5) or args.k < 1:
        parser.error("Candidate depth must cover the requested cutoff and top-5")
    if args.cache_workers < 1 or args.intra_threads < 1:
        parser.error("Worker and thread counts must be positive")
    return args


def main():
    print(json.dumps(run(parse_args()), ensure_ascii=False))


if __name__ == "__main__":
    main()
