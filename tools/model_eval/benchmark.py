import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import random
import platform
import re
import time

import numpy as np
import tokenizers
from PIL import Image

from tools.model_eval.core import (
    EmbeddingCache,
    cache_key,
    evaluate_ranking,
    load_manifest,
    query_identifier,
    query_image_indices,
    resolve_gallery_root,
    resolve_image_path,
    sha256_bytes,
    sha256_file,
    summarize_quality,
    write_json,
    write_jsonl,
)
from tools.model_eval.encoders import (
    AppClipOnnxEncoder,
    HuggingFaceVisionLanguageEncoder,
    clip_token_windows,
    decode_verified_image,
    parse_model_spec,
    resolve_device,
)

LONGCLIP_REMOTE_MODEL_REFERENCES = frozenset({"creative-graphic-design/LongCLIP-B"})


def model_loading_policy(model_ref):
    if not model_ref.startswith("longclip:"):
        return model_ref, False
    candidate_ref = model_ref.removeprefix("longclip:")
    if candidate_ref not in LONGCLIP_REMOTE_MODEL_REFERENCES:
        raise ValueError("Remote model code is enabled only for the pinned LongCLIP-B evaluation candidate")
    return candidate_ref, True


def validate_options(args):
    if args.batch_size < 1 or args.batch_size > 256:
        raise ValueError("Batch size must be between 1 and 256")
    if args.decode_workers < 1 or args.decode_workers > 64:
        raise ValueError("Decode workers must be between 1 and 64")
    if args.max_image_bytes < 1:
        raise ValueError("Maximum image bytes must be positive")
    if args.max_image_pixels < 1:
        raise ValueError("Maximum image pixels must be positive")
    if args.depth < max(args.k, 5) or args.k < 1:
        raise ValueError("Candidate depth must cover both the requested cutoff and top-5 metrics")
    if args.dtype not in {"float32", "float16"}:
        raise ValueError("Dtype must be float32 or float16")
    if args.device != "cpu" and args.device != "cuda" and not re.fullmatch(r"cuda:\d+", args.device):
        raise ValueError("Device must be cpu, cuda, or cuda:N")
    if not args.models:
        raise ValueError("At least one --model alias=reference is required")
    parsed = [parse_model_spec(value) for value in args.models]
    aliases = [alias for alias, _ in parsed]
    if len(set(aliases)) != len(aliases):
        raise ValueError("Model route aliases must be unique")
    parse_model_revisions(getattr(args, "model_revisions", None), set(aliases))
    if args.device == "cpu" and args.dtype == "float16":
        raise ValueError("float16 requires --device cuda")
    return parsed


def parse_model_revisions(values, aliases):
    revisions = {}
    for value in values or []:
        if not isinstance(value, str) or "=" not in value:
            raise ValueError("Model revisions must use alias=revision")
        alias, revision = value.split("=", 1)
        if not alias or not revision.strip():
            raise ValueError("Model revisions must have a nonempty alias and revision")
        if alias not in aliases:
            raise ValueError(f"Revision supplied for unknown model route: {alias}")
        if alias in revisions:
            raise ValueError(f"Duplicate model revision for route: {alias}")
        revisions[alias] = revision.strip()
    return revisions


def validate_source_files(manifest, manifest_path, gallery_root, workers, max_image_bytes):
    rows = manifest["images"]
    root = resolve_gallery_root(manifest_path, gallery_root, manifest.get("image_root"))
    paths = {row["id"]: resolve_image_path(row["file"], root) for row in rows}
    expected_digests = {row["id"]: row["sha256"] for row in rows}
    for image_id, path in paths.items():
        if not path.is_file() or path.stat().st_size < 1 or path.stat().st_size > max_image_bytes:
            raise ValueError(f"Image is missing or exceeds the configured byte bound: {image_id}")
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda row: (row["id"], sha256_file(paths[row["id"]])), rows))
    elapsed = time.perf_counter() - started
    for image_id, actual in results:
        if actual != expected_digests[image_id]:
            raise ValueError(f"Image SHA-256 does not match the frozen manifest: {image_id}")
    return paths, elapsed


