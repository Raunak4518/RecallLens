# Model quality and text precision results

This evaluation follows [the GPU quality plan](gpu-quality-evaluation-plan.md). Model quality is evaluated on the laptop GPU when running PyTorch candidate models; graph-size and ONNX CPU timing are reported separately. Phone and emulator latency are separate measurements.

## What is being measured

For identical model weights, preprocessing, tokenization, precision and retrieval procedure, faster laptop or GPU execution does not itself improve ranking quality. Hardware changes runtime. Precision, model choice, input views, prompt length and pipeline changes can change the embeddings and therefore quality. GPU evaluation lets us compare larger candidates and run a larger benchmark faster; it does not make a fixed model more accurate. An Android claim still requires the same candidate to run through the Android model path and a phone or emulator timing pass.

## Frozen test and controls

- Dataset: DOCCI, with 200 deterministic, high-detail test descriptions and their one target plus four source-provided distractors.
- Gallery: 10,910 photos: the existing 10,000-photo Open Images background plus 910 distinct selected DOCCI target/distractor photos.
- Labels: 200 targets, 800 hard-negative slots, 710 unique hard-negative photos; the remaining background photos are unjudged and are not treated as negatives.
- Manifest SHA-256: `0a7221f23d2f39cd7af6a4e613c4ad2b075e855cc8e274e23d276e75a9775d9e`.
- The 200 queries are a difficult, selected test cohort, not a random phone-album sample. The DOCCI training split and qualification splits were not used to tune these numbers.
- The precision sweep holds the app’s cached image embeddings, query text, gallery, labels and ranking procedure fixed. Only the text ONNX graph changes. The cache manifest and encoder fingerprints were verified before use.

## CLIP text-graph precision sweep

| Text graph | Bytes | Hit@1 | Hit@5 | Positive in top 100 | Host CPU graph time for 200 queries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Packaged quantized | 64,504,507 | 9.0% | 30.5% | 70.0% | 10.54 s |
| INT8 variant | 64,070,791 | 14.5% | 34.5% | 76.5% | 10.52 s |
| UINT8 variant | 64,070,835 | 11.0% | 30.5% | 72.5% | 12.53 s |
| Q4 variant | 125,742,108 | 45.5% | 74.5% | 98.5% | 19.94 s |
| Full precision | 254,058,553 | 46.0% | 75.5% | 99.0% | 16.94 s |
| Q4F16 variant | 72,531,963 | Not measured | Not measured | Not measured | Could not initialize in the current ONNX Runtime |

The current packaged text graph’s mean cosine against the full-precision text graph is `0.395` over the 200 prompts, with a `0.233` fifth percentile and `0.102` minimum. The Q4 graph’s corresponding mean is `0.962` (fifth percentile `0.947`, minimum `0.934`). Full precision matches the pinned Hugging Face CLIP projected text outputs to numerical precision on the same tokens. Token IDs also match exactly; the largest quality gap is therefore the current graph’s precision behavior, especially on long inputs.

Q4 trails full precision by 0.5 percentage points at Hit@1 and 1 point at Hit@5. A paired cluster bootstrap over the 148 source clusters gives Q4-minus-full-precision intervals of `[-4.5, 3.6]` points for Hit@1 and `[-4.1, 2.1]` points for Hit@5. This set does not show a reliable retrieval-quality difference between Q4 and full precision. Q4 is about half the file size, but its host CPU graph time was 18% longer than full precision. Neither result predicts Android latency.

The top-five includes at least one publisher-provided hard negative for 30% of Q4 queries and 35% of full-precision queries. This does not mean the rest of the unjudged gallery is correct or incorrect. Q4’s top-one hard-negative rate is 5.5%, while the target hit rate is 45.5%. Keep returning a ranked set and calibrate user-facing confidence separately.

## Broader model comparison on the same difficult test

The following direct-route results use each candidate’s own image and text encoders, on the same 200 prompts and 10,910-photo gallery:

| Candidate | Hit@1 | Hit@5 | Positive in top 100 | Evaluation execution |
| --- | ---: | ---: | ---: | --- |
| Packaged app CLIP | 9.0% | 30.5% | 70.0% | Quantized ONNX on host CPU |
| OpenAI CLIP ViT-B/32 | 50.5% | 72.5% | 99.5% | FP16 PyTorch on laptop RTX 3050 |
| Google SigLIP 2 Base/16 | 58.5% | 82.5% | 99.5% | FP16 PyTorch on laptop RTX 3050 |
| LongCLIP-B/16 | 80.0% | 96.0% | 99.5% | FP16 PyTorch on laptop RTX 3050 |

