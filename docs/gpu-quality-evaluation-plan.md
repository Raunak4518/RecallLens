# Model quality and Android performance evaluation

This is a post-plan extension to [the Android search plan](final-android-search-plan.md). It separates what retrieval models find from how quickly the Android app executes them.

## What the measurements mean

| Measurement | Fixed inputs | Hardware | It answers |
| --- | --- | --- | --- |
| Encoder quality | Same image bytes, query text, model weights, tokenizer, preprocessing and precision | Laptop RTX 3050 | Which encoder or trained adapter ranks the intended images better on the benchmark |
| Retrieval-system quality | Same frozen gallery, queries, human judgments and system routes | Laptop GPU for batched model inference; CPU workers for decode and record preparation | Which combination of visual, OCR, identity, metadata and ranking evidence solves more of each request |
| Android runtime | Same APK, model pack, fixture, device state and request order | Emulator first; supported physical Android devices later | How much time, RAM, storage, battery and thermal headroom the shipped system needs |

The GPU is a faster evaluation machine. It does not make a fixed model more accurate. Small floating-point differences can occur across runtimes or precision modes, so record them and compare top-k stability. A model score is specific to its weights and preprocessing. Align decode, orientation, resize, crop, normalization, tokenizer and precision with Android, then compare host and app embeddings/rankings on a parity fixture before promoting a candidate. Overall search quality also depends on candidate coverage, query interpretation, OCR, identity evidence and ranking. Never present host throughput as phone latency.

## Current local evaluation baseline

- The repository contains a 2,000-photo Open Images gallery with 60 probes, including category, generated conjunction and positive-only relationship probes. This is a reusable smoke/regression corpus, not 200 independently authored difficult personal-album queries.
- The repository also has 10,000-photo cached-vector Android search measurements. Those vectors were encoded on the host; that run does not measure Android image-encoder speed or OCR.
- The pinned app CLIP model is a quantized ONNX model. This run has PyTorch 2.6.0+cu124, Transformers 5.9.0 and ONNX Runtime 1.26.0. The NVIDIA RTX 3050 Laptop GPU has 4 GiB VRAM; a live check showed 1,709 MiB in use, leaving about 2.3 GiB for the model run, so the initial batch is limited to four images. ONNX Runtime exposes Azure and CPU providers, not CUDA. The quality runner measures the exact packaged CLIP artifacts through CPU ONNX Runtime and separate Hugging Face candidates through CUDA-capable PyTorch; it records each provider. Do not compare their elapsed times as if they were measured on the same hardware.
- The first HF candidates are pinned to OpenAI CLIP ViT-B/32 commit `3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268` and Google SigLIP 2 Base/16 commit `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`. These are host quality candidates. Neither is byte-identical to the app's quantized Xenova CLIP, and neither should replace it until Android embedding/top-k parity and runtime qualification pass.
- OpenAI's CLIP model card presents constrained image-search use as non-deployed research and calls out fine-grained and counting limitations. Keep that HF checkpoint as an academic comparator; a high benchmark score alone does not make it a release candidate. SigLIP 2's card lists Apache-2.0 and image-text retrieval as an intended use, but its own host score still does not establish Android speed or task quality.
- Decode and image hashing can be parallelized and pipelined into batched GPU inference. Run model candidates one at a time by default so VRAM pressure and quality comparisons remain controlled. Benchmark concurrent model sessions only as a separate throughput experiment.
- Start on the laptop GPU. Use a single Kaggle GPU session only if a measured training job does not fit the local VRAM envelope; parallelize data preparation and model evaluation, not duplicate full-model training runs. Only public, rights-cleared evaluation/training data may leave the laptop. Never upload private phone photos, private embeddings or OCR output to Kaggle.

## Benchmark data

### Hard-detail retrieval benchmark: DOCCI

DOCCI is the primary encoder and query-strategy benchmark because its 15,000 real images have human-written descriptions averaging 136 words and deliberately include fine attributes, counts, spatial relations, visible text and related-image contrasts. The publisher provides four visually similar distractor images for a randomly selected 1,000 test examples. Use 200 deterministic, high-detail examples from that test subset, preserve their original descriptions, target IDs, four known hard negatives, cluster IDs and source image hashes, and rank against the existing 10,000-photo background plus the selected DOCCI images. This yields a gallery of roughly 10,000–11,000 photos without labeling every background image as a negative.

