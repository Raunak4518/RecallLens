# Larger local OCR comparison

This experiment advances PL058, PL061 and PL071. It compares the production ML Kit Latin adapter with a PP-OCRv6 Tiny adapter implementing the same PhotoProcessor contract. The app default remains selected through its existing composition.

The input is the frozen 300-photo selective OCR gallery. Its manifest SHA-256 is `ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd`. It contains 180 HierText photos with 360 remembered queries, plus 120 mixed Open Images photos. Each query has a specific target image. The test verifies the manifest and each image checksum before inference.

Each image is decoded once with Android ImageDecoder to a software bitmap bounded to 1,600 pixels on the longer side. Both adapters receive that same bitmap. Model execution order alternates by image to reduce systematic first-engine bias. Four warm-up photos precede the measured pass. The report records common decode time, independent inference time, recognized lines, completeness and all matching gallery IDs for every query. Text matching uses the production normalization and phrase matcher. Both adapters cap output at 256 nonempty lines and 16,384 characters. Setup and warm-up are recorded separately.

Tiny uses two ONNX Runtime threads with detection settings fixed before this run. Thresholds are 0.2 for detection and 0.4 for boxes, with unclip ratio 1.4 and maximum image edge 1,600. Recognition uses the pinned model dictionary and corrected BGR preprocessing already used in the smaller comparison. Its synchronous PhotoProcessor adapter bridges the SDK's coroutine API, and that bridging overhead is included in inference time.

The detector SHA-256 is `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`. The recognizer SHA-256 is `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6`. The recognition YAML SHA-256 is `66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1`. These match the staged assets and the pinned staging script.

The primary result is target-photo retrieval for each remembered word or phrase. Gained queries, lost queries and failures shared by both engines are reported independently. Per-image inference p50/p95, total inference, common decode cost and incomplete outputs accompany accuracy. The report also compares ML Kit's full result sets with the previous exhaustive run when that artifact is available. Annotated targets cover printed horizontal Latin text. ARM battery, thermal performance, mixed-script coverage, handwriting and gallery-wide precision require additional evaluation.

## Reproduction

Use the existing frozen corpus and staged mobile assets. Rebuilding the corpus can change its manifest because the sampler excludes earlier staged cohorts.

```powershell
.\gradlew.bat --offline -PphotolibraryPaddleBenchmark=true :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -s emulator-5580 install -r app/build/outputs/apk/feasibility/app-feasibility.apk
& $adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk
& $adb -s emulator-5580 shell am instrument -w -r -e largeOcr true -e class org.photolibrary.app.feasibility.LargeOcrComparisonTest org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
New-Item -ItemType Directory -Force app/build/large-ocr-comparison | Out-Null
& $adb -s emulator-5580 exec-out run-as org.photolibrary.app.feasibility cat files/large-ocr-comparison.json | Set-Content -Encoding utf8 app/build/large-ocr-comparison/emulator.json
python tools/report_large_ocr.py app/build/large-ocr-comparison/emulator.json
```

The report tool writes summary.json, remembered-queries.csv, gained-queries.csv, lost-queries.csv and tiny-missed-queries.csv beside the raw emulator output. Model and dataset assets stay under ignored directories. Run the test on the emulator with networking disabled, and preserve its instrumentation transcript with the result files.

## Execution status

The paired Android17 x86_64 run passed on the frozen300-photo manifest. ML Kit found143/360 target queries, Tiny160/360. Tiny gained44 queries and lost27. The previous ML Kit run and this run had identical query match sets.

| OCR time | ML Kit | Tiny |
| --- | ---: | ---: |
| Total | 499.30s | 268.17s |
| Median per image | 1,230ms | 639ms |
| 95th percentile | 5,040ms | 2,297ms |

Both engines used the same decoded input. Tiny setup took1.33s and ML Kit adapter setup8ms, with warm-up measured separately. These emulator results establish a candidate with better aggregate recall and lower measured latency, while its27 lost queries require review before changing the default. Preserve all gained/lost CSV rows and physical ARM qualification as separate acceptance evidence.

All27 lost-query cases now have a visual and saved-output review. Small text omissions, spelling errors and joined-token failures appear alongside partial/occluded benchmark queries. The original metrics are preserved. See [loss-cohort review](ocr-loss-review.md) and tools/create_ocr_loss_review.py. This inspection does not provide a fresh hold-out result or ARM qualification.

## Retrospective routing analysis

Run tools/evaluate_ocr_routing.py against the saved paired outputs. It fixes150 tuning and150 held-out photo IDs using a SHA-256 ordering. The held-out group includes174 target queries. The original comparison corpus was already inspected, so this is a retrospective policy analysis. A fresh cohort and a scheduled device run are still required before a deployment claim.

| Policy on held-out photos | Target queries found | Summed recorded decode/inference components |
| --- | ---: | ---: |
| ML Kit | 67/174 | 235.09s |
| Tiny | 82/174 | 131.59s |
| Both, union of results | 92/174 | 364.02s |
| Tiny, then ML Kit when Tiny returns at most64 characters | 84/174 | 162.20s |
| Tiny, then ML Kit when Tiny returns at most256 characters | 90/174 | 239.10s |

The empty-output and16-character fallback rules added no held-out hits. This gallery supplies limited support for an empty-output fallback as a quality strategy. The component sums exclude route evaluation, new session setup, scheduling, storage and power costs. Keep every query outcome visible when assessing a policy. Output is app/build/large-ocr-comparison/routing-analysis.json.
