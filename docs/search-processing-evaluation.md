# Search quality and indexing performance

This evaluation advances PL058, PL061, PL064, PL065, PL069 and PL071. A useful search has to locate the right photo in a mixed gallery and preserve the details that distinguish it from nearby matches. A Class 10 marksheet is one acceptance case. The same design needs to distinguish a receipt date from a payment date, a sign in the background from the main subject, and a red shirt worn by the requested person from another red shirt in the photo.

## What the app measures today

| Work | Observed evidence |
| --- | --- |
| Scene encoding, 10,000 real Open Images photos | Windows CPU pass took 1,076.68 s. The median image model call took 78.04 ms and the median decode and preprocessing took 5.10 ms. This used the bundled quantized CLIP weights with host preprocessing. |
| Scene retrieval on that gallery | 17/24 queries put a known positive first and 24/24 put a known positive in the top five. The 120 top-five placements include 87 known positives, 10 labeled negatives and 23 unjudged photos. |
| Encrypted visual index, same 10,000 vectors on Android 17 x86_64 | SQLCipher stored 46,940,221 bytes (44.77 MiB) and restored in 2.73 s. The first CLIP query encoding took 1.35 s. Warm text encoding and ranking medians were 7.53 ms and 19.47 ms. Android loaded host-encoded vectors. The test covers neither Android image encoding nor UI, device battery, documents or named matching. |
| OCR recognition and retrieval, 300 real photos | ML Kit found 143/360 intended photos, with 1,230 ms p50 and 5,040 ms p95. PP-OCRv6 Tiny found 160/360 at 639 ms p50 and 2,297 ms p95. Tiny gained 44 queries and lost 27. This is an Android emulator comparison on a frozen cohort and the same decoded input. |
| Permanent OCR skipping, same 300 photos | A 320-pixel preview rule reduced 489.49 s to 482.84 s and added one missed remembered-text query. The rule sent 274/300 photos to full OCR. This result supports testing prioritization before skipping. |

The visual receipts are `app/build/gallery-10k-pipeline.txt`, `app/build/gallery-10k-android/results.json` and `app/build/gallery-10k-android/instrumentation.txt`. The manifest SHA-256 is `3bd9a97f4591b0f26dfd7dfbd43f3be8ca3aefef0824bd61e119c3b68b4bc31d`. See the [paired OCR model comparison](large-ocr-comparison.md) and [permanent skipping experiment](selective-ocr-benchmark.md) for cohort design and measured misses.

## Failure modes for fine details

