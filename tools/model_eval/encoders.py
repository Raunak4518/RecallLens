import hashlib
import inspect
import json
from io import BytesIO
from pathlib import Path
import platform
import re
import time
import unicodedata

import numpy as np
from PIL import Image, ImageOps
from tokenizers import Tokenizer

from tools.model_eval.core import canonical_digest, sha256_directory


SUPPORTED_MODEL_TYPES = {"clip", "siglip", "siglip2", "longclip"}


def parse_model_spec(value):
    if not isinstance(value, str) or "=" not in value:
        raise ValueError("Models must use alias=Hugging-Face-ID or alias=local-directory")
    alias, model_ref = value.split("=", 1)
    if not alias or not alias[0].isalnum() or any(not (character.isalnum() or character in "_.-") for character in alias):
        raise ValueError(f"Invalid model route alias: {alias}")
    if not model_ref.strip():
        raise ValueError("Model reference must not be empty")
    return alias, model_ref


def clip_token_windows(tokenizer, normalized, context=77, overlap_tokens=10):
    body_ids = tokenizer.encode(normalized, add_special_tokens=False).ids
    body_size = context - 2
    if len(body_ids) <= body_size:
        return []
    start_id = tokenizer.token_to_id("<|startoftext|>")
    end_id = tokenizer.token_to_id("<|endoftext|>")
    if start_id is None or end_id is None:
        raise ValueError("CLIP tokenizer is missing its start or end token")
    stride = body_size - min(overlap_tokens, body_size // 3)
    windows = []
    for start in range(0, len(body_ids), stride):
        windows.append([start_id, *body_ids[start:start + body_size], end_id])
        if start + body_size >= len(body_ids):
            break
    return windows


def resolve_device(requested, torch):
    if requested == "cpu":
        return torch.device("cpu")
    if requested == "cuda":
        index = 0
    elif requested.startswith("cuda:") and requested[5:].isdigit():
        index = int(requested[5:])
    else:
        raise ValueError("Device must be cpu, cuda, or cuda:N")
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but PyTorch reports no available CUDA device")
    if index >= torch.cuda.device_count():
        raise RuntimeError(f"CUDA device {index} was requested but only {torch.cuda.device_count()} device(s) are available")
    return torch.device(f"cuda:{index}")


def resolve_model_snapshot(model_ref, revision, local_files_only):
    candidate = Path(model_ref).expanduser()
    if candidate.is_dir():
        directory = candidate.resolve()
        resolved_revision = "local-content-addressed"
    else:
        from huggingface_hub import snapshot_download

        snapshot = snapshot_download(model_ref, revision=revision, local_files_only=local_files_only)
        directory = Path(snapshot).resolve()
        resolved_revision = directory.name
    if not directory.is_dir() or not (directory / "config.json").is_file():
        raise ValueError(f"Model reference does not resolve to a Hugging Face model snapshot: {model_ref}")
    fingerprint_started = time.perf_counter()
    model_sha256 = sha256_directory(directory)
    fingerprint_seconds = time.perf_counter() - fingerprint_started
    return directory, resolved_revision, model_sha256, fingerprint_seconds


def _serialize_component(component):
    if component is None:
        return None
    if hasattr(component, "to_dict"):
        try:
            return component.to_dict()
        except (TypeError, ValueError):
            pass
    if hasattr(component, "to_json"):
        try:
            return component.to_json()
        except (TypeError, ValueError):
            pass
    if hasattr(component, "backend_tokenizer"):
        backend = component.backend_tokenizer
        if hasattr(backend, "to_str"):
            return backend.to_str()
    if hasattr(component, "init_kwargs"):
        return component.init_kwargs
    return {"class": type(component).__qualname__}


def processor_fingerprint(processor, model_type, transformers_version):
    image_processor = getattr(processor, "image_processor", None) or getattr(processor, "feature_extractor", None)
    tokenizer = getattr(processor, "tokenizer", None)
    if image_processor is None or tokenizer is None:
        raise ValueError("The selected model processor must expose image_processor and tokenizer")
    import tokenizers

    return canonical_digest({
        "model_type": model_type,
        "transformers_version": transformers_version,
        "tokenizers_version": tokenizers.__version__,
        "pillow_version": Image.__version__,
        "processor_class": type(processor).__qualname__,
        "image_processor_class": type(image_processor).__qualname__,
        "image_processor": _serialize_component(image_processor),
        "tokenizer_class": type(tokenizer).__qualname__,
        "tokenizer": _serialize_component(tokenizer),
        "image_conversion": "EXIF transpose then RGB",
        "feature_output": "get_image_features and get_text_features; L2 normalized float32",
    })


def _model_inputs(method, inputs):
    try:
        signature = inspect.signature(method)
    except (TypeError, ValueError):
        return inputs
    if any(parameter.kind == inspect.Parameter.VAR_KEYWORD for parameter in signature.parameters.values()):
        return inputs
    return {name: value for name, value in inputs.items() if name in signature.parameters}


def _pooled_tensor(value, torch):
    if hasattr(value, "pooler_output") and value.pooler_output is not None:
        value = value.pooler_output
    elif hasattr(value, "image_embeds") and value.image_embeds is not None:
        value = value.image_embeds
    elif hasattr(value, "text_embeds") and value.text_embeds is not None:
        value = value.text_embeds
    elif isinstance(value, (tuple, list)):
        value = value[0]
    if not torch.is_tensor(value):
        raise ValueError("The model feature method did not return a tensor")
    if value.ndim == 3:
        value = value[:, 0, :]
    if value.ndim != 2 or value.shape[0] < 1:
        raise ValueError("The model feature output must have shape [batch, dimension]")
    value = value.float()
    norms = value.norm(dim=1, keepdim=True)
    if not torch.isfinite(value).all() or not torch.isfinite(norms).all() or (norms <= 0).any():
        raise ValueError("The model produced invalid feature values")
    return value / norms


class HuggingFaceVisionLanguageEncoder:
    def __init__(self, alias, model_ref, revision, local_files_only, device, dtype_name, batch_size, torch, transformers,
                 trust_remote_code=False):
        self.alias = alias
        self.model_ref = model_ref
        self.device = device
        self.host_execution_device = str(device)
        self.dtype_description = dtype_name
        self.artifact_relationship = "Hugging Face host-side candidate/reference; byte identity with the deployed Android model is not established."
        self.android_parity = {
            "status": "not_measured_by_host_runner",
            "required_before_model_promotion": True,
            "required_checks": ["matched-image embedding similarity against the actual Android encoder", "top-k agreement on the same frozen photos and queries"],
        }
        self.torch = torch
        self.dtype_name = dtype_name
        self.snapshot, self.resolved_revision, self.model_sha256, self.fingerprint_seconds = resolve_model_snapshot(
            model_ref, revision, local_files_only)
        started = time.perf_counter()
        self.processor = transformers.AutoProcessor.from_pretrained(
            str(self.snapshot), local_files_only=True, trust_remote_code=trust_remote_code)
        self.tokenizer = getattr(self.processor, "tokenizer", None)
        if self.tokenizer is None:
            raise ValueError("The selected processor must expose a text tokenizer")
        self.model = transformers.AutoModel.from_pretrained(
            str(self.snapshot), local_files_only=True, trust_remote_code=trust_remote_code)
        self.model_type = getattr(self.model.config, "model_type", "")
        if self.model_type not in SUPPORTED_MODEL_TYPES:
            raise ValueError(f"Unsupported model type {self.model_type!r}; expected CLIP or SigLIP")
        if not callable(getattr(self.model, "get_image_features", None)) or not callable(getattr(self.model, "get_text_features", None)):
            raise ValueError("The selected Hugging Face model must expose image and text feature methods")
        text_config = getattr(self.model.config, "text_config", None) or self.model.config
        context_length = getattr(text_config, "max_position_embeddings", None)
        if not isinstance(context_length, int) or context_length < 8:
            raise ValueError("The selected model does not expose a supported text context length")
        self.max_text_tokens = context_length
        self.direct_query_character_limit = None
        self.preprocess_sha256 = processor_fingerprint(self.processor, self.model_type, transformers.__version__)
        dtype = getattr(torch, dtype_name)
        if dtype_name == "float16" and device.type != "cuda":
            raise ValueError("float16 inference requires a CUDA device")
        self.model.eval().to(device=device, dtype=dtype)
        execution_device = torch.cuda.get_device_name(device) if device.type == "cuda" else platform.processor()
        cudnn = getattr(getattr(torch, "backends", None), "cudnn", None)
        cuda_backend = getattr(getattr(torch, "backends", None), "cuda", None)
        cuda_matmul = getattr(cuda_backend, "matmul", None)
        self.execution_configuration = {
            "dtype": dtype_name,
            "device_type": device.type,
            "device_name": execution_device,
            "machine": platform.machine(),
            "torch_version": torch.__version__,
            "cuda_runtime": torch.version.cuda if device.type == "cuda" else None,
            "cudnn_version": cudnn.version() if cudnn is not None and hasattr(cudnn, "version") else None,
            "cudnn_deterministic": getattr(cudnn, "deterministic", None),
            "cudnn_benchmark": getattr(cudnn, "benchmark", None),
            "cudnn_allow_tf32": getattr(cudnn, "allow_tf32", None),
            "cuda_matmul_allow_tf32": getattr(cuda_matmul, "allow_tf32", None),
            "deterministic_algorithms": torch.are_deterministic_algorithms_enabled(),
            "cpu_threads": torch.get_num_threads(),
            "batch_size": batch_size,
            "model_eval_mode": not self.model.training,
            "inference_mode": True,
        }
        self.execution_sha256 = canonical_digest(self.execution_configuration)
        if device.type == "cuda":
            torch.cuda.reset_peak_memory_stats(device)
        self.load_seconds = time.perf_counter() - started

    def _to_device(self, batch):
        tensor_inputs = {}
        for name, value in batch.items():
            if not self.torch.is_tensor(value):
                continue
            if name == "pixel_values":
                value = value.to(device=self.device, dtype=getattr(self.torch, self.dtype_name))
            else:
                value = value.to(device=self.device)
            tensor_inputs[name] = value
        return tensor_inputs

    def _synchronize(self):
        if self.device.type == "cuda":
            self.torch.cuda.synchronize(self.device)

    def encode_images(self, images):
        preprocess_started = time.perf_counter()
        batch = self.processor(images=images, return_tensors="pt")
        inputs = self._to_device(batch)
        preprocess_seconds = time.perf_counter() - preprocess_started
        if "pixel_values" not in inputs:
            raise ValueError("The selected processor did not produce pixel_values")
        self._synchronize()
        started = time.perf_counter()
        with self.torch.inference_mode():
            output = self.model.get_image_features(**_model_inputs(self.model.get_image_features, inputs))
        self._synchronize()
        elapsed = time.perf_counter() - started
        vectors = _pooled_tensor(output, self.torch).cpu().numpy().astype("float32", copy=False)
        return vectors, preprocess_seconds, elapsed

    def encode_texts(self, texts):
        preprocess_started = time.perf_counter()
        batch = self.processor(text=list(texts), padding=True, truncation=True, return_tensors="pt")
        inputs = self._to_device(batch)
        preprocess_seconds = time.perf_counter() - preprocess_started
        self._synchronize()
        started = time.perf_counter()
        with self.torch.inference_mode():
            output = self.model.get_text_features(**_model_inputs(self.model.get_text_features, inputs))
        self._synchronize()
        elapsed = time.perf_counter() - started
        vectors = _pooled_tensor(output, self.torch).cpu().numpy().astype("float32", copy=False)
        return vectors, preprocess_seconds, elapsed

    def prepare_query_for_chunking(self, query):
        return re.sub(r"\s+", " ", unicodedata.normalize("NFC", query)).strip()

    def peak_allocated_bytes(self):
        if self.device.type != "cuda":
            return None
        self._synchronize()
        return int(self.torch.cuda.max_memory_allocated(self.device))


class AppClipOnnxEncoder:
    def __init__(self, alias, model_ref, model_directory, onnxruntime):
        self.alias = alias
        self.model_ref = model_ref
        self.model_directory = Path(model_directory).expanduser().resolve()
        self.resolved_revision = "local-content-addressed"
        self.host_execution_device = "CPUExecutionProvider"
        self.dtype_description = "quantized-onnx-with-float32-input-output"
        self.artifact_relationship = "Exact local Android CLIP ONNX weights, tokenizer and preprocessing configuration; runtime-level preprocessing parity remains to be checked."
        self.android_parity = {
            "status": "weights_match; host_vs_android_preprocessing_not_verified",
            "required_before_claiming_parity": True,
            "required_checks": ["same decoded image embeddings against the Android encoder", "top-k agreement on the same frozen photos and queries"],
        }
        required = ("vision_model_quantized.onnx", "text_model_quantized.onnx", "tokenizer.json", "preprocessor_config.json")
        if any(not (self.model_directory / name).is_file() for name in required):
            raise ValueError(f"The Android CLIP model directory is incomplete: {self.model_directory}")
        fingerprint_started = time.perf_counter()
        self.model_sha256 = sha256_directory(self.model_directory, included_files=required)
        self.fingerprint_seconds = time.perf_counter() - fingerprint_started
        self.config = json.loads((self.model_directory / "preprocessor_config.json").read_text(encoding="utf-8"))
        self.tokenizer = Tokenizer.from_file(str(self.model_directory / "tokenizer.json"))
        self.max_text_tokens = 77
        self.direct_query_character_limit = 512
        self.stop_token = self.tokenizer.token_to_id("<|endoftext|>")
        if self.stop_token is None:
            raise ValueError("The Android CLIP tokenizer is missing its end-of-text token")
        options = onnxruntime.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        started = time.perf_counter()
        self.vision = onnxruntime.InferenceSession(
            str(self.model_directory / "vision_model_quantized.onnx"), options,
            providers=["CPUExecutionProvider"],
        )
        self.text = onnxruntime.InferenceSession(
            str(self.model_directory / "text_model_quantized.onnx"), options,
            providers=["CPUExecutionProvider"],
        )
        self.load_seconds = time.perf_counter() - started
        self.model_type = "android-clip-vit-b32-quantized-onnx"
        self.preprocess_sha256 = canonical_digest({
            "preprocessor_config": self.config,
            "image_crop": "center-square-then-bilinear-224",
            "exif_orientation": "transpose-before-rgb-conversion",
            "direct_query_normalization": "NFC, truncate-to-512-characters, collapse-Unicode-whitespace, lowercase-root",
            "chunk_query_normalization": "NFC, collapse-Unicode-whitespace, lowercase-root, no-character-limit",
            "tokenizer_json_sha256": hashlib.sha256((self.model_directory / "tokenizer.json").read_bytes()).hexdigest(),
            "context_length": 77,
            "tokenizers_version": __import__("tokenizers").__version__,
        })
        self.execution_configuration = {
            "runtime": "onnxruntime",
            "runtime_version": onnxruntime.__version__,
            "provider": "CPUExecutionProvider",
            "intra_op_threads": 2,
            "inter_op_threads": 1,
            "image_batch_size": 1,
            "detail_views": False,
        }
        self.execution_sha256 = canonical_digest(self.execution_configuration)
        self._onnxruntime_version = onnxruntime.__version__

    def _normalize(self, vectors):
        values = np.asarray(vectors, dtype=np.float32)
        norms = np.linalg.norm(values, axis=1, keepdims=True)
        if values.ndim != 2 or values.shape[1] != 512 or not np.isfinite(values).all() or not np.isfinite(norms).all() or np.any(norms <= 0):
            raise ValueError("The Android CLIP encoder returned invalid feature vectors")
        return values / norms

    def encode_images(self, images):
        preprocess_seconds = 0.0
        inference_seconds = 0.0
        outputs = []
        edge = int(self.config["crop_size"]["width"])
        means = np.asarray(self.config["image_mean"], dtype=np.float32)
        deviations = np.asarray(self.config["image_std"], dtype=np.float32)
        for image in images:
            started = time.perf_counter()
            side = min(image.size)
            left = (image.width - side) // 2
            top = (image.height - side) // 2
            crop = image.crop((left, top, left + side, top + side)).resize((edge, edge), Image.Resampling.BILINEAR)
            pixels = np.asarray(crop, dtype=np.float32)
            pixels = (pixels * float(self.config["rescale_factor"]) - means) / deviations
            tensor = pixels.transpose(2, 0, 1)[None].astype(np.float32, copy=False)
            preprocess_seconds += time.perf_counter() - started
            started = time.perf_counter()
            outputs.append(self.vision.run(["image_embeds"], {"pixel_values": tensor})[0][0])
            inference_seconds += time.perf_counter() - started
            crop.close()
        return self._normalize(outputs), preprocess_seconds, inference_seconds

    def encode_texts(self, texts):
        started = time.perf_counter()
        sequences = []
        for query in texts:
            normalized = self.prepare_query_for_chunking(query)[:512]
            tokens = self.tokenizer.encode(normalized).ids
            if len(tokens) > 77:
                tokens = tokens[:76] + [self.stop_token]
            sequences.append(tokens)
        tokenize_seconds = time.perf_counter() - started
        vectors, preprocess_seconds, inference_seconds = self.encode_token_sequences(sequences)
        return vectors, tokenize_seconds + preprocess_seconds, inference_seconds

    def encode_token_sequences(self, sequences):
        preprocess_seconds = 0.0
        inference_seconds = 0.0
        outputs = []
        for tokens in sequences:
            started = time.perf_counter()
            tensor = np.asarray([tokens], dtype=np.int64)
            preprocess_seconds += time.perf_counter() - started
            started = time.perf_counter()
            outputs.append(self.text.run(["text_embeds"], {"input_ids": tensor})[0][0])
            inference_seconds += time.perf_counter() - started
        return self._normalize(outputs), preprocess_seconds, inference_seconds

    def prepare_query_for_chunking(self, query):
        return re.sub(r"\s+", " ", unicodedata.normalize("NFC", query)).strip().lower()

    def peak_allocated_bytes(self):
        return None


def decode_verified_image(path, expected_sha256, max_bytes, max_pixels):
    path = Path(path)
    size = path.stat().st_size
    if size < 1 or size > max_bytes:
        raise ValueError(f"Image byte size is outside the configured bound: {path}")
    payload = path.read_bytes()
    actual_sha256 = hashlib.sha256(payload).hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError(f"Image SHA-256 does not match the manifest: {path}")
    with Image.open(BytesIO(payload)) as source:
        if source.width < 1 or source.height < 1 or source.width * source.height > min(max_pixels, Image.MAX_IMAGE_PIXELS):
            raise ValueError(f"Image dimensions exceed the Pillow safety limit: {path}")
        source.load()
        return ImageOps.exif_transpose(source).convert("RGB")