def encode_image_gallery(manifest, encoder, cache, batch_size, workers, max_image_bytes, max_image_pixels):
    image_rows = manifest["images"]
    vectors = {}
    misses = []
    cache_hits = 0
    for row in image_rows:
        key = cache_key(row["sha256"], encoder.model_sha256, encoder.preprocess_sha256, "image", encoder.execution_sha256)
        cached = cache.get(key)
        if cached is None:
            misses.append((row, key))
        else:
            vectors[row["id"]] = cached
            cache_hits += 1
    decode_worker_seconds = 0.0
    decode_wait_seconds = 0.0
    processor_seconds = 0.0
    inference_seconds = 0.0
    encode_wall = 0.0
    def decode_item(item):
        started = time.perf_counter()
        image = decode_verified_image(item[0]["resolved_path"], item[0]["sha256"], max_image_bytes, max_image_pixels)
        return image, time.perf_counter() - started

    with ThreadPoolExecutor(max_workers=workers) as pool:
        batches = [misses[offset:offset + batch_size] for offset in range(0, len(misses), batch_size)]
        def submit_batch(batch):
            return [pool.submit(decode_item, item) for item in batch]

        current = submit_batch(batches[0]) if batches else []
        for batch_index, batch in enumerate(batches):
            next_batch = submit_batch(batches[batch_index + 1]) if batch_index + 1 < len(batches) else []
            batch_start = time.perf_counter()
            decode_start = time.perf_counter()
            decoded = [future.result() for future in current]
            decode_wait_seconds += time.perf_counter() - decode_start
            decode_worker_seconds += sum(elapsed for _, elapsed in decoded)
            images = [image for image, _ in decoded]
            try:
                outputs, preprocess_time, inference_time = encoder.encode_images(images)
                processor_seconds += preprocess_time
                inference_seconds += inference_time
                if outputs.shape[0] != len(batch):
                    raise ValueError("Image encoder returned a different number of vectors than inputs")
                for (row, key), vector in zip(batch, outputs, strict=True):
                    normalized = np.asarray(vector, dtype=np.float32)
                    cache.put(key, normalized)
                    vectors[row["id"]] = normalized / np.linalg.norm(normalized)
            finally:
                for image in images:
                    image.close()
            encode_wall += time.perf_counter() - batch_start
            current = next_batch
    if len(vectors) != len(image_rows):
        raise ValueError("The image encoder did not produce one vector per manifest image")
    dimensions = {vector.shape for vector in vectors.values()}
    if len(dimensions) != 1:
        raise ValueError("Cached or encoded image vectors have inconsistent dimensions")
    ordered_ids = [row["id"] for row in image_rows]
    matrix = np.stack([vectors[image_id] for image_id in ordered_ids]).astype(np.float32, copy=False)
    return ordered_ids, matrix, {
        "gallery_images": len(image_rows),
        "encoded_images": len(misses),
        "cache_hits": cache_hits,
        "decode_worker_seconds": decode_worker_seconds,
        "decode_wait_seconds": decode_wait_seconds,
        "processor_seconds": processor_seconds,
        "inference_seconds": inference_seconds,
        "encoding_pipeline_wall_seconds": encode_wall,
        "encoded_images_per_host_second": len(misses) / encode_wall if encode_wall > 0 else None,
    }


def tokenizer_ids(tokenizer, text):
    encoded = tokenizer.encode(text, add_special_tokens=False)
    return encoded.ids if hasattr(encoded, "ids") else list(encoded)