These results make the quality/runtime distinction concrete. LongCLIP ranks this selected long-description test best among the compared models, but its roughly 599 MB model and host-only evaluation do not establish a viable Android package or phone runtime. The higher-capacity candidate is a quality reference, not yet an app selection. The text-precision sweep separately shows that the deployed app’s poor score is substantially recoverable without fine-tuning by changing only its text graph.

## Long-prompt chunk-route check

The Q4 graph was also evaluated with the same 200 prompts, cached image vectors and gallery using token-ID windows with 10-token overlap. This preserves the CLIP token IDs directly instead of decoding and re-tokenizing BPE fragments. The direct route scored 45.5% Hit@1 and 74.5% Hit@5. Chunk mean scored 42.0% and 73.5%, chunk max-sim 42.0% and 73.0%, and top-two chunk mean 43.5% and 73.5%. Direct-plus-chunk reciprocal-rank fusion scored 46.5% and 77.5%. Its paired cluster-bootstrap difference was +1.0 points for Hit@1 (95% CI [-1.4, +3.5]) and +3.0 points for Hit@5 (95% CI [+0.5, +5.8]); 148 DOCCI clusters were resampled.

The original generic runner decoded app CLIP chunks with literal BPE word-end markers. The generic runner now uses the same token-ID windows as the precision sweep. The corrected legacy graph run, `docci-200-token-windows-001`, measured 9.0% Hit@1 and 30.5% Hit@5 for the direct route. Chunk-only routes reached at most 6.5% and 19.5%. Direct-plus-chunk fusion reached 10.5% and 31.0% with 69.5% top-100 coverage versus 70.0% direct. The prior direct rows in the full-model comparison remain valid because they use the original query.

All 200 prompts exceeded the direct context and produced 703 chunks. Host CPU text inference took 13.46 seconds for the 200 direct embeddings and 43.31 seconds for chunk embeddings. The top five contained an average of 0.435 curated distractors per query with fusion and 0.415 with direct Q4. Only publisher-judged distractors count toward these averages. Fusion remains an experimental fallback for long queries. Its retrieval value and inference cost still need confirmation in the app, and the selected DOCCI descriptions are unusually long for phone searches.

## App integration status

The app now supports a `photolibraryClipTextPrecision` build property:

- `quantized` selects the existing packaged graph and remains the default.
- `q4` selects the pinned Q4 graph for emulator qualification.

The new Q4 file is stored under ignored `.local-models/clip/`; it is not a Git deliverable. Build Q4 with `-PphotolibraryClipTextPrecision=q4`. The default is deliberately unchanged until Android loading, search quality, memory and latency checks pass. Changing the text graph does not require re-encoding photo embeddings because the image graph, preprocessing and stored image vectors are unchanged.

Both variants build and load in the x86_64 emulator. `LocalVisualSearchTest` passed 2/2 instrumentation cases with each variant. On the five-photo smoke fixture, each returned the expected photo for all five short queries and reopened the encrypted index with zero image decodes.

### Android cached visual retrieval, MQ07 and MQ08

The feasibility app restored the same 10,910 host-encoded image vectors from an isolated SQLCipher index for each graph, then ran Android CLIP text encoding and exact visual ranking on all 200 difficult prompts. Each instrumentation run passed. The index contained 51,228,733 bytes for both variants.

| Graph | Hit@1 | Hit@5 | Top-100 target coverage | First query | Warm median | Warm p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Quantized | 9.0% | 30.5% | 70.0% | 1,861 ms | 82 ms | 107 ms |
| Q4 | 45.5% | 74.5% | 98.5% | 2,043 ms | 121 ms | 144 ms |

Timing covers text encoding plus ranking. Q4 gained 77 top-one hits and lost four. It gained 90 top-five hits and lost two. Both graphs reproduced their aggregate host scores on the cached-vector path. The test isolates that path from Android image encoding, hybrid query routing and UI latency. Q4 remains opt-in while those paths and physical ARM memory and sustained performance are qualified.

