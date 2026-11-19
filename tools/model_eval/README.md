# Host-side vision-language model quality comparison

`benchmark.py` compares the exact local Android CLIP ONNX artifact and one or more Hugging Face CLIP, SigLIP, or SigLIP 2 image/text encoders against a single frozen gallery and query manifest. Hugging Face candidates run on the requested host device. Android CLIP runs through its local ONNX graph on CPU because the installed ONNX Runtime has no CUDA provider. A requested CUDA device applies to Hugging Face candidates and is mandatory; if unavailable, the runner stops instead of falling back to CPU. The report records the actual provider per model so quality is not misread as a speed comparison.

The runner does not download a model by default. Model references may point to local snapshots or to repositories already present in the Hugging Face cache. `--allow-model-download` is required to fetch the explicitly named Hugging Face repositories. No model was downloaded while adding this tool.

## Manifest

The DOCCI manifest produced by `tools/prepare_docci_hard_manifest.py` is supported directly. It combines the existing 10,000-photo background with 200 publisher-described target images and their curated visual distractors. Every query has one target, four explicit hard negatives, and unknown background photos. The download streams and verifies the 7.59 GB publisher archive, retains only the selected target and distractor files, and writes the source receipt under ignored `.benchmark-data/`. PhotoBench manifests produced by `tools/prepare_photobench_manifest.py` are also supported; each query is ranked only against images with the same `gallery_id`. The older `tools/large-gallery/` manifest shape is supported as well.

The supported fields are:

- Top level: `images`, `queries`, optional `version: 1`, and optional `image_root`.
- Each image: unique `id`, relative `file`, raw-file `sha256`, and optional `gallery_id`.
- Each query: unique `query_id` (optional for the older shape), `query`, optional `gallery_id`, and either a `grades` map with integer relevance 0–3 and `absent`, or `positive`, `explicit_negative`, and `type`.

Every source image is SHA-256 checked against the manifest before inference. Image and query records must both use `gallery_id` or both omit it. When present, every query ranking and emitted candidate route is restricted to that gallery, and the candidate and judgment JSONL preserve `gallery_id`. The runner accepts integer schema version 1 and the current `gallery-10k-v1` manifest version.

By default, each compressed image is limited to 64 MiB and 32 million decoded pixels. Change `--max-image-bytes` or `--max-image-pixels` deliberately when a corpus needs different bounds.

The full manifest bytes are hashed into each result. For repeatable comparisons, record that hash and pass it with `--expected-manifest-sha256`. Candidate paths must stay within the manifest image root or an explicit `--gallery-root`.

## Run

Install Pillow, NumPy, Transformers, Tokenizers, Hugging Face Hub, and a PyTorch build that matches the host CUDA driver. Model choice and output location are explicit. This example expects both model directories to exist locally and performs no model download:

```powershell
python -m tools.model_eval.benchmark `
  --manifest .benchmark-data/docci-hard-200/manifest.json `
  --model appclip=app-clip:.local-models/clip `
  --model clip=openai/clip-vit-base-patch32 `
  --model siglip2=google/siglip2-base-patch16-224 `
  --model-revision clip=3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268 `
  --model-revision siglip2=75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2 `
  --allow-model-download `
  --device cuda `
  --dtype float16 `
  --batch-size 8 `
  --decode-workers 8 `
  --depth 100 `
  --k 5 `
  --output app/build/model-quality/docci-200-run-001
```

Use `--model-revision alias=commit` to pin each Hugging Face candidate independently; the shared `--revision` is the fallback. The resolved commit, model bytes and preprocessing fingerprints are saved in the report. Add `--allow-model-download` only when fetching the named checkpoints is intended. Set `HF_HOME` to a drive with enough free space before starting large model downloads.

## Output

- `candidates.jsonl`: one query per row with ranked photo IDs under each model's direct route and `chunk_mean`, `chunk_maxsim`, `chunk_top2_mean`, and `direct_chunk_rrf` routes; accepted by `tools/replay_photo_queries.py`.
- `judgments.jsonl`: the same query IDs, families, gallery IDs, absent labels and graded judgments.
- `results.json`: per-route top-1/top-5, recall at k, positive coverage at candidate depth, reciprocal-rank, `nDCG@k` with unjudged items assigned zero only for that metric, judgment coverage, explicit-negative counts, per-family results, paired query-stratified bootstrap intervals, query-chunk counts and host-only stage timings.
- `provenance.json`: manifest hash, model-content, preprocessing and execution fingerprints, resolved model revisions, device, dtype, host software and run configuration.
- `embedding-cache/`: normalized image and text vectors keyed by raw image/query content hash, model snapshot content hash, preprocessing hash, execution fingerprint and embedding role. Decoding is bounded and pipelined so the next batch can decode while the current batch is encoded.