The public image archive is about 7.59 GB. The acquisition script pins and verifies the publisher object's byte count and MD5/ETag while streaming it, retains only the selected target and distractor JPEGs, and records per-image SHA-256, source filename and attribution. The resulting retained image set is bounded at 1.5 GiB and the script preserves a 2 GiB free-space reserve. DOCCI media and annotations are CC BY 4.0. Its images were taken by one researcher and family and its descriptions are English, so this cohort measures difficult visual-language matching, not the full distribution of personal phone albums, Indian documents, Gujarati/Hindi OCR, or user-specific identity search.

DOCCI test prompts are held out from all model fitting. The current Android tokenizer limits a direct query to 512 characters and the CLIP text encoder to 77 tokens. The host runner keeps that behavior for the exact app-model direct baseline, then separately evaluates full prompt text in overlapping tokenizer-bounded chunks using the same weights. It reports performance for chunk-mean, per-photo max-similarity, top-two chunk mean and direct-plus-chunk reciprocal-rank fusion. Those chunk routes are a proposed query-side extension, not code already shipped in the Android app. If one improves held-out quality, implement it in the app and measure its phone latency separately.

PhotoBench remains a later personal-album transfer evaluation. Its image terms are inconsistent between the project repository and dataset card, so the more restrictive non-commercial interpretation applies until the license holder resolves the conflict. Do not use it for training or redistribute its images. See its [official repository](https://github.com/LaVieEnRose365/PhotoBench) and [dataset card](https://huggingface.co/datasets/SorrowTea/PhotoBench).

### Specialist benchmarks

| Subsystem | Candidate source | Intended use and limit |
| --- | --- | --- |
| Exact-object instance retrieval | ILIAS: 1,000 object instances, 5,947 query/positive images, and 1,000 fine-grained text descriptions | Evaluate reference-to-instance and text-to-instance retrieval. The repository's MIT license covers its code; image rights are separate and are not established by that code license. Do not train on or download image subsets until the image-level terms are verified. |
| Famous landmark recognition | Google Landmarks Dataset v2 | Evaluate a separate landmark-candidate route with per-image attribution and license checks. Its official page says image licenses vary and asks users to verify each image; do not treat the dataset annotation license as an image license. Start with a small verified subset, not the full multi-million-image corpus. |
| General categories and relations | Existing 2,000-photo Open Images subset and annotation receipts | Keep as a regression control. The local manifest records publisher license metadata but explicitly does not independently reverify every source page. Do not invent fine-grained visual properties absent from its annotations. |
| OCR and documents | Existing HierText, OCR challenge and document benchmark artifacts | Add only prompts whose visible text and exact target are traceable to reviewed annotations. Report OCR character/field accuracy separately from retrieval success. Do not use personal OCR outputs or private phone photos as training data without a separate explicit opt-in. |
| Face identity | Existing consent-safe fixtures and controlled lookalikes | Evaluate detection, alignment, candidate recall and false acceptance separately. Public identity data must not be used to associate a real user with a name or train personal identity behavior without appropriate consent. |

The DOCCI set is not a replacement for subsystem-specific evaluation. Build separate reviewed cohorts for exact personal entities, faces, OCR/document fields, events and places from licensed or consent-safe sources. Each example must have an evidence-backed target, all known relevant images, explicit hard negatives, unknown/unjudged candidates left unlabeled, query family, source split, source image hashes and reviewer provenance. A prompt can mention only details confirmed in its supporting pixels, OCR or metadata. A generated prompt is not a human judgment.

## 200-query design

Select prompts before looking at any candidate model's ranking. Keep natural wording and balance the set across:

1. fine visual detail and attributes;
2. multiple-object combinations and relations;
3. people and confirmed entity references;
4. documents and exact remembered text;
5. event, date, place and nearby-place intent;
6. depictions, memes, screenshots and edited copies;
7. hard lookalikes, clutter, occlusion and small regions;
8. absent or ambiguous targets.

Use the benchmark's own categories where they overlap; retain its compositional visual-detail score and report our reviewed task-family labels separately. Have a reviewer confirm target evidence and near negatives. Keep unjudged results unknown. Record disagreements rather than silently forcing a binary label. Do not create prompt paraphrases from the test prompts and then treat them as independent test cases.

Report per-query rankings, target count, candidate coverage, Recall@1/5/10, MRR, nDCG@5/10, full-clause success, absent-query false returns, and confidence intervals grouped by independent query or album. Include an error category: target omitted during candidate generation, wrong query parse, missing OCR/region evidence, verifier miss, ranking error, ambiguous source evidence, or incorrect ground truth. Show sample result sheets for the 20 worst misses and 20 high-confidence false alerts.

## Model evaluation and training rules

1. Freeze the current APK model files, hashes, tokenizer and Android preprocessing as the baseline.
2. Run the same 200 prompts and galleries through the pinned baseline and each candidate encoder on the laptop GPU. Cache embeddings by image SHA-256, model-weight digest, tokenizer digest, preprocessing digest, precision, and runtime version. Reuse the common judgment manifest.
3. Compare current CLIP with one or two challengers selected for the observed miss type, such as SigLIP 2 for broad text-image alignment, DINOv2/local features for exact instances, or a document-specific OCR/layout model for text-bearing photos. MobileCLIP2 is research-only under Apple's model terms; it can be a noncommercial academic comparator, but it is not a candidate for the user's commercial product unless the terms change. A model is not selected by paper rankings alone; use the same target candidates and labels.
4. Do not fine-tune on the 200-query evaluation set. First classify failures and establish that the model, rather than missing evidence or bad parsing, caused them. If a repeatable model gap remains, create a separate task-specific training split with hard positives and negatives, deduplicated by source image, entity, capture session and query intent.
5. Prefer a small projection/head or parameter-efficient adapter on the laptop's 4 GiB GPU. Fine-tune OCR, document type, face matching, landmark recognition, and semantic retrieval only when each has suitable labels and a distinct measured failure. A submodel with no learned-parameter bottleneck should keep its pretrained weights and improve through selection, preprocessing or ranking instead.
6. Compare every trained adapter with the frozen baseline and a simple non-learned route on an untouched split. Require a paired gain in its target family, no material regression in other families, reproducible model hashes, and an Android-compatible export before adding it to the app.
7. After selecting weights, rerun quality on the fixed query set, then measure Android cold/warm inference and end-to-end behavior on the emulator. Physical-device timing remains a distinct later qualification. Do not infer phone speed from GPU throughput or emulator speed from cached-vector ranking.

### Train only the subsystem that has a learned failure

| Subsystem | First diagnosis | Candidate training, only if the diagnosis supports it |
| --- | --- | --- |
| Prompt and broad visual retrieval | Does a better parse, prompt rewrite or image crop fix the result with the current encoder? | Train a small contrastive projection/ranker on separate, reviewed hard-positive and hard-negative pairs. Keep query parsing deterministic unless held-out prompt interpretation shows a repeated failure. |
| Exact object | Is the instance missing from proposal/crop candidates, or is its crop embedding confused with a lookalike? | Evaluate DINOv2/local-feature reranking first. Train a compact adapter only from instance-disjoint examples and verified object pairs; never use the 200-query set for adaptation. |
| Face identity | Is detection/alignment the cause, or are quality and impostor thresholds miscalibrated? | Calibrate thresholds on consent-safe verification data. Do not fine-tune a general face encoder on the user's gallery; personalized identity remains locally stored reference evidence and user-confirmed links. |
| OCR and document fields | Is the text absent because of detector recall, script support, low resolution or recognition error? Does OCR succeed but field linking fail? | Compare existing recognizers first. If field linking is the gap, train a small layout/field ranker from a separate, rights-cleared annotated document split. Fine-tune an OCR model only with script-specific transcriptions and enough difficult crops. |
| Object detection and garment regions | Are targets absent from boxes/masks, or are later embeddings/ranking wrong? | Fine-tune a detector or segmenter only with reviewed boxes/masks for its error cohort. A classification loss cannot fix missing regions or bad person-to-garment ownership. |
| Famous landmarks | Is the image a known landmark but the label/prototype catalogue lacks it, or does visual classification confuse lookalikes? | Use a bounded GLDv2 training subset with per-image CC-BY attribution checks and a class-disjoint held-out set. A searchable local landmark catalogue may fix missing coverage more cheaply than fine-tuning a general encoder. |
| Events, time and place | Is the prompt-to-date/place normalization or photo metadata wrong? | Fix deterministic date and location resolution first. Model training is not justified when the evidence already exists in EXIF or a known event calendar. |
| Result ranker and local adaptation | Are candidates correct but ordered incorrectly, and are user corrections explicit? | Train a small ranker only from consented, reversible feedback with a temporal holdout. Compare against fixed rules and direct correction. Deletion/withdrawal must remove learned influence. |

This subsystem matrix is an evaluation and training policy, not a commitment to train every checkpoint. Training without a suitable independent label set can make the model worse while appearing to improve on its own examples.

## After-plan work and dependencies

The full model comparison, controlled precision sweep and 200-query Android visual retrieval benchmark are recorded in [model-quality-evaluation-results.md](model-quality-evaluation-results.md). Q4 recovered most of the full-precision retrieval score at roughly half the graph size on the host and reproduced its host ranking metrics with cached vectors on the emulator. Q4 remains opt-in while the integrated search workflow and ARM resource costs are qualified. The shortlist replay provides a reference for compact reranking experiments, which require separate training and final evaluation sets.

| ID | Work | Depends on | Completion evidence |
| --- | --- | --- | --- |
| MQ01 | Add common, hash-verified manifest validation and a CUDA-only host quality runner with modular model adapters and batched inference | Existing 2k manifest and local model artifacts | Focused unit tests, explicit provider receipt, reproducible embedding cache key, replay-compatible rankings |
| MQ02 | Acquire a frozen 200-example DOCCI test cohort with four hard distractors per target; stream the archive and retain only selected images | MQ01; publisher source hashes, current free-space check | 200 evidence-backed prompts, 800 source distractor slots, deduplicated image count, source checksums and attribution, no raw images in Git |
| MQ03 | Verify query-chunk coverage, gallery file hashes and sparse-label semantics; freeze the manifest | MQ02 | Reproducible manifest hash, positive/hard-negative/unknown accounting, context-truncation and chunking receipt |
| MQ04 | Run exact app CLIP baseline and CUDA candidates with native and chunked query routes, then inspect misses | MQ01–MQ03 | Per-query rankings, model/preprocessor hashes, paired confidence intervals and failure taxonomy; host speed labeled separately |
| MQ05 | Build separate train data only for repeated, attributable model failures | MQ04 | Task-specific train/validation/held-out split; no evaluation-query contamination; source and license receipts |
| MQ06 | Train adapters or heads only where the evidence justifies it | MQ05 | Training configuration, checkpoint provenance, before/after held-out metrics, regressions and export-size record |
| MQ07 | Integrate only winning model versions and validate full Android result quality | MQ04–MQ06, existing app model interfaces | Pinned model catalog update, offline app checks and repeatable result improvement on an untouched fixture |
| MQ08 | Measure Android runtime/resource cost of selected versions | MQ07 | Emulator cold/warm timing and memory; physical-device timing only in its own authorized stage |

The code work for MQ01/MQ03 should be completed before the evaluation run, consistent with the implementation-first workflow. Do not mark model quality or phone performance complete merely because the host runner executes.

## Sources

- [DOCCI official project, dataset schema, distractor protocol and license](https://google.github.io/docci/)
- [DOCCI dataset card, split sizes and annotation details](https://huggingface.co/datasets/google/docci)
- [OpenAI CLIP ViT-B/32 model card and stated limitations](https://huggingface.co/openai/clip-vit-base-patch32)
- [Google SigLIP 2 Base/16 model card, intended use and license](https://huggingface.co/google/siglip2-base-patch16-224)
- [PhotoBench official repository](https://github.com/LaVieEnRose365/PhotoBench)
- [PhotoBench dataset card and license metadata](https://huggingface.co/datasets/SorrowTea/PhotoBench)
- [ILIAS official repository](https://github.com/ilias-vrg/ilias)
- [ILIAS image archive listing](https://vrg.fel.cvut.cz/ilias_data/)
- [Google Landmarks Dataset v2 official repository and image-license guidance](https://github.com/cvdfoundation/google-landmark)
