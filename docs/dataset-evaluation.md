# Public dataset evaluation

This is evidence for PL007, PL011, PL015, PL023, PL058 and PL071. Evaluation uses existing public benchmark images on the workstation and its Android emulator. Personal photo libraries are outside the corpus.

## Datasets and permissions

| Dataset | Official source and size | Subset used |
| --- | --- | --- |
| Open Images validation | 41,620 validation images. The [official download page](https://storage.googleapis.com/openimages/web/download_v6.html) links the image records and verified labels. The [license section](https://storage.googleapis.com/openimages/web/factsfigures_v7.html#licenses) lists CC BY 4.0 for Google annotations and CC BY 2.0 for eligible photos. | 96 unique JPEGs. Six classes with eight human-verified positives and eight verified negatives each. |
| HierText validation | 1,724 validation images. The [authors' release](https://github.com/google-research-datasets/hiertext) gives CC BY-SA 4.0 for HierText and identifies Open Images as the photo source. | 30 printed line crops from 12 photos. The source IDs also occur in the Open Images validation metadata. |

Admit images only when the original attribution row includes a creator, landing page, and the CC BY 2.0 declaration. Record the original URL, author, title, license, image ID, and SHA-256. These are checks against the published per-image records. Verify the original license before any redistribution, as the Open Images publishers advise. Keep the downloaded images and derived OCR crops local, exclude them from the regular app and release artifacts, and use this subset for evaluation. Training and cloud-upload permission decisions remain separate.

## Retrieval

The frozen sample uses a deterministic hash order per class and relevance label. Unknown labels do not act as negatives. The evaluated queries are class requests such as “a photo of a cat.” Each is ranked only against its own 16 judged images. The [pinned quantized CLIP candidate](g0-feasibility.md#pl015pl018-feasibility-results) ran with ONNX Runtime 1.26.0 and two CPU threads on the workstation.

| Query class | Top result relevant | Precision at 5 | Average precision |
| --- | --- | ---: | ---: |
| Airplane | Yes | 0.60 | 0.822 |
| Bicycle | Yes | 0.60 | 0.727 |
| Cat | Yes | 1.00 | 1.000 |
| Dog | Yes | 1.00 | 0.986 |
| Flower | Yes | 0.60 | 0.772 |
| Pizza | Yes | 1.00 | 0.928 |

Mean average precision was 0.873. Mean precision at 5 was 0.80. The run took 14.16 seconds for session creation, image preprocessing, both encoders, and ranking, excluding downloads and annotation filtering. The report and per-image attribution are in `app/build/datasets/open-images-retrieval.json`.

This small balanced sample measures category ranking. It does not establish retrieval quality across a large personal library, known-item search quality, or the performance of arbitrary descriptions. Model pretraining overlap is unknown. Use a separate evaluation set for final comparisons.

## OCR method

Select printed, legible, horizontal Latin text from HierText validation. Use the annotated line polygons to crop matching Open Images photos, preserving the original source attribution. Align coordinates to the available image dimensions. Use up to three qualifying lines per image. Record the transcriptions in the local benchmark manifest.

Score normalized text with Unicode NFKC, collapsed whitespace, and case-insensitive comparison. Report exact line matches, blank predictions, and micro character error rate. The character error rate is total Levenshtein edits divided by total ground truth characters. Warm latency excludes the first call. This evaluates recognition with the location already known. A full-photo OCR test still needs to score detection, line matching and recognition together.

## OCR engine comparison

Bundled ML Kit Latin 16.0.1 and Tesseract 5.5.1 through Tesseract4Android 4.9.0 ran offline on the same Android 17 x86_64 emulator with airplane mode enabled and Wi-Fi off. Both engines received the same 30 frozen line crops in the same order. The preparation script pads crops to ML Kit's 32-pixel minimum side while preserving the original glyph pixels. The runner verifies the manifest and every crop against their recorded hashes. Tesseract used a pinned [fast English model](https://github.com/tesseract-ocr/tessdata_fast/blob/65727574dfcd264acbb0c3e07860e4e9e9b22185/eng.traineddata) in single-line mode because the input already isolated individual lines. See [ML Kit's input guidance](https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input-image-guidelines) for its image size and quality recommendations.

The runner tested each engine three times in alternating order and force-stopped the app before each run. All six tests passed and each engine had identical accuracy across its repeats. The startup metric includes engine initialization and the first recognition. The warm metrics pool the 87 recognition calls after dropping the first call from each run. Timings exclude crop decoding and staging the model file on disk.

| Metric | ML Kit Latin | Tesseract fast English |
| --- | ---: | ---: |
| Normalized exact lines | 14 / 30 (46.7%) | 7 / 30 (23.3%) |
| Blank predictions | 2 / 30 | 10 / 30 |
| Character error rate | 36.47% (124 edits) | 65.59% (223 edits) |
| Startup and first result, median | 855 ms | 371 ms |
| Warm p50 / p95 per crop | 138 ms / 437 ms | 17.5 ms / 52.5 ms |
| All 30 crops with initialization, median | 6.25 s | 0.97 s |

Carry ML Kit forward for the next app validation. It made fewer errors on 19 lines, Tesseract made fewer on 3, and they tied on 8. Tesseract's median warm latency was about 8× faster, but its 10 blank lines would be unavailable for text search. Keep both benchmark paths so the choice can be revisited. Both error rates on this sample leave room for improvement. The release decision still needs a larger held-out set of full photos that tests text detection, recognition and search usefulness. These timings describe the emulator.

## Full-photo OCR and search

The Android 17 x86_64 emulator ran bundled ML Kit Latin 16.0.1 with airplane mode on and Wi-Fi off. This cached pilot used 18 uncropped Open Images copies from HierText validation. Six target photos were outside the previous crop experiment. The twelve previous source photos served as search distractors. All 18 JPEGs were already on disk at roughly 1,024 pixels on the long edge. The planned 60-photo test remains pending.

The runner froze the images, text queries, relevance labels and file hashes before inference. It chose one annotated word per target and one two-token phrase where available. Ten absent words and ten absent phrases came from other HierText photos and had no normalized match in the gallery annotations. The emulator used the app's bounded decoder, OCR adapter and text matcher. Search tested contiguous words within a single line. Case and punctuation differences were normalized. Results were ordered by photo ID for this test.

| Metric | Result |
| --- | ---: |
| Target at rank one with a single word | 4 / 6 |
| Target at rank one with a two-token phrase | 2 / 4 |
| Target at rank one with at least one query | 5 / 6 photos |
| Absent queries with no results | 20 / 20 |
| Returned photos without a matching annotation | 0 / 6 returns |
| Supported reference lines with a matching box | 58 / 234 (24.8%) |
| Geometrically matched lines recovered exactly | 36 / 58 (62.1%) |
| Character error on matched lines | 9.0% |
| Character error counting all missed reference lines as deletions | 74.2% |
| Warm OCR per photo, median / p95 | 1.82 s / 10.17 s |
| Decode and OCR for all 18 photos | 45.35 s |
| In-memory search per query, median / p95 | 2.79 ms / 7.64 ms |

The line score matches one prediction and one eligible reference at a time using axis-aligned box overlap of at least 0.5 IoU. It includes legible horizontal printed lines with ASCII text on the six target photos. Predictions outside those references do not contribute insertions to the character error metric, and line splits can affect box matching. The search result measures exact annotation-derived snippets from a small gallery. Larger and higher-resolution fixtures, realistic recalled wording and the encrypted search index still need separate validation.

To repeat this run with the same locally cached files, use the Android setup and emulator switches below. Preparation defaults to existing files. The larger corpus requires an explicit `prepare --download`.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
python tools/benchmark_full_photo_ocr.py prepare
.\gradlew.bat --offline :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
& $adb -s emulator-5580 shell cmd connectivity airplane-mode enable
& $adb -s emulator-5580 shell svc wifi disable
python tools/benchmark_full_photo_ocr.py run --adb $adb --serial emulator-5580
```

The reference manifest with attribution is `app/build/datasets/hiertext-full-reference.json`. The test writes per-image OCR and search metrics to `app/build/datasets/hiertext-full-ocr-search.json`. Both files and the staged fixtures are in the ignored build directory.

## PL011 acceptance conditions

Apply these conditions to G1 on a frozen set, with both model and source inputs versioned. Incomplete gates remain open in the checklist.

| Area | Evidence required |
| --- | --- |
| Retrieval | Compare filename/metadata, OCR and semantic ranking on the same judged collection. Use at least 50 grouped known-item queries and 10 absent-item queries for the first held-out comparison. Report success at 1/5/10 and per-category failure counts. Require an improvement from image-text retrieval on visual tasks and preserve exact metadata and text search behavior. |
| OCR | Score reference crops and full photos separately. Report micro character error rate, exact text recovery, blanks and full-photo line detection. Separate unreadable text and unsupported scripts. Select quality thresholds after measuring both candidate and reference pipelines on development data, then freeze thresholds before held-out evaluation. |
| Source lifecycle | For known edits, moves, trash, removal, restoration and access loss, compare the expected accessible revisions against the index after reconciliation. Require zero stale results and zero changes to originals. Recover after an interrupted run without duplicating indexed output. |
| Local privacy | Verify an offline run succeeds. Audit the shipping manifest, dependency traffic and persisted files separately. Require encrypted OCR, vectors and sensitive catalog state, wrong-key rejection without destructive recovery, and zero source-image or query uploads in LOCAL mode. |
| Runtime | On the reference hardware, record cold and warm p50/p95, memory, thermal state, and indexing throughput against agreed 1k and 10k library sizes. Require completion without an ANR or out-of-memory failure, with repeatable progress and resumption. Freeze user-facing latency and resource budgets after the first hardware baseline. Record emulator timing separately. |

The above conditions specify what evidence is needed. The current Open Images and HierText runs qualify candidate tools on small public subsets.

## Reproduce

Use Python with Pillow, NumPy, ONNX Runtime and tokenizers. From the project root:

```powershell
python tools/prepare_g0_clip.py
python tools/benchmark_public_datasets.py retrieval
python tools/benchmark_public_datasets.py ocr
```

The harness prints a retrieval summary and stores annotations, photos, attribution records and reports under the ignored build directory. For the OCR comparison, start an Android emulator and set up the JDK and SDK as shown in the [README](../README.md). The comparison stages the selected crops, their references, and the pinned Tesseract model into the instrumentation APK. Replace `emulator-5580` with the serial of your running emulator as needed.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
python tools/compare_ocr_engines.py prepare
.\gradlew.bat :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
& $adb -s emulator-5580 shell cmd connectivity airplane-mode enable
& $adb -s emulator-5580 shell svc wifi disable
python tools/compare_ocr_engines.py run --adb $adb --serial emulator-5580
```

The runner writes aggregate results, paired line errors, and individual timings to `app/build/datasets/ocr-engine-comparison.json`. The installed benchmark dependency and model are scoped to instrumentation tests.