To replay the exported routes independently, run `python tools/replay_photo_queries.py <output>/candidates.jsonl <output>/judgments.jsonl --routes clip siglip --ablate` using the aliases supplied on the command line. The runner itself also reports per-model quality so no route combination is needed to compare individual encoders.

The host timings describe this evaluation computer only. They measure neither Android inference speed nor Android search latency, memory, battery, or thermals. Use the same frozen photo/query inputs in separate emulator or Android app benchmarks to measure runtime and resource cost. Host and Android preprocessing may differ, so quality results select candidates for on-device qualification; they do not replace it.

## Isolating CLIP text-graph precision

`clip_text_precision_sweep.py` compares text ONNX graphs against the exact image embeddings already cached for the app CLIP route. It requires a complete, hash-compatible image cache and does not re-encode the gallery. This holds the image encoder, gallery, queries and labels fixed while measuring text graph retrieval quality and host CPU text-inference time. The ONNX graphs can come from the pinned Xenova CLIP conversion; supply each explicitly with `--candidate alias=path`. The runner does not download candidate graphs.

```powershell
python -m tools.model_eval.clip_text_precision_sweep `
  --manifest .benchmark-data/docci-hard-200/manifest.json `
  --expected-manifest-sha256 0a7221f23d2f39cd7af6a4e613c4ad2b075e855cc8e274e23d276e75a9775d9e `
  --app-model .local-models/clip `
  --tokenizer .local-models/clip/tokenizer.json `
  --image-cache .benchmark-data/model-quality/docci-200-run-001/embedding-cache `
  --candidate app_quantized=.local-models/clip/text_model_quantized.onnx `
  --candidate full_precision=.benchmark-data/hf-cache/hub/models--Xenova--clip-vit-base-patch32/snapshots/6b7c523703ae26e39be4b03774966f8571c7f523/onnx/text_model.onnx `
  --candidate q4=.local-models/clip/text_model_q4.onnx `
  --depth 100 `
  --k 5 `
  --output .benchmark-data/model-quality/clip-text-precision-sweep-001
```

The report records graph hashes and bytes, per-route retrieval results, paired comparisons, cosine to the full-precision text route when supplied, image-cache provenance and host CPU timings. The image cache is read in parallel and must match the exact manifest and app encoder fingerprints. Source images are not rehashed because this sweep consumes the cached embeddings rather than image files. A passing host result is not an Android quality or runtime result; promotion still requires an Android model-pack test on the same prompts and photos.

Add `--chunk-routes` to evaluate the same direct query alongside chunk mean, chunk maximum similarity, top-two chunk mean and direct-plus-chunk reciprocal-rank fusion. CLIP token IDs are split into 75-token windows with 10-token overlap, then wrapped with the model's start and end tokens; the tool does not decode and re-tokenize BPE fragments. This is a host quality comparison and text-encoding cost, not a mobile recommendation. Keep chunk routes off unless a fixed held-out test shows a useful recall gain at an acceptable runtime cost.

## Android text graph comparison on cached DOCCI vectors

Prepare one fixture from the verified host cache, then reuse it for both graphs. Run the following from the repo root.

```powershell
$manifest = '.benchmark-data/docci-hard-200/manifest.json'
$fixture = '.benchmark-data/model-quality/android-docci-input'
python -m tools.large-gallery.run_android_docci --manifest $manifest --fixture $fixture prepare --model .local-models/clip --cache .benchmark-data/model-quality/docci-200-run-001/embedding-cache
```

Build and install the feasibility app and its instrumentation APK as described in the project README. Select the desired graph with `-PphotolibraryClipTextPrecision=quantized` or `-PphotolibraryClipTextPrecision=q4`. For Q4, stage the verified `text_model_q4.onnx` from [the pinned Xenova snapshot](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523/onnx) using `python tools/stage_app_clip.py --q4-source PATH_TO_GRAPH`. The runner checks that the emulator used the requested graph. Use a fresh output folder for each run.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
python -m tools.large-gallery.run_android_docci --manifest $manifest --fixture $fixture run --adb $adb --graph text_model_quantized.onnx --output .benchmark-data/model-quality/android-docci-baseline
```

The runner records Hit@1, Hit@5, top-100 coverage and raw query times. The app restores vectors from an isolated encrypted index before running its CLIP text encoder and exact visual ranker. Images are encoded on the host, and the measurement covers visual retrieval only.

For the separate A12 storage stress run, install the same feasibility and instrumentation APKs and use:

```powershell
python -m tools.large-gallery.run_android_storage_stress --adb $adb --fixture $fixture --output .benchmark-data/model-quality/android-storage-stress-001
```

