# Selective local OCR benchmark

This experiment advances PL058, PL061, PL069 and PL071. It tests a low-cost first-pass rule for deciding which new photos to send to OCR. Production search uses bundled Latin ML Kit OCR, and the selector lives in the benchmark.

The gallery contains 180 full HierText validation photos with eligible annotated text and 120 other Open Images validation photos. The latter are a mixed gallery sample and may contain text. Each eligible HierText photo contributes one remembered word and one two-word phrase from a legible, printed, horizontal ASCII annotation. The sampler prefers strings that occur on fewer of the annotated target photos. It excludes files from the earlier staged OCR cohorts. The manifest records the source image, author, license and SHA-256 for each sample, as well as the target photo for each query. [HierText annotations](https://github.com/google-research-datasets/hiertext) · [Open Images source metadata](https://storage.googleapis.com/openimages/web/download_v7.html)

Exhaustive OCR decodes each full photo to the app's OCR input limit of 1,600 pixels on the longest side and runs bundled Latin ML Kit 16.0.1. Selective OCR first decodes each photo to at most 320 pixels on the longest side. It looks for at least four bright-to-dark and four dark-to-bright luminance transitions of 32 levels in a 40-pixel horizontal window, on two rows sampled two pixels apart. If the photo passes, the same 1,600-pixel decode and recognizer run. Very narrow previews bypass the filter. The preview is processed entirely on the emulator.

The two paths run on the same image, alternating which goes first. Both use the same warmed recognizer. Each timing includes image decode, OCR when selected, and insertion into an in-memory search map. The selective total also includes the preview and selection cost. The measure excludes package installation, enumeration, warm-up, encrypted storage writes, and query execution. It measures first-pass work for uncached input. Existing cached OCR would be reused by the app.

A hit requires the specific remembered photo to appear in the result set using the app's normalized phrase matching. The report lists other returned photos separately. The mixed photos have no exhaustive reference transcription, so the benchmark measures target-photo retrieval and incremental misses, with no precision claim for the full gallery.

## Results on Android 17 x86_64

Both APKs built offline, and the paired instrumentation test passed in airplane mode. The 300 photos total 89,683,655 bytes. The manifest SHA-256 is `ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd`.

| Metric | Exhaustive | Selective |
| --- | ---: | ---: |
| Photos sent to ML Kit | 300 | 274 |
| Total decode and processing time | 489.49 s | 482.84 s |
| Intended photo found for a word | 80/180 | 80/180 |
| Intended photo found for a phrase | 63/180 | 62/180 |
| Intended photo found overall | 143/360 | 142/360 |

Selective OCR saved 6.65 s (1.36%). The preview decode and selection cost 3.75 s. The 26 skipped photos represented just 11.60 s of the exhaustive time. Two were from the annotated cohort and 24 from the mixed cohort.

The only additional miss was `q202`, “le moyne.” Exhaustive OCR found the intended photo and selective OCR returned zero results. The preview rule skipped the portrait with repeated pale lettering on the backdrop. Both paths missed the intended photo on 217 queries. [Iggy Dolphin, Adler](https://www.flickr.com/photos/lemoyne/15783819512) · [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/)

Keep exhaustive local OCR. The savings for permanent skipping were small on this gallery, especially since most skipped photos were already cheap to process. Evaluate preview-based prioritization separately if time to early search coverage becomes a bottleneck. The measured mix and emulator timings do not establish real-device battery impact, or coverage for handwriting and non-Latin queries.

Files under `app/build/selective-ocr-results` include the raw per-image timings, aggregate summary, full query CSV, newly missed query CSV, and instrumentation transcript.

## Reproduce

```powershell
python tools/prepare_selective_ocr.py
.\gradlew.bat --offline :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$device = 'emulator-5580'
& $adb -s $device install -r app/build/outputs/apk/feasibility/app-feasibility.apk
& $adb -s $device install -r app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk
& $adb -s $device shell am instrument -w -r -e selectiveOcr true -e class org.photolibrary.app.feasibility.SelectiveOcrTest org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
New-Item -ItemType Directory -Force app/build/selective-ocr-results | Out-Null
& $adb -s $device exec-out run-as org.photolibrary.app.feasibility cat files/selective-ocr-results.json | Set-Content -Encoding utf8 app/build/selective-ocr-results/emulator.json
python tools/report_selective_ocr.py app/build/selective-ocr-results/emulator.json
```

The frozen manifest, staged assets, and per-query results live under the ignored build directory. Preserve the frozen manifest for dataset comparisons. Re-running selection on a checkout with different staged earlier cohorts can change the source IDs.