def split_query_chunks(encoder, query, overlap_tokens=10):
    normalized = encoder.prepare_query_for_chunking(query)
    token_ids = tokenizer_ids(encoder.tokenizer, normalized)
    max_body_tokens = max(1, encoder.max_text_tokens - 2)
    if len(token_ids) <= max_body_tokens:
        return [normalized]
    overlap = min(overlap_tokens, max_body_tokens // 3)
    step = max(1, max_body_tokens - overlap)
    chunks = []
    for start in range(0, len(token_ids), step):
        ids = token_ids[start:start + max_body_tokens]
        try:
            chunk = encoder.tokenizer.decode(ids, skip_special_tokens=True, clean_up_tokenization_spaces=False)
        except TypeError:
            chunk = encoder.tokenizer.decode(ids, skip_special_tokens=True)
        chunk = re.sub(r"\s+", " ", chunk).strip()
        if chunk and (not chunks or chunk != chunks[-1]):
            chunks.append(chunk)
        if start + max_body_tokens >= len(token_ids):
            break
    return chunks or [normalized]


def encode_queries(manifest, encoder, cache, batch_size):
    queries = [(query_identifier(row, index), row) for index, row in enumerate(manifest["queries"])]
    vectors = {}
    misses = []
    cache_hits = 0
    for query_id, row in queries:
        text_sha256 = sha256_bytes(row["query"].encode("utf-8"))
        key = cache_key(text_sha256, encoder.model_sha256, encoder.preprocess_sha256, "text", encoder.execution_sha256)
        cached = cache.get(key)
        if cached is None:
            misses.append((query_id, row, key))
        else:
            vectors[query_id] = cached
            cache_hits += 1
    processor_seconds = 0.0
    inference_seconds = 0.0
    for offset in range(0, len(misses), batch_size):
        batch = misses[offset:offset + batch_size]
        outputs, preprocess_time, inference_time = encoder.encode_texts([row["query"] for _, row, _ in batch])
        processor_seconds += preprocess_time
        inference_seconds += inference_time
        if outputs.shape[0] != len(batch):
            raise ValueError("Text encoder returned a different number of vectors than inputs")
        for (query_id, _, key), vector in zip(batch, outputs, strict=True):
            normalized = np.asarray(vector, dtype=np.float32)
            cache.put(key, normalized)
            vectors[query_id] = normalized / np.linalg.norm(normalized)
    if len(vectors) != len(queries):
        raise ValueError("The text encoder did not produce one vector per manifest query")
    chunk_vectors = {}
    misses_by_key = {}
    chunk_keys_by_query = {}
    app_clip_chunks = isinstance(encoder, AppClipOnnxEncoder)
    for query_id, row in queries:
        if app_clip_chunks:
            normalized = encoder.prepare_query_for_chunking(row["query"])
            chunks = clip_token_windows(encoder.tokenizer, normalized, encoder.max_text_tokens)
        else:
            chunks = split_query_chunks(encoder, row["query"])
        if len(chunks) <= 1:
            chunk_vectors[query_id] = [vectors[query_id]]
            continue
        keys = []
        for chunk in chunks:
            payload = b"clip-token-ids:" + np.asarray(chunk, dtype="<i8").tobytes() if app_clip_chunks else chunk.encode("utf-8")
            text_sha256 = sha256_bytes(payload)
            key = cache_key(text_sha256, encoder.model_sha256, encoder.preprocess_sha256,
                            "text", encoder.execution_sha256)
            keys.append(key)
            cached = cache.get(key)
            if cached is None:
                misses_by_key.setdefault(key, chunk)
        chunk_keys_by_query[query_id] = keys
    segment_inference_seconds = 0.0
    segment_preprocess_seconds = 0.0
    segment_vectors = {}
    pending = list(misses_by_key.items())
    for offset in range(0, len(pending), batch_size):
        batch = pending[offset:offset + batch_size]
        encode = encoder.encode_token_sequences if app_clip_chunks else encoder.encode_texts
        outputs, preprocess_time, inference_time = encode([chunk for _, chunk in batch])
        segment_preprocess_seconds += preprocess_time
        segment_inference_seconds += inference_time
        if outputs.shape[0] != len(batch):
            raise ValueError("Text encoder returned a different number of vectors than query chunks")
        for (key, _), vector in zip(batch, outputs, strict=True):
            normalized = np.asarray(vector, dtype=np.float32)
            normalized = normalized / np.linalg.norm(normalized)
            cache.put(key, normalized)
            segment_vectors[key] = normalized
    for query_id, keys in chunk_keys_by_query.items():
        values = []
        for key in keys:
            value = segment_vectors.get(key)
            if value is None:
                value = cache.get(key)
            if value is None:
                raise ValueError(f"Query chunk embedding is unavailable for {query_id}")
            values.append(value)
        chunk_vectors[query_id] = values
    return queries, vectors, chunk_vectors, {
        "queries": len(queries),
        "encoded_queries": len(misses),
        "cache_hits": cache_hits,
        "processor_seconds": processor_seconds,
        "inference_seconds": inference_seconds,
        "truncated_query_count": sum(1 for _, row in queries
                                      if len(tokenizer_ids(encoder.tokenizer,
                                                           encoder.prepare_query_for_chunking(row["query"])))
                                      > encoder.max_text_tokens - 2),
        "chunk_count": sum(len(values) for values in chunk_vectors.values()),
        "chunked_query_count": sum(len(values) > 1 for values in chunk_vectors.values()),
        "encoded_chunks": len(pending),
        "chunk_embedding_reuses": sum(len(keys) for keys in chunk_keys_by_query.values()) - len(pending),
        "direct_query_character_limit": encoder.direct_query_character_limit,
        "direct_text_context_tokens": encoder.max_text_tokens,
        "chunk_text_context_tokens": encoder.max_text_tokens,
        "chunk_overlap_tokens": 10,
        "chunk_route_source_character_limit": None,
        "chunk_processor_seconds": segment_preprocess_seconds,
        "chunk_inference_seconds": segment_inference_seconds,
    }


def evaluate_chunk_routes(manifest, alias, image_ids, image_matrix, query_rows, query_vectors,
                          chunk_vectors, depth, cutoff):
    route_suffixes = ("chunk_mean", "chunk_maxsim", "chunk_top2_mean", "direct_chunk_rrf")
    rankings = {suffix: [] for suffix in route_suffixes}
    metric_rows = {suffix: [] for suffix in route_suffixes}
    route_wall_seconds = {suffix: 0.0 for suffix in route_suffixes}
    started = time.perf_counter()
    for query_id, query in query_rows:
        indices = query_image_indices(manifest["images"], query)
        scoped_ids = np.asarray([image_ids[index] for index in indices], dtype=str)
        scoped_matrix = image_matrix[indices]
        vectors = np.stack(chunk_vectors[query_id]).astype(np.float32, copy=False)
        chunk_scores = vectors @ scoped_matrix.T
        mean_vector = vectors.mean(axis=0)
        mean_norm = np.linalg.norm(mean_vector)
        if not np.isfinite(mean_norm) or mean_norm <= 0:
            raise ValueError(f"Query chunk aggregation produced an invalid vector for {query_id}")
        mean_scores = (mean_vector / mean_norm) @ scoped_matrix.T
        max_scores = chunk_scores.max(axis=0)
        top_count = min(2, chunk_scores.shape[0])
        top2_scores = np.sort(chunk_scores, axis=0)[-top_count:, :].mean(axis=0)
        direct_scores = scoped_matrix @ query_vectors[query_id]
        direct_order = np.lexsort((scoped_ids, -direct_scores))
        max_order = np.lexsort((scoped_ids, -max_scores))
        direct_rank = np.empty(len(indices), dtype=np.int32)
        max_rank = np.empty(len(indices), dtype=np.int32)
        direct_rank[direct_order] = np.arange(len(indices))
        max_rank[max_order] = np.arange(len(indices))
        rrf_scores = 1.0 / (60.0 + direct_rank + 1.0) + 1.0 / (60.0 + max_rank + 1.0)
        score_by_suffix = {
            "chunk_mean": mean_scores,
            "chunk_maxsim": max_scores,
            "chunk_top2_mean": top2_scores,
            "direct_chunk_rrf": rrf_scores,
        }
        for suffix, scores in score_by_suffix.items():
            route_started = time.perf_counter()
            order = np.lexsort((scoped_ids, -scores))
            ranked = [str(scoped_ids[int(index)]) for index in order[:depth]]
            rankings[suffix].append((query_id, ranked))
            metrics = evaluate_ranking(ranked, query, cutoff)
            row = {"query_id": query_id, "query": query["query"],
                   "family": query["family"], "metrics": metrics}
            if query.get("cluster_id") is not None:
                row["cluster_id"] = query["cluster_id"]
            if query.get("gallery_id") is not None:
                row["gallery_id"] = query["gallery_id"]
            metric_rows[suffix].append(row)
            route_wall_seconds[suffix] += time.perf_counter() - route_started
    elapsed = time.perf_counter() - started
    results = []
    for suffix in route_suffixes:
        route = f"{alias}:{suffix}"
        results.append({
            "route": route,
            "query_aggregation": suffix,
            "quality": summarize_quality(metric_rows[suffix]),
            "host_rank_wall_seconds": route_wall_seconds[suffix],
            "all_chunk_routes_wall_seconds": elapsed,
            "scope": "Host-side query-chunk ranking; not Android search latency.",
            "by_query": metric_rows[suffix],
        })
    return rankings, results


def evaluate_model(manifest, alias, image_ids, image_matrix, query_rows, query_vectors, depth, cutoff):
    candidates = []
    query_metrics = []
    started = time.perf_counter()
    for query_id, query in query_rows:
        indices = query_image_indices(manifest["images"], query)
        if not indices:
            raise ValueError(f"Query {query_id} has no gallery images")
        if len(query_vectors[query_id]) != image_matrix.shape[1]:
            raise ValueError(f"Image/text embedding dimensions disagree for query {query_id}")
        scoped_ids = [image_ids[index] for index in indices]
        scores = image_matrix[indices] @ query_vectors[query_id]
        order = np.lexsort((np.asarray(scoped_ids, dtype=str), -scores))
        ranked = [scoped_ids[int(index)] for index in order[:depth]]
        candidates.append((query_id, ranked))
        metrics = evaluate_ranking(ranked, query, cutoff)
        result = {"query_id": query_id, "query": query["query"], "family": query["family"], "metrics": metrics}
        if query.get("cluster_id") is not None:
            result["cluster_id"] = query["cluster_id"]
        if query.get("gallery_id") is not None:
            result["gallery_id"] = query["gallery_id"]
        query_metrics.append(result)
    elapsed = time.perf_counter() - started
    route_results = {
        "route": alias,
        "quality": summarize_quality(query_metrics),
        "host_rank_wall_seconds": elapsed,
        "scope": "Host-side matrix ranking only; not Android search latency.",
        "by_query": query_metrics,
    }
    return candidates, route_results


def candidate_jsonl_rows(query_index, all_candidates):
    rows = []
    for query_id, query in query_index.items():
        candidate = all_candidates[query_id]
        row = {"query_id": query_id, "routes": candidate["routes"]}
        if query.get("gallery_id") is not None:
            row["gallery_id"] = query["gallery_id"]
        rows.append(row)
    return rows


def judgment_jsonl_rows(query_index):
    rows = []
    for query_id, query in query_index.items():
        row = {
            "query_id": query_id,
            "family": query["family"],
            "absent": query["absent"],
            "grades": query["grades"],
        }
        if query.get("gallery_id") is not None:
            row["gallery_id"] = query["gallery_id"]
        rows.append(row)
    return rows


def paired_quality_comparisons(model_results, seed=91427, samples=4000):
    if len(model_results) < 2:
        return {"reference_route": model_results[0]["route"] if model_results else None, "comparisons": []}
    reference = model_results[0]
    reference_rows = {row["query_id"]: row for row in reference["by_query"]}
    groups = {}
    for query_id, row in reference_rows.items():
        gallery = row.get("gallery_id", "all")
        independent_group = row.get("cluster_id", query_id)
        groups.setdefault(gallery, {}).setdefault(independent_group, []).append(query_id)
    metric_names = (
        "known_positive_hit_at_1",
        "known_positive_hit_at_5",
        "known_positive_recall_at_k",
        "reciprocal_rank_at_k",
        "ndcg_at_k_unjudged_zero",
        "known_positive_candidate_coverage_at_depth",
    )
    comparisons = []
    for candidate in model_results[1:]:
        candidate_rows = {row["query_id"]: row for row in candidate["by_query"]}
        if set(candidate_rows) != set(reference_rows):
            raise ValueError("Paired model comparisons require identical query sets")
        metric_deltas = {}
        for metric_name in metric_names:
            query_ids = [query_id for query_id in reference_rows
                         if reference_rows[query_id]["metrics"][metric_name] is not None
                         and candidate_rows[query_id]["metrics"][metric_name] is not None]
            if not query_ids:
                metric_deltas[metric_name] = {"queries": 0, "mean_delta": None, "ci95": None}
                continue
            deltas = {
                query_id: candidate_rows[query_id]["metrics"][metric_name]
                - reference_rows[query_id]["metrics"][metric_name]
                for query_id in query_ids
            }
            observed = float(np.mean(list(deltas.values())))
            selected_groups = {
                gallery: {
                    group_name: [query_id for query_id in group_queries if query_id in deltas]
                    for group_name, group_queries in group_map.items()
                }
                for gallery, group_map in groups.items()
            }
            selected_groups = {
                gallery: {name: values for name, values in group_map.items() if values}
                for gallery, group_map in selected_groups.items()
            }
            selected_groups = {gallery: values for gallery, values in selected_groups.items() if values}
            generator = random.Random(seed + sum(ord(character) for character in candidate["route"] + metric_name))
            bootstrap = np.empty(samples, dtype=np.float64)
            for sample_index in range(samples):
                draw = []
                for group_map in selected_groups.values():
                    group_names = list(group_map)
                    sampled_groups = generator.choices(group_names, k=len(group_names))
                    for group_name in sampled_groups:
                        draw.extend(group_map[group_name])
                bootstrap[sample_index] = np.mean([deltas[query_id] for query_id in draw])
            low, high = np.quantile(bootstrap, [0.025, 0.975])
            metric_deltas[metric_name] = {
                "queries": len(query_ids),
                "independent_clusters": sum(len(group_map) for group_map in selected_groups.values()),
                "mean_delta": observed,
                "ci95": [float(low), float(high)],
            }
        comparisons.append({"candidate_route": candidate["route"], "metrics": metric_deltas})
    return {
        "reference_route": reference["route"],
        "comparisons": comparisons,
        "interval_method": "paired cluster bootstrap over source cluster_id, stratified within gallery_id; each query is its own cluster when no source cluster is supplied",
        "bootstrap_samples": samples,
        "interpretation": "Intervals account for queries sharing a source cluster within each evaluated gallery; they do not estimate transfer to new albums, populations or users.",
    }


def host_environment(torch, transformers, device):
    cudnn = getattr(getattr(torch, "backends", None), "cudnn", None)
    cuda_backend = getattr(getattr(torch, "backends", None), "cuda", None)
    cuda_matmul = getattr(cuda_backend, "matmul", None)
    record = {
        "system": platform.system(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "python": platform.python_version(),
        "torch": torch.__version__,
        "transformers": transformers.__version__,
        "tokenizers": tokenizers.__version__,
        "numpy": np.__version__,
        "pillow": Image.__version__,
        "requested_device": str(device),
        "cuda_available": bool(torch.cuda.is_available()),
        "cuda_runtime": torch.version.cuda,
        "cudnn_version": cudnn.version() if cudnn is not None and hasattr(cudnn, "version") else None,
        "cudnn_deterministic": getattr(cudnn, "deterministic", None),
        "cudnn_benchmark": getattr(cudnn, "benchmark", None),
        "cudnn_allow_tf32": getattr(cudnn, "allow_tf32", None),
        "cuda_matmul_allow_tf32": getattr(cuda_matmul, "allow_tf32", None),
        "deterministic_algorithms": torch.are_deterministic_algorithms_enabled(),
        "cpu_threads": torch.get_num_threads(),
    }
    if device.type == "cuda":
        record["gpu_name"] = torch.cuda.get_device_name(device)
        record["gpu_total_memory_bytes"] = int(torch.cuda.get_device_properties(device).total_memory)
    return record


def run(args):
    model_specs = validate_options(args)
    manifest, manifest_sha256, manifest_path = load_manifest(args.manifest, args.expected_manifest_sha256)
    try:
        import torch
        import transformers
    except ImportError as error:
        raise RuntimeError("Install PyTorch, Transformers, Hugging Face Hub and Pillow for this evaluation runner") from error
    device = resolve_device(args.device, torch)
    if args.dtype == "float16" and device.type != "cuda":
        raise ValueError("float16 inference requires CUDA")
    paths, source_verification_seconds = validate_source_files(
        manifest, manifest_path, args.gallery_root, args.decode_workers, args.max_image_bytes)
    for row in manifest["images"]:
        row["resolved_path"] = paths[row["id"]]
    output = args.output.resolve()
    existing_reports = [output / name for name in ("candidates.jsonl", "judgments.jsonl", "results.json", "provenance.json")]
    if any(path.exists() for path in existing_reports):
        raise FileExistsError(f"Output already contains a report; choose a fresh output directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    cache = EmbeddingCache(args.cache_dir or (output / "embedding-cache"))
    environment = host_environment(torch, transformers, device)
    started_utc = datetime.now(timezone.utc).isoformat()
    all_candidates = {}
    model_results = []
    model_provenance = []
    query_index = {row["query_id"]: row for row in manifest["queries"]}
    model_revisions = parse_model_revisions(args.model_revisions, {alias for alias, _ in model_specs})
    for alias, model_ref in model_specs:
        requested_revision = model_revisions.get(alias, args.revision)
        if model_ref.startswith("app-clip:"):
            import onnxruntime
            encoder = AppClipOnnxEncoder(alias, model_ref, model_ref.removeprefix("app-clip:"), onnxruntime)
        else:
            candidate_ref, trust_remote_code = model_loading_policy(model_ref)
            encoder = HuggingFaceVisionLanguageEncoder(
                alias=alias,
                model_ref=candidate_ref,
                revision=requested_revision,
                local_files_only=not args.allow_model_download,
                device=device,
                dtype_name=args.dtype,
                batch_size=args.batch_size,
                torch=torch,
                transformers=transformers,
                trust_remote_code=trust_remote_code,
            )
        image_ids, image_matrix, image_stats = encode_image_gallery(
            manifest, encoder, cache, args.batch_size, args.decode_workers, args.max_image_bytes, args.max_image_pixels)
        query_rows, query_vectors, query_chunk_vectors, query_stats = encode_queries(
            manifest, encoder, cache, args.batch_size)
        ranked_rows, model_result = evaluate_model(
            manifest, alias, image_ids, image_matrix, query_rows, query_vectors, args.depth, args.k)
        chunk_rankings, chunk_results = evaluate_chunk_routes(
            manifest, alias, image_ids, image_matrix, query_rows, query_vectors,
            query_chunk_vectors, args.depth, args.k)
        for query_id, ranked in ranked_rows:
            candidate = all_candidates.setdefault(query_id, {"query_id": query_id, "routes": {}})
            query = query_index[query_id]
            if query.get("gallery_id") is not None:
                candidate["gallery_id"] = query["gallery_id"]
            candidate["routes"][alias] = ranked
        for suffix, rows in chunk_rankings.items():
            route = f"{alias}:{suffix}"
            for query_id, ranked in rows:
                candidate = all_candidates.setdefault(query_id, {"query_id": query_id, "routes": {}})
                query = query_index[query_id]
                if query.get("gallery_id") is not None:
                    candidate["gallery_id"] = query["gallery_id"]
                candidate["routes"][route] = ranked
        model_result.update({
            "model_reference": model_ref,
            "artifact_relationship": encoder.artifact_relationship,
            "android_parity": encoder.android_parity,
            "model_type": encoder.model_type,
            "model_content_sha256": encoder.model_sha256,
            "preprocess_sha256": encoder.preprocess_sha256,
            "execution_sha256": encoder.execution_sha256,
            "execution_configuration": encoder.execution_configuration,
            "resolved_revision": encoder.resolved_revision,
            "requested_revision": requested_revision,
            "model_snapshot_fingerprint_seconds": encoder.fingerprint_seconds,
            "model_load_seconds": encoder.load_seconds,
            "device": encoder.host_execution_device,
            "dtype": encoder.dtype_description,
            "image_encoding": image_stats,
            "text_encoding": query_stats,
            "cuda_peak_allocated_bytes": encoder.peak_allocated_bytes(),
        })
        model_results.append(model_result)
        for chunk_result in chunk_results:
            model_results.append({
                **{key: value for key, value in model_result.items()
                   if key not in {"quality", "by_query", "host_rank_wall_seconds", "scope", "route"}},
                **chunk_result,
                "base_model_route": alias,
                "text_encoding": query_stats,
            })
        model_provenance.append({
            "route": alias,
            "reference": model_ref,
            "artifact_relationship": encoder.artifact_relationship,
            "android_parity": encoder.android_parity,
            "model_type": encoder.model_type,
            "resolved_revision": encoder.resolved_revision,
            "requested_revision": requested_revision,
            "model_content_sha256": encoder.model_sha256,
            "preprocess_sha256": encoder.preprocess_sha256,
            "execution_sha256": encoder.execution_sha256,
            "execution_configuration": encoder.execution_configuration,
        })
        del encoder, image_matrix, query_vectors, query_chunk_vectors
        if device.type == "cuda":
            torch.cuda.empty_cache()
    candidate_rows = candidate_jsonl_rows(query_index, all_candidates)
    judgment_rows = judgment_jsonl_rows(query_index)
    write_jsonl(output / "candidates.jsonl", candidate_rows)
    write_jsonl(output / "judgments.jsonl", judgment_rows)
    results = {
        "complete": True,
        "manifest_sha256": manifest_sha256,
        "candidate_sha256": sha256_file(output / "candidates.jsonl"),
        "judgment_sha256": sha256_file(output / "judgments.jsonl"),
        "candidate_depth": args.depth,
        "quality_cutoff": args.k,
        "models": model_results,
        "paired_comparisons": paired_quality_comparisons(model_results),
        "metrics_scope": "Ranking quality against the supplied positive/explicit-negative labels. Unjudged photos are reported separately. No match threshold or absence false-alert policy is evaluated.",
        "query_strategy_scope": "Long descriptions are evaluated both with the model's native truncated prompt and with overlapping tokenizer-bounded chunks aggregated by mean embedding, per-photo max similarity, top-two chunk mean, and direct/chunk reciprocal-rank fusion. These routes are retrieval heuristics, not learned query rewriting.",
        "speed_scope": "All durations and throughput values in this report are host-side measurements. They are not Android inference, search latency, battery, memory, or thermal claims.",
        "promotion_gate": "A Hugging Face host candidate must pass matched-image embedding and top-k parity checks against the actual Android encoder before it is treated as a replacement or as the deployed-model baseline.",
    }
    provenance = {
        "schema_version": 1,
        "started_utc": started_utc,
        "manifest_path_name": manifest_path.name,
        "manifest_sha256": manifest_sha256,
        "gallery_image_count": len(manifest["images"]),
        "query_count": len(manifest["queries"]),
        "source_sha256_verification_seconds": source_verification_seconds,
        "models": model_provenance,
        "host": environment,
        "configuration": {
            "device": str(device),
            "dtype": args.dtype,
            "requested_model_revision": args.revision,
            "requested_model_revisions": model_revisions,
            "batch_size": args.batch_size,
            "decode_workers": args.decode_workers,
            "max_image_bytes": args.max_image_bytes,
            "max_image_pixels": args.max_image_pixels,
            "candidate_depth": args.depth,
            "quality_cutoff": args.k,
            "model_downloads_allowed": args.allow_model_download,
        },
        "cache_scope": "Embedding keys include raw image or query content SHA-256, model snapshot content SHA-256, preprocessing fingerprint SHA-256, execution fingerprint SHA-256, and image/text role.",
        "runtime_boundary": "This host experiment selects models by retrieval quality. Android runtime claims require separate on-device inference and full-workflow measurements.",
    }
    write_json(output / "results.json", results)
    write_json(output / "provenance.json", provenance)
    return {"output": str(output), "models": len(model_results), "images": len(manifest["images"]), "queries": len(manifest["queries"]), "manifest_sha256": manifest_sha256}


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Compare Hugging Face CLIP/SigLIP retrieval quality on a frozen gallery manifest.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model", action="append", dest="models", required=True, help="Repeat as alias=Hugging-Face-ID or alias=local-directory")
    parser.add_argument("--revision", default="main", help="Shared Hugging Face revision; prefer a pinned commit")
    parser.add_argument("--model-revision", action="append", dest="model_revisions",
                        help="Per-route Hugging Face revision as alias=commit; repeat as needed")
    parser.add_argument("--allow-model-download", action="store_true", help="Allow downloading explicitly named Hugging Face snapshots")
    parser.add_argument("--device", default="cpu", help="cpu, cuda, or cuda:N; requested CUDA fails if unavailable")
    parser.add_argument("--dtype", choices=("float32", "float16"), default="float32")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--decode-workers", type=int, default=min(8, max(1, os.cpu_count() or 1)))
    parser.add_argument("--max-image-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--max-image-pixels", type=int, default=32_000_000)
    parser.add_argument("--gallery-root", type=Path)
    parser.add_argument("--expected-manifest-sha256")
    parser.add_argument("--depth", type=int, default=100)
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.expected_manifest_sha256 is not None and not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_manifest_sha256):
        parser.error("--expected-manifest-sha256 must be 64 hexadecimal characters")
    try:
        validate_options(args)
    except ValueError as error:
        parser.error(str(error))
    return args


def main(argv=None):
    args = parse_args(argv)
    print(json.dumps(run(args), ensure_ascii=False))


if __name__ == "__main__":
    main()
