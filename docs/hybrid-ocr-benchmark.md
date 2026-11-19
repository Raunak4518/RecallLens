# OCR for private photo search

This benchmark addresses PL062, PL065, PL087–PL088, PL099–PL100 and PL125. It measures whether a user can find a photo by the words they remember. It uses the production Android normalization and whole-token, same-line matching rules for each engine. Benchmark dependencies and model assets load only in the test APK or on the development host.

## Models and APIs to consider

There is no common ranking across photos of signs, dense scanned pages, handwriting, and document layout. The app needs reliable exact text from arbitrary photos under a phone compute budget. The versions below cover the relevant current options and earlier baselines.

| Candidate | Fit for this app | Primary source |
| --- | --- | --- |
| Google ML Kit Text Recognition v2 | Native Android SDK and offline model. Google documents about 4 MB added per bundled script and architecture. Script-specific choices include Latin and Devanagari. Current default. | [Android SDK and footprint](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) |
| PP-OCRv6 Tiny | Open lightweight text detection and recognition pair. 1.5M parameters across both stages. The pinned ONNX pair is 6.24 MB before runtime libraries. Measured candidate for a low-compute phone alternative. | [Paper](https://arxiv.org/abs/2606.13108), [Android deployment](https://www.paddleocr.ai/main/en/version3.x/inference_deployment/cross_platform/android_deployment.html) |
| PP-OCRv6 Small | Open 7.7M-parameter pair. Pinned ONNX models total 31.04 MB before libraries. Useful as a comparison for spending more local compute. | [Technical report and tier measurements](https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html) |
| PP-OCRv6 Medium | Open 34.5M-parameter pair. Pinned ONNX models total 138.59 MB. Runs as a self-hosted worker for this benchmark. | [Technical report](https://arxiv.org/abs/2606.13108) |
| PP-OCRv5 Mobile and Server | Prior lightweight and heavier non-generative reference models. | [PP-OCRv5 paper](https://arxiv.org/abs/2603.24373) |
| Tesseract 5 with `tessdata_fast` | Open CPU baseline with compact integer LSTM language files. The previous Android benchmark used Tesseract4Android on cropped words. | [Fast models](https://github.com/tesseract-ocr/tessdata_fast), [Prior project comparison](dataset-evaluation.md#ocr-engine-comparison) |
| ABBYY Real-Time Recognition SDK | Commercial mobile SDK for on-device text and field capture. Benchmark and licensing would require SDK access. | [ABBYY SDK release](https://support.abbyy.com/hc/en-us/article_attachments/34374378665234) |
| docTR and EasyOCR | Open detection and recognition stacks for an independent server comparison. Both require a separate Android port for this app. | [docTR](https://github.com/mindee/doctr), [EasyOCR](https://github.com/JaidedAI/EasyOCR) |
| SVTRv2 and PARSeq | Scene-text recognition models for evaluating cropped words or lines. Full photos also need a detector and crop pipeline. | [SVTRv2 paper](https://arxiv.org/abs/2411.15858), [PARSeq paper](https://arxiv.org/abs/2207.06966) |
| Granite-Docling 258M | Compact generative document-conversion candidate. Worth comparing on layout-rich documents if that becomes a key task. | [IBM model card](https://huggingface.co/ibm-granite/granite-docling-258M) |
| PaddleOCR-VL 1.6, GLM-OCR, LightOnOCR 2 | Roughly billion-parameter document models to compare on difficult pages, tables and layout. Their document scores cannot predict scene-photo retrieval. | [PaddleOCR-VL 1.6](https://arxiv.org/abs/2606.03264), [GLM-OCR](https://github.com/zai-org/GLM-OCR), [LightOnOCR 2](https://huggingface.co/lightonai/LightOnOCR-2-1B) |
| DeepSeek-OCR 2, olmOCR 2, Chandra | Generative document-focused options for a later server evaluation. Include invented or altered text in the error analysis before integrating generative OCR into exact search. | [DeepSeek-OCR 2](https://github.com/deepseek-ai/DeepSeek-OCR-2), [olmOCR 2](https://github.com/allenai/olmocr), [Chandra](https://github.com/datalab-to/chandra) |
| PolyOCR-Venus, dots.ocr, Nanonets-OCR2-3B | Additional open document parsing systems for multilingual layouts, reading order and structured content. | [PolyOCR-Venus](https://github.com/inclusionAI/PolyOCR-Venus), [dots.ocr](https://github.com/studio-dots-ai/dots.ocr), [Nanonets-OCR2](https://huggingface.co/nanonets/Nanonets-OCR2-3B) |
| Google Cloud Vision, Azure Vision Read | Managed image OCR alternatives for printed text in photos. | [Cloud Vision](https://docs.cloud.google.com/vision/docs/ocr), [Azure Read](https://learn.microsoft.com/en-us/azure/ai-services/computer-vision/overview-ocr) |
| Google Document AI, Azure Document Intelligence Read, Amazon Textract, Mistral OCR 3 | Managed OCR or document extraction for teams that need pages, layout or fields. | [Document AI](https://docs.cloud.google.com/document-ai/docs/enterprise-document-ocr), [Document Intelligence](https://learn.microsoft.com/azure/ai-services/document-intelligence/concept-read), [Textract](https://docs.aws.amazon.com/textract/latest/dg/API_DetectDocumentText.html), [Mistral OCR 3](https://mistral.ai/news/mistral-ocr-3/) |

Use [OmniDocBench](https://github.com/opendatalab/OmniDocBench) when reviewing page parsing and [OCRBench v2](https://arxiv.org/abs/2501.00321) for broad visual text and reasoning. The results here measure retrieval from a gallery with the app's actual search rules.

## Benchmark design

The primary result is the intended photo appearing among the results for a remembered word or phrase. Both queries are frozen before reading any OCR predictions. Protected query hits and absent-query returns are reported separately. A single keyword can identify multiple relevant photos. The benchmark checks target presence and reports extra matches outside the available labels.

The corpus comprises 26 HierText photos with CC BY 2.0 per-image attribution and 72 generated photos, with 12 fixtures per challenge. The manifest records the per-image attribution, hashes and annotation data. The public photos are selected from the intersection of available validation annotations and licensed Open Images metadata. Manifest preparation uses credit, license and orientation metadata, then stages phone-sized JPEGs with a 1,600-pixel maximum edge. The generated photos share document-like content with controlled distortions. The split has 196 public queries, 72 protected synthetic email queries and 12 absent controls. This challenge set does not estimate the average recall of a typical gallery. [HierText](https://github.com/google-research-datasets/hiertext), [Open Images access and licensing](https://storage.googleapis.com/openimages/web/download_v7.html)

The emulator reads each original with bundled ML Kit. The same source goes through both app export modes. Redacted mode includes the app's automatic masks plus an explicit marked region for each fixture. The known synthetic identifier card is marked in generated photos. On natural photos, a separate text region is marked when available and a small corner is used otherwise. Therefore the redacted results test a reviewed copy with a known mask and cannot establish automatic detection recall. Both exact exported copies are approved, encrypted with HPKE, processed by the local development worker, and returned in an encrypted response. Host timing covers OCR without real network latency. Emulator timing is a comparison on Android x86_64 and does not establish ARM phone speed, battery use or thermal behavior.

The standalone Android benchmark runs ML Kit, PP-OCRv6 Tiny and PP-OCRv6 Small separately using the same 1,600-pixel input cap. The Paddle runs use two ONNX Runtime threads and the thresholds from each pinned model configuration. The staging script fixes the upstream demo's recognition channel order to follow the BGR specification in the official model files. The comparison verifies that standalone ML Kit produced the same search results as ML Kit in the paired worker run. [Paddle Android SDK issue](https://github.com/PaddlePaddle/PaddleOCR/issues/18380), [Pinned Apache-licensed SDK](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android)

## Paired export results

| Mode | Public queries that found target | HierText subset | Photos found by either public query | Synthetic email queries that found target | Gained / lost vs ML Kit |
| --- | ---: | ---: | ---: | ---: | ---: |
| ML Kit on original | 156/196 | 18/52 | 78/98 | 71/72 | 0 / 0 |
| PP-OCRv6 Medium on full export | 165/196 | 33/52 | 83/98 | 72/72 | 15 / 6 |
| PP-OCRv6 Medium on reviewed redacted export | 171/196 | 31/52 | 86/98 | 0/72 | 21 / 6 |

All three modes returned no photos for the 12 absent queries. Preserving local results would yield 171/196 public hits with full exports and 177/196 with redacted exports on these fixtures. The redacted worker recovered all 15 queries that the full worker added over ML Kit and six further queries. The app registered an automatic text mask on 40 of the 72 generated photos. The reviewed workflow additionally masked the known card on every generated photo, so 0/72 email hits describe reviewed exports only.

| Remembered text | Target | ML Kit | Full worker | Redacted worker |
| --- | --- | --- | --- | --- |
| party prefer | HierText `26e97e9f6d719dee` | Missed | Found | Found |
| edgel vacuum | HierText `7a7d0f471fe900e2` | Missed | Found | Found |
| lagoon hazel | Generated blur `synthetic-030` | Missed | Missed | Found |
| category 6 | HierText `04c69de1d1bf9892` | Found | Found | Missed |
| summit juniper | Generated perspective `synthetic-053` | Found | Missed | Missed |
| smith s | HierText `a2364c31de73114d` | Missed | Missed | Missed |

Android processed 98 originals and exchanged 196 encrypted request/result pairs with the PP-OCRv6 Medium worker. The host run took 1,001.79 seconds including Android preparation, CPU inference and ADB exchange. A stage-level hosted service latency comparison needs a separate measurement. The fixture manifest SHA-256 is `58282c147d10d56d0de06e028477ec97eba635816fdcffc6e761e9bf2aa84d61`.

## Standalone Android results

The Android 17 x86_64 emulator ran the three local models separately. Times cover OCR processing per photo after bitmap decoding. ML Kit returned the same per-query matches in the isolated and paired runs.

| Local engine | Public queries that found target | HierText subset | Gained / lost vs ML Kit | OCR p50 | OCR p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ML Kit Latin 16.0.1 | 156/196 | 18/52 | 0 / 0 | 693 ms | 3,166 ms |
| PP-OCRv6 Tiny | 165/196 | 26/52 | 9 / 0 | 694 ms | 1,574 ms |
| PP-OCRv6 Small | 165/196 | 27/52 | 11 / 2 | 1,287 ms | 3,089 ms |

PP-OCRv6 Tiny is the strongest low-compute alternative measured here. On this corpus it found nine more queries than ML Kit, kept all ML Kit hits, matched ML Kit's median OCR time, and halved p95. Small tied Tiny's overall hit count and took 1.85 times as long at the median. Keep ML Kit as the shipped default and qualify Tiny on physical ARM Android devices before switching. The benchmark Tiny ONNX weights occupy 6.24 MB and the test APK's ARM64 OpenCV 4.12 binary occupies an additional 23.47 MB. The app currently bundles ML Kit and leaves the Paddle models and test runtime outside the production APK.

The full worker recovered seven queries that Tiny missed. The reviewed redacted worker recovered twelve. Either worker also lost some of Tiny's matches, so a future opt-in result should supplement the local index. Test that gain against Tiny on an independent corpus before adding hosted processing.

The debug build and Android lint completed with zero lint errors and 20 warnings. Both standalone Paddle instrumentation runs and the standalone ML Kit run passed. The focused export and HPKE regression suite passed four checks and skipped the host-dependent case. The paired benchmark exercised the host request and response path independently.

## Reproduction

Prepare the dependencies in the [project README](../README.md) and place the HierText validation and Open Images attribution files under `app/build/datasets` using the [dataset tools](dataset-evaluation.md). This run used PaddleOCR 3.7.0, PaddleX 3.7.2 and the pinned source and model hashes in the staging script.

```powershell
python tools/prepare_hybrid_ocr_corpus.py
python tools/stage_hybrid_ocr_models.py --mobile
python -m pip install --target app/build/ocr-hybrid-runtime paddleocr==3.7.0 paddlex==3.7.2 onnxruntime==1.26.0 cryptography==50.0.2
.\gradlew.bat -PphotolibraryPaddleBenchmark=true :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
$env:PYTHONPATH = (Resolve-Path app/build/ocr-hybrid-runtime).Path
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
python tools/benchmark_hybrid_ocr.py --device emulator-5580 --adb $adb --worker medium --include-mobile
```

The runner requires an emulator with airplane mode on and Wi-Fi disabled. It verifies the APK fixture bytes against the frozen manifest and writes aggregate results, timings and per-query outcomes under `app/build/hybrid-ocr-benchmark`. Use `--mobile-only` to run the three standalone Android candidates. The benchmark does not require a cloud API account.