1. The input can lose the detail. The current bundled Latin recognizer receives a whole image resized to at most 1,600 pixels on its longest edge. Small text, blur, perspective, glare and scripts outside the configured model can leave it unreadable. A targeted crop should use pixels from the original. Upscaling the existing thumbnail restores no lost information. [ML Kit input guidance](https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input-image-guidelines)
2. OCR can see the text and still misread, omit or split it. Store the original OCR string, available geometry, language and model revision. For short natural words, try normalized aliases and conservative spelling variants below exact matches. Names, account numbers and other identifiers need exact evidence or an explicit uncertainty label.
3. OCR can read all the words and search can still miss the photo. The baseline literal matcher expects a normalized phrase on one OCR line. Field-aware retrieval needs nearby label/value geometry, document type and normalized dates or levels. The marksheet implementation tests these ideas with bounded header lines and school-level/holder evidence. A broader extractor should use typed fields and source regions for the value it ranks, with separate roles such as candidate and parent. [DocLayNet](https://arxiv.org/abs/2206.01062) · [DocVQA](https://openaccess.thecvf.com/content/WACV2021/html/Mathew_DocVQA_A_Dataset_for_VQA_on_Document_Images_WACV_2021_paper.html)
4. A tiny visual detail may fall outside the center crop or disappear from a global embedding. Use global similarity for candidates. Check a relevant region with a detector, crop encoder or attribute estimator before claiming the requested exact object, color or relationship. For named entities, keep user-confirmed references separate from model suggestions.

A retrospective check of the 360 frozen queries reproduced all 143 literal ML Kit hits. Of its 217 misses, 213 had at least one query token absent from the OCR output for the intended photo. Four had all the tokens without the requested phrase. Joining every adjacent OCR line recovered zero ML Kit targets on this corpus. The same check reproduced 160 Tiny hits, with 192 missing at least one token, four containing the tokens without the phrase, three containing a joined form such as `dejesus`, and one spanning adjacent lines. The input is `app/build/large-ocr-comparison/emulator.json`. These figures describe the saved outputs and do not separate text detection from misreading. They favor testing image crops and adaptive OCR before broadly loosening exact lookup.

An independent HierText validation holdout used 22 unchanged, individually attributed JPEGs from the publisher archive with no photo ID overlap across the four earlier OCR cohorts. The emulator compared the bundled ML Kit whole-photo pass with padded 2× crops around 11 annotated words 5–14 pixels tall. Both found 6/11 and neither gained a word over the other. Each returned zero hits across 11 controls whose annotations did not contain the requested word. Whole-photo OCR found 10/30 other remembered-text queries. Median OCR inference for the paired words was 3,352 ms for whole photos and 374 ms for known crops. The experiment shows a speed benefit when the location is already supplied. It does not justify adding automatic crop OCR to the production search. All originals in this holdout are already at or below the 1,600 px input limit. Automatic location proposals and genuine high-resolution originals still need evaluation. The fixture manifest is `da61a5708a8cbff7a8fd8a2a112134da99081e3ad6cebc8c664aa02a19862ec1`. See `app/build/staged-production-benchmark/oracle-crop-ocr.json` and the [trial instructions](../.agent-staging/ocr-fresh-holdout/CROP-TRIAL.md).

## Scheduling decision

The previous worker processed eight photos at a time. Each job listed, ordered and fingerprinted the authorized library, checked both encrypted indexes, ran OCR and then CLIP, and queued the next job. Both models decoded separately and closed their sessions after each batch. At 16,247 uncached photos, eight at a time implies about 2,031 batches before retries. Model-stage measurements below isolate the order. They do not measure the worker's repeated setup or I/O. [Coordinator](../app/src/main/kotlin/org/photolibrary/app/index/LocalIndexCoordinator.kt) · [Indexer](../app/src/main/kotlin/org/photolibrary/app/index/LocalPhotoIndexer.kt) · [Worker](../app/src/main/kotlin/org/photolibrary/app/index/PhotoIndexWorker.kt)

| Mode | Expected trade-off | Decision |
| --- | --- | --- |
| Exhaustive serial batches of eight | Predictable cancellation and low live bitmap count, with repeated job, source, table and model setup | Keep as the measurement control |
| Gallery-wide fast pass followed by slower enrichment | Every image gets baseline coverage sooner if the chosen first model is faster. Search types that need the later model start later. | Compare per-capability time to coverage. Give likely documents and an active text search an early OCR budget if a visual-first pass delays them. |
| Bounded time slice with warm model sessions | Amortizes setup. A large publication interval or unbounded slice can delay first results and pause | Test small durable checkpoints within the slice, publish early and retain per-file revision checks |
| Bounded overlap | A single prefetch can overlap I/O with one native inference. Two independent inference lanes may help if a device can run them without resource contention | Test after the serial accounting. Cap queued bitmaps and measure sustained latency and peak memory |
| All models concurrently for every photo | Native thread pools, image allocations and encrypted writes compete. The slowest queue still determines throughput | Keep as a diagnostic comparison on public fixtures |

Run cheap metadata and listing first. Publish results as each bounded batch commits. The new default is a gallery-wide CLIP pass followed by OCR, with a durable stage and cursor. The first visual batch uses eight photos and subsequent batches can use up to 32. With the visual model unavailable or a photo's visual analysis unsuccessful, successful OCR still makes the photo searchable. Existing OCR and embeddings stay usable after upgrading. OCR-sensitive queries such as a new marksheet still depend on when OCR reaches the photo. Candidate promotion for those queries needs its own evaluated design. Run heavier face and object matching, small-text recovery or layout extraction on relevant regions or a shortlist. Preserve encrypted revision-bound outputs so later prompts reuse the work. Tune ONNX threads and execution providers on ARM because the fastest combination depends on the model and hardware. [Android background scheduling](https://developer.android.com/develop/background-work/background-tasks/optimize-battery?authuser=63) · [Android thermal APIs](https://developer.android.com/games/optimize/adpf/thermal?hl=en) · [ONNX Runtime mobile tuning](https://onnxruntime.ai/docs/tutorials/mobile/)

### Processing order benchmark

`PipelineConcurrencyBenchmarkTest` uses all 300 originals from the frozen selective OCR corpus for the full run. The test checks each image hash, then keeps a 3:2 proportion of the 180 text-rich and 120 mixed photos through the gallery. Each requested schedule processes the exact same sequence with production ML Kit and quantized CLIP. The full run uses resident model sessions and one timed pass per schedule. An independent test case keeps the shorter run for checking repeatability and fresh-session initialization. Both compare every OCR output exactly and require equivalent image embeddings. Coverage milestones record completed model output before encrypted storage and UI publication.

After staging the assets and installing both APKs, run this on the test emulator. Omit `pipelineConcurrentLane` to compare the two serial orders.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -s emulator-5580 shell am instrument -w -r -e pipelineConcurrency true -e pipelineFullGallery true -e pipelineConcurrentLane true -e class org.photolibrary.app.feasibility.PipelineConcurrencyBenchmarkTest org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5580 exec-out run-as org.photolibrary.app.feasibility cat files/pipeline-full-gallery-results.json
```

The phone has the marksheet implementation. The acceptance run used generated document photos with real on-device ML Kit in the Android emulator, plus OCR evidence and regression tests. It passed 22 Android and 25 host checks, build and lint. The exact APK receipt and phone install evidence are under `app/build/marksheet-acceptance`. The later scheduler has a separate focused test suite for phase order, durable pause, restart, revision changes, failed OCR, and pending search availability.

The visual-first build passed 27 Android checks with zero skips, 25 host checks, build, and lint. `StagedIndexingTest` verified early visual output, visual cache reads during deliberately blocked OCR, checkpoint recovery after both phase and process recreation, persisted pause hydration, and recovery of encrypted rows saved during an interrupted batch. The focused receipt is `app/build/staged-index-acceptance/receipt.json`. The phone was disconnected at the end of this run and had the prior marksheet build.

| 300-photo emulator schedule | Visual outputs 8 / 100 / 300 | OCR outputs 8 / 100 / 300 | Both passes complete | CLIP query encoding p95 while working | Sampled peak PSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Eight-photo OCR + CLIP windows | 8.98 / 141.48 / 395.93 s | 8.21 / 135.61 / 395.56 s | 395.93 s | 19.2 ms | 357.3 MiB |
| Entire CLIP pass, then OCR | 0.71 / 9.23 / 27.97 s | 36.03 / 154.10 / 395.69 s | 395.69 s | 22.6 ms | 357.8 MiB |
| One concurrent lane for each model | 0.93 / 11.65 / 35.67 s | 9.09 / 131.15 / 373.58 s | 373.58 s | 22.7 ms | 359.8 MiB |

All three schedules ran bundled Latin ML Kit OCR on all 300 photos. Each returned at least one OCR line for 208 images. The OCR outputs agreed exactly and every compared CLIP image cosine was at least 0.9999. CLIP-first improved full visual coverage by 14.2×, with total time effectively unchanged and the eighth OCR result about 28 seconds later. The concurrent variant finished both stages 5.6% sooner in this emulator run, with slower full visual coverage than CLIP-first. These are individual timed warm passes on the same host and emulator. They do not establish production UI timing, sustained ARM memory or contention, or battery impact. Evidence is in `app/build/pipeline-full-gallery/results.json` and its instrumentation transcript.

### Production coordinator on the same 300 photos

`StagedProductionBenchmarkTest` drove the production coordinator directly from the same fixed public source data. It used the app's decoder, bundled ML Kit and CLIP, and a fresh encrypted catalog. This run includes work lost from the component comparison, including encrypted catalog operations, per-photo revision checks, and model restarts. It supplies fixture metadata directly and excludes Android MediaStore enumeration, WorkManager queue delay, and UI rendering.

| Durable milestone | Visual search evidence | OCR evidence |
| --- | ---: | ---: |
| First eight photos | 11.03 s | 147.27 s |
| At least 100 photos | 47.99 s | 405.06 s |
| All 300 photos | 128.21 s | 909.74 s |

Both models processed all 300 photos, with visual evidence for every photo committed before the first OCR batch. There were 11 visual and 38 OCR model opens. The 2.07 MiB encrypted database reopened both caches in 1.95 s with zero image decodes or image-model calls. Reopened output and rankings matched the original indexes; the exact OCR matcher found 143 of 360 remembered-text targets. These measure one cold coordinator pass and one subsequent cache restore on emulator-5580. The [production benchmark source](../app/src/androidTest/kotlin/org/photolibrary/app/feasibility/StagedProductionBenchmarkTest.kt) and `app/build/staged-production-benchmark/staged-production.json` preserve the setup, timings and assertions.

A separate WorkManager check used 25 generated MediaStore photos on the same tested build. The app resumed a partially completed job after process restart, retained the previously saved text rows, and proceeded after a second restart with indexing paused until it was resumed explicitly. The test compared original bytes and removed only its own fixtures. Receipt and phase transcripts are in `app/build/staged-production-benchmark/worker-restart`.

### Encrypted checkpoint overhead

The same emulator APK compared the original control implementation with a single-connection transaction. Each implementation processed the same 49 checkpoint batches on its own warmed, disposable SQLCipher database. Both preserved the legacy paused-row migration behavior and produced the same digest for the complete sequence of checkpoint decisions. The benchmark ran once per variant in original-first order.

| Metric | Original | Single connection |
| --- | ---: | ---: |
| Median update per checkpoint | 1,680 ms | 913 ms |
| 95th-percentile update | 1,937 ms | 1,090 ms |
| Entire checkpoint-only workload | 215.82 s | 185.30 s |

The transaction saves a connection open by reading and updating the checkpoint on the same handle. It checks that the stored cursor and pause state still match before writing. This compares checkpoint handling alone, excluding image inference, output storage, scheduling, and UI. The 14% measured improvement applies to this workload. The report is `app/build/index-control-overhead/results.json`.

The transaction is integrated in `IndexingControl.kt`. The resulting app passed all five staged-indexing Android checks, 25 host checks, build and lint, with no skips. Its APK signature verified. The final receipt is `app/build/checkpoint-transaction-acceptance/receipt.json`. It finished at 21:27:26 IST, 21 seconds after the four-hour deadline of 21:27:05 IST. The preceding 27-check APK is preserved at `app/build/staged-index-acceptance/app-feasibility.apk`.

## Next experiments in order

1. Instrument enumeration, DB open and reconcile, model cold start, decode, inference, write, search publish, and warm query time. Compare the current eight-photo batches with a whole-gallery fast pass followed by a slow pass. Use the same public originals and track when each search capability covers the first 8, 100 and entire gallery. Keep pause, resume, process death, permission revocation and source edits in the regression set.
2. Compare warm bounded slices and capped overlap to the best batch order. In the staged design, keep a separate durable cursor and existing outputs for each stage, so progress in one stage survives a pause or a model change in another. Keep queued bitmaps bounded and give foreground searches a way to promote candidates. Measure photos per minute, UI query p50/p95 during indexing, CPU and peak memory. Repeat on physical ARM for sustained battery and temperature.
3. Compare whole-image ML Kit, PP-OCRv6 Tiny and a targeted crop retry. Label the cause of each miss before aggregating. Use an independent candidate set with documents, screenshots, receipts, labels, handwriting and text in natural scenes. Include rotated and low-contrast originals, relevant negatives and exact identifiers. [HierText](https://github.com/google-research-datasets/hiertext) · [SROIE tasks](https://arxiv.org/abs/2103.10213)
4. Freeze mixed-query judgments across the 10,000-photo background gallery and enough hand-labeled positives, lookalikes and absent targets. Score plain remembered text, field combinations, small visual evidence and person/object identity separately. Report top-1, top-5, false strong matches, coverage and encrypted bytes. Require measured value on these tasks before adding more model weight to the app.