The runner repeats frozen scene vectors under distinct local IDs at 25,000 and 50,000 rows. Every row also gets generated text, with a 16 KiB text outlier for every twentieth row. It measures the encrypted scene store, total scene and text bytes, page counts, sampled text restore and cached visual ranking. Use `--rows 50000` to isolate the larger fixture. This exercise measures database cost and emulator search mechanics. Retrieval quality needs the independent 10,910-photo benchmark.

Use the same module's `compare` command with the two `results.json` files to report which queries gained or lost a top-one or top-five hit. It reports the first query separately from the median and 95th percentile for subsequent queries.

## Train a compact DOCCI shortlist reranker

Prepare a cluster-disjoint selection from the original publisher metadata. The default sizes are 800 training examples, 40 development examples and 40 final evaluation examples. Selection needs only the metadata. Add `--acquire-images` to the same command to verify the publisher archive, extract the selected photos, and check image hashes against the regression gallery and the other splits.

```powershell
$root = '.benchmark-data/model-quality/docci-reranker-001'
$regression = '.benchmark-data/docci-hard-200/manifest.json'
$source = '.benchmark-data/docci-hard-200/source'
python -m tools.model_eval.prepare_docci_reranker --source $source --regression $regression --output $root
python -m tools.model_eval.prepare_docci_reranker --source $source --regression $regression --output $root --acquire-images
```

The archive transfer is about 7.6 GB. Extraction streams the archive and caps retained images at 1 GiB by default. The data and run output stay in the ignored benchmark directory. The publisher credits and dataset terms are at [Google DOCCI](https://google.github.io/docci/).

```powershell
python -m tools.model_eval.train_docci_reranker --dataset "$root/dataset.json" --model .local-models/clip --text-graph .local-models/clip/text_model_q4.onnx --background-fixture .benchmark-data/model-quality/android-docci-input --regression $regression --output .benchmark-data/model-quality/docci-reranker-training-001
```

The trainer uses the existing CLIP image encoder and Q4 text graph on host CPU to extract frozen features, then fits a rank-32 residual projection on the laptop GPU. The train targets are exact caption and image pairs. Other candidates serve as alternatives for that pairing objective and may still be relevant to a user. Both methods rank the same fixed top 25. The trainer selects a checkpoint on development Hit@5 then Hit@1, with the unchanged baseline eligible for selection. The evaluation result records the target coverage and paired gains or losses. The optional background fixture supplies distinct 5,000-photo sets to development and evaluation with no regression DOCCI images. The run is a pilot for visual retrieval and does not evaluate Android runtime.

Use `python -m tools.model_eval.compare_docci_training_size --help` to compare two verified nested training sets. It extracts the expanded features once, verifies that development and comparison rows are identical, and fits both heads with the same number of full 64-example optimizer updates, seed and checkpoint interval. The repeated comparison gallery measures the effects of the experiment. It is already a known benchmark, so a promotion decision still needs fresh data.

### Android evaluation

Use the existing pinned benchmark checkpoint. Gradle checks the model digest, checks the Q4 graph digest, and stages the reranker only when explicitly selected. An independently retrained checkpoint needs a new qualification and a new pin.

```powershell
$run = '.benchmark-data/model-quality/docci-reranker-training-001'
python -m tools.model_eval.export_docci_reranker --run $run --output "$run/reranker.plr"
.\gradlew.bat :app:assembleFeasibility :app:assembleFeasibilityAndroidTest -PphotolibraryModelProfile=core -PphotolibraryClipTextPrecision=q4 "-PphotolibraryVisualReranker=$run/reranker.plr" --offline
```

Install the feasibility and instrumentation APKs on the authorized emulator, then run the paired holdout. The APK computes both routes in one run against the same gallery and query vectors.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
python -m tools.large-gallery.run_android_reranker --adb $adb --dataset "$root/dataset.json" --model .local-models/clip --text-graph .local-models/clip/text_model_q4.onnx --background-fixture .benchmark-data/model-quality/android-docci-input --regression $regression --output .benchmark-data/model-quality/android-docci-reranker-001
```

The experimental app path applies the projection to a single unscoped visual query and preserves the membership of the baseline shortlist. The production feature flag remains off pending broader evidence.

The runner reports quality against only the labels supplied by the manifest. Unjudged photos remain unknown. It does not choose an absence threshold or claim that a returned candidate is a false alert for an absent prompt. Long prompts are scored both as the model normally truncates them and as overlapping tokenizer-bounded chunks; chunk mean, maximum similarity, top-two chunk mean and reciprocal-rank fusion are independent measured routes, not assumed improvements.

Every Hugging Face checkpoint is reported as a host candidate/reference. A matching model name or architecture does not establish byte identity with the Android deployment. The `app-clip:` route uses the exact local ONNX files and tokenizer staged into the APK, but its PIL preprocessing still requires a matched-image embedding and top-k comparison against Android before claiming full parity. The runner records that check as outstanding; it does not claim to perform it.