Raw reports are under `.benchmark-data/model-quality/` in `android-docci-quantized-001`, `android-docci-q4-001`, and `android-docci-paired-001.json`. The paired runs used vector SHA-256 `41c7b3aee8cfbe77d16d932b4f77cee07805c152c69ae6d50f71f03232d5463e`. See [the benchmark reproduction steps](../tools/model_eval/README.md#android-text-graph-comparison-on-cached-docci-vectors).

### Encrypted index writes, A04 and O11

The durable indexing coordinator groups up to eight rows with known source revisions. It rechecks each revision before committing the batch and publishes the saved results after the transaction. Rows without a source revision use the existing per-photo pixel check. The migration regression fixture now drops the version 13 and 14 tables when reconstructing version 7.

On the same emulator and Q4 fixture, batched writes reduced the storage step from 262.93 s to 57.36 s (4.58×). The 51,228,733-byte encrypted index and the complete ordered top-100 lists for all 200 prompts matched. Each timing is one run of writing cached vectors. This isolates storage from image decoding, inference and Android UI latency. The batch receipt is `android-docci-q4-batched-001`. Reproduce it by adding `--write-batch-size 8` to the Q4 Android benchmark command.

### Encrypted storage stress, A12

The x86_64 emulator stored repeated host scene vectors under distinct local IDs and one generated text row per ID. Text averaged 3,084 bytes before serialization. Every twentieth row contained 16 KiB. Each run restored the scene payload and checked a sampled text restore. All runs used Q4 and 32-row fixture writes.

| Rows | Scene database | Encrypted scene and text | Scene restore | Warm encode and rank p95 |
| --- | ---: | ---: | ---: | ---: |
| 25,000 | 111.7 MiB | 236.4 MiB | 7.34 s | 240 ms |
| 50,000 | 223.3 MiB | 472.7 MiB | 18.09 s | 318 ms |

The 50,000-row fixture occupied 121,000 pages at 4,096 bytes each. Cached query timings use 19 warm prompts from one run. Repeated vectors make this a storage and query mechanics test. It excludes face and object crops, release assets, transient files, migrations and real-phone timing. The 50,000-row scene and text case leaves about 27 MiB before the plan's 500 MiB design ceiling. The complete mixed index still needs a separate measurement. Raw receipts are in `android-storage-stress-001`.

### Compact scene-vector index comparison

The compact-index build stores scene vectors as scaled int8 values. On the same 10,910-vector emulator benchmark, its encrypted index was 10,723,389 bytes versus 51,228,733 bytes for the float index (79.1% smaller). Hit@1, Hit@5 and top-100 target coverage stayed at 45.5%, 74.5% and 98.5%. Warm median text-encode time was 107.3 ms versus 105.5 ms; ranking was 18.2 ms versus 16.9 ms. However, only 197/200 top-one IDs and 96/200 ordered top-five lists were identical. Equal aggregate hit rates therefore do not mean ranking parity; keep compact storage opt-in until a broader paired evaluation establishes acceptable rank movement.

On the same synthetic 50,000-row scene-plus-text workload, compact storage used 39.1 MiB for scene vectors and 288.5 MiB encrypted overall, compared with 223.3 MiB and 472.7 MiB for float vectors. Scene storage fell 82.5%, and total storage fell 39.0%. Scene restoration was 7.96 s versus 18.09 s, while warm encode-and-rank p95 was 233 ms versus 318 ms. Scene vectors repeat and OCR text is generated, so this establishes storage mechanics, not realistic quality or a full mixed-index size. Raw receipts are `android-docci-compact-index-001` and `android-storage-stress-compact-001`.

## Shortlist reranking replay

The Q4 shortlist contained the intended image for 183 of 200 prompts at depth 25 and 197 at depth 100. An offline replay reordered only those candidates using LongCLIP's exported top-100 ordering. At depth 25, the replay found 154 targets at rank one and 178 in the top five. Compared with Q4's initial top five, it gained 34 queries and lost five. Increasing the shortlist to 100 yielded 190 top-five hits and required four times as many candidates as depth 25 would for a ranker that evaluates each candidate.

The replay used cached rankings from the full host runs and did not execute a shortlist model on Android. It establishes a measurable target for a compact ranker: compare it against direct Q4 and this reference on a separate set. The replay and input checks are reproducible with `python -m tools.model_eval.shortlist_replay --help`.

## Fine-tuning decision

The encoder weights remain pretrained. Fine-tuning the weak quantized graph would confound inference precision with learned improvement. The fixed 200-prompt benchmark has informed graph and ranking choices, so treat it as a regression set for future experiments. Keep the existing evaluation targets and distractors outside training and prompt synthesis.

The current corpus does not support fine-tuning face identity, document-field OCR, exact personal entities, event normalization or landmark recognition. Those subsystems need their own task-specific reviewed training labels. Keep their current pretrained models and improve candidate coverage, ranking, OCR selection or deterministic metadata handling where measured errors point there.

The local DOCCI metadata contains 149 train clusters. All 66 clusters represented in `qual_dev` and all 64 in `qual_test` also occur in train. A cluster-disjoint experiment must explicitly reserve distinct cluster sets for validation and evaluation before building the train subset. Report how many examples remain after the cluster and image deduplication checks.

The MQ05 selection now reserves 40 clusters and 40 images for final evaluation, a separate 40 clusters and 40 images for development, and 800 images in the remaining 69 train clusters. The selector also excludes the DOCCI regression filenames and checks selected image bytes against the regression gallery and all other splits during acquisition. For MQ06, the trainer applies a rank-32 residual to frozen Q4 query vectors and fits it against the original top-25 candidates. CUDA execution and weight updates passed synthetic smoke checks. See [the pilot commands](../tools/model_eval/README.md#train-a-compact-docci-shortlist-reranker).

The verified archive yielded all 880 selected images (452,258,664 retained bytes). The RTX 3050 training run used the 777 of 800 training queries whose exact target appeared in the frozen shortlist. Development and evaluation each contained 40 known targets plus distinct sets of 5,000 unjudged background photos. Development selected epoch 1. Hit@1 rose from 29 to 31 of 40 and Hit@5 stayed at 34. On the separate evaluation split, Hit@1 rose from 27 to 34 and Hit@5 from 37 to 39. The two gained top-five hits had no offsetting loss. These are results on a small exact-caption pilot with a fixed shortlist.

The follow-up RTX 3050 run expanded the training split to 2,000 photos from the same 69 clusters and retained the exact development and comparison sets. The 1,898 training queries with reachable targets were eligible for fitting. No learned epoch improved development Hit@5 or Hit@1 over the frozen baseline. The selector chose epoch 0, yielding the baseline 27 Hit@1 and 37 Hit@5 on the comparison set. This run used the same settings per epoch, so it also increased the number of optimizer updates. It does not isolate whether the result came from the expanded examples or the changed optimization schedule. The 800-photo benchmark checkpoint remains the only trained visual candidate in Android. See `docci-reranker-2k-training-001/results.json`.

The matched-budget correction ran both sizes on the RTX 3050 for 48 full 64-example updates, with development checks every four updates. Both selected the checkpoint at 12 updates. On the repeated 40-query comparison set, the 800-photo fit found 33 targets first and 39 in the top five. The 2,000-photo fit found 35 first and 39 in the top five. Plain Q4 found 27 and 37. The expanded set produced two additional first places at this budget, with equal top-five counts. Independent evaluation still decides promotion. The receipt is `docci-matched-updates-001/results.json`.

The checked Android evaluation export is 131,152 bytes with SHA-256 `7faf3b04a355ce7491e5a3f61aedaff2988d5cac2c299199b440891d82839c97`. It carries the training text graph and checkpoint hashes. The run and export are under `.benchmark-data/model-quality/docci-reranker-training-001`.

The opt-in Android build reproduced the host metrics on the same 40 holdout prompts and 5,040 cached image vectors: 27 to 34 Hit@1 and 37 to 39 Hit@5. Every top-25 shortlist kept the same membership, and all lower positions retained their order. Added reranking time was 1.35 ms warm median and 2.55 ms p95 on the x86_64 emulator. The evidence covers the Android query encoder and reranker with host image vectors. The result and raw timings are in `android-docci-reranker-001`.

On the fixed 200-prompt, 10,910-vector Android regression set, the trained head raised Hit@1 from 45.5% to 53.0% (30 gains, 15 losses) and Hit@5 from 74.5% to 79.5% (19 gains, 9 losses). Top-100 coverage stayed at 98.5%. The result of `android-docci-reranker-regression-001` includes both routes for every query. These regressions and the small independent holdout support keeping the ranker opt-in while evaluating a broader query mix.

The evaluation APK contained the pinned checkpoint. `PhotoContentSearchTest` passed all seven instrumentation cases. The new regression verified that an unscoped, single-vector query can use the trained order while a scoped query or multiple query vectors use the baseline order.

The follow-up run encoded all 40 target photos through Android's image decoder and app CLIP vision path, while retaining 5,000 host-encoded background vectors. Against the host versions, target embedding cosine was 0.950 at the median and 0.844 at the minimum. On this mixed gallery, reranking raised Hit@1 from 32 to 33 of 40 and Hit@5 from 34 to 37, with four gains and one loss at five. Warm reranking took 1.17 ms median and 3.63 ms p95. The Android-target result is `android-docci-reranker-target-parity-001`. This measures the effect of target encoding differences and leaves background parity and phone resource measurements open.

## Specialist training pilots

- Face verification used frozen YuNet and SFace features on PubFig83. The learned scorer accepted 95.79% of genuine pairs and 0.317% of impostor pairs across 25 held-out identities. A calibrated cosine threshold accepted 96.22% and 0.345%, respectively. The learned head traded seven genuine accepts for twelve fewer impostor accepts. Keep it as a host research result until a matched Android evaluation can establish the right operating point. The receipt is `face-verification-calibration-001/results.json`. [PubFig83 source](https://vision.seas.harvard.edu/pubfig83/)
- An eight-class landmark pilot compared zero-shot Q4 and a classifier fitted to frozen image embeddings. The classifier raised top-three hits from 27 to 30 of 32, while first-place hits fell from 27 to 24. The pilot used distinct creator labels across train, development and evaluation. It keeps the zero-shot route for first place pending broader evaluation. The receipt is `landmark-pilot-001/results.json`. [Wikimedia Commons image reuse terms](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia)
- The expanded landmark cohort used 240 photos across 16 known landmarks and eight unknown lookalikes. On the 64 known test photos, zero-shot Q4 ranked 54 first versus 46 for the learned classifier. With thresholds fixed on development, Q4 correctly accepted 36 known test photos and accepted one of 24 unknowns. The learned classifier correctly accepted 15 known photos and accepted two unknowns. The nearest exemplar accepted zero unknowns and correctly accepted 23 known photos. These host results favor Q4 on this cohort. The receipt is `landmark-expanded-001/results.json`.
- On 52 held-out remembered-text queries, a learned scorer raised top-five hits from 28 to 35 for ML Kit and from 33 to 41 for Paddle Tiny against fuzzy matching. ML Kit first-place hits fell from 26 to 25. Paddle Tiny first-place hits rose from 30 to 31. Both engines used frozen OCR output. This tests retrieval ranking and leaves transcription accuracy as a separate issue. The receipt is `ocr-document-ranker-001/results.json`.
- A receipt-field ranker trained on 200 CORD v2 receipts and selected its setting on 100 separate validation receipts. On the 100 official test receipts, it ranked the target first for 97.7% of 348 field queries versus 81.0% for a fixed keyword baseline. This experiment uses the publisher's transcribed tokens and boxes, so it measures field ranking given correct input text. Android OCR output and other document types still need their own tests. The receipt is `cord-field-ranker-001/results.json`. [CORD source](https://github.com/clovaai/cord)
- On the 123-photo Open Images clothing test, a learned CLIP category head reached 0.926 ROC-AUC and 0.858 balanced accuracy. Category presence is independent of the garment's exact identity or the person wearing it. On the COIL-100 exact-item test, the existing DINOv2 and hue policy ranked all 90 known queries first and accepted six of 75 unknowns. It also rejected 23 of 90 known queries at its one-reference threshold. The tested learned LDA heads underperformed the existing policy. The receipt is `visual-specialists-run-001/results.json`.

## Reproduction

The ignored raw data and model graphs are under `.benchmark-data/`. The precision runner and pinned cache inputs are documented in [the model-evaluation README](../tools/model_eval/README.md). The main full-model runs are `docci-200-run-002` and `docci-200-longclip-run-001`; the controlled text-graph runs are `clip-text-precision-sweep-002` and `clip-text-q4-chunk-sweep-002`.

The source benchmark is [Google’s DOCCI project and license](https://google.github.io/docci/). The tested conversion and graph variants are pinned to [Xenova CLIP revision `6b7c523703ae26e39be4b03774966f8571c7f523`](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523). The reference full-precision comparison uses [OpenAI CLIP ViT-B/32](https://huggingface.co/openai/clip-vit-base-patch32).
