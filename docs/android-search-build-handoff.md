# Android search improvement build

The starting tree is preserved on main at c0a6402 and tagged baseline-before-search-improvements. The build is on codex/android-search-improvements.

| Plan tasks | Implementation |
| --- | --- |
| A01–A05, O01–O05 | Offline Android entry point, indexed source lifecycle and legacy OCR evidence, original and bounded rewritten visual prompts, literal-aware positional text lookup, visual and lexical fusion, relative capture dates, and an offline fixture replay evaluator. |
| A06–A07, O11 | Existing quantized CLIP with optional edge views and compact scene entries. ML Kit default and build-selectable PP-OCRv6 Tiny. Optional higher-resolution OCR crops, persistent time slicing, bounded ranking and Android profiling spans. |
| A08–A10, O06–O07 | Model-detected anonymous review groups, confirmation per region, multi-reference ranking, aspect-preserving object crops and existing person–garment and event evidence. Added document routes for PAN, Aadhaar, passport, driving licence, invoices and receipts. |
| O08–O10 | Per-result Wrong result and Found it, Undo, optional reasons, encrypted opt-in judgments, a bounded pairwise reranker on visual similarity and filename evidence, and fallback to direct judgments when holdout evidence is sparse. |

## Build the ARM64 candidate

The Android SDK and staged CLIP, detection, identity and garment assets follow the README. Stage PP-OCRv6 Tiny with python tools/stage_hybrid_ocr_models.py --tiny when the pinned Android OCR SDK and models are absent.

```powershell
$env:JAVA_HOME = 'D:/andriod studio/jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$env:GRADLE_USER_HOME = 'D:/Android/Gradle'
.\gradlew.bat --offline -PphotolibraryAbi=arm64-v8a -PphotolibraryOcr=tiny -PphotolibraryDetailOcr=true -PphotolibraryFairIndex=true -PphotolibrarySceneViews=true -PphotolibraryCompactIndex=true :app:assembleDebug
```

Without the OCR flag, the existing ML Kit path is selected. The detail, scheduling, edge-view and compact-index flags can be toggled independently for paired comparisons. The candidate is a debug APK at app/build/outputs/apk/debug/app-debug.apk. Its first gallery scan will populate any new model revisions. On 19 November 2026 the offline build succeeded with all listed flags. Package inspection confirmed ARM64 libraries, CLIP and Tiny model assets, and an Android manifest without Internet permission.

## Deferred measurements and follow-on work

The user requested build first and runtime testing later. The build establishes compilation and packaging. It leaves retrieval quality, open-set identity and OCR thresholds, ARM query latency, battery, thermal cost, and index migration behavior for A11–A13 and O12. The compact index and additional scene views are experimental. The replay CLI expects curated candidate and judgment JSONL fixtures. Full 10,000-photo mixed-query adjudication remains open.

Language handling uses guarded prompts and rules, and identity verification uses available visual models. General relation recognition, arbitrary document layouts, multilingual OCR qualification, model challengers, and broader learned query rewriting remain follow-on work. Pairwise personalization learns a small ranking function from explicit opt-in pairs. Image and text encoder weights are frozen.


## Query implementation continuation

A05, A09 and O02–O03 now include an image-plane relation route over two explicitly confirmed subject regions. Left, right, above, below and beside return confirmed results only when the boxes meet the relation constraint. Incomplete region evidence remains partial. Depth relations remain unresolved. A single explicit OR between named expressions returns the union of their confirmed sightings.

Build-selectable visual rewrite trials use -PphotolibraryQueryExpansions=typo,synonym,transliteration. Each search preserves its original and can receive one additional experimental expansion. Trial expansion never enters exact OCR or filename lookup. The deterministic dictionaries cover a bounded vocabulary. The ARM64 Kotlin compilation passed with all three rewrite trials enabled. The regression cases are written, with runtime execution and held-out accuracy and latency measurements deferred. The default retains the prior rewrite behavior.


## Identity review continuation

A08 and O07 add Different subject and Undo identity rejection in the review screen. The encrypted v13 schema scopes a correction by entity, source, revision and selected region. Reopening the review restores previous corrections, and source reconciliation prunes entries for changed or removed inventory. Confirming the same region explicitly removes its rejection. The ARM64 sources compile. Regression cases for restart, edit, confirmation and undo are written and await runtime execution. The identity review also supports splitting selected confirmed photos into a new subject and merging two subjects of the same supported kind. A merge carries both names, region provenance and consistent corrections, and checks for contradictory crops or judgments before writing. Undo is available for the last group edit during the current review and is refused if the groups or access changed. Kotlin compilation passed. Transaction and undo regressions are written for runtime verification.


## Script-specific OCR continuation

A07 now supports one optional bundled ML Kit recognizer per build. Use -PphotolibraryOcrScript=devanagari, chinese, japanese, or korean, with none as the default. The additional pass retains existing Latin or Tiny output and adds measurements containing its script. The versioned all policy is the comparison control. Use -PphotolibraryOcrScriptPolicy=sparse to trial the secondary pass only when the primary found little text. Changes to the pack or policy create a distinct OCR revision. Continuous Han and Japanese lookup can find a short phrase inside an OCR line while numeric IDs retain whole-token matching.

All four script packs and the no-extra-script configuration compiled for ARM64. Recognition quality, mixed-script retrieval and device cost remain runtime gates. The integration uses the bundled models documented by [ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).


## Temporal and proximity search continuation

A10 adds inclusive-year dated aliases to the subject editor and encrypted schema v14. A one-year capture query resolves the active assignment before narrowing results to the requested date. Aliases can be removed individually. Overlapping assignments, collisions with permanent names and out-of-range years do not replace an existing mapping. Date-spanning requests leave a dated alias unresolved when no single capture year applies.

MediaStore import dates and folder modification dates are kept apart from capture timestamps. A matching hint can surface a possible result with an unknown capture date. It cannot override a known conflicting capture date. Event searches require a visual candidate before using a date hint in this tier. Nearby results use minimum distance across the valid anchors. For search combining content and location, proximity contributes to reranking the visual shortlist and the 30 km eligibility gate still applies.

The capture, conflict, storage and distance-order regression cases are written. Device tests and ranking evaluation remain deferred.


## Duplicate and version search continuation

A10 and O05 add **Find possible copies** to photo detail and a **Show similar versions** control on broad searches. Copy candidates use whole-image gradient and color signatures, aspect agreement and global embedding similarity. Broad search uses stricter thresholds than the dedicated copy route, fills up to 24 representative slots from its shortlist and retains up to 24 additional versions for expansion. Grouping leaves confirmed identity, literal, location, date, event, document and explicit version queries untouched. The app never uses resemblance as proof of which file is the original.

The encrypted visual payload stores a 40-byte signature plus an eight-byte envelope. The decoder accepts existing full-precision, compact and multi-view entries. A compatible previous model entry stays available for search. Once the index checks the same decoded pixel digest, it can add the fingerprint without opening CLIP. O11 adds a bounded 64-entry visual-only reuse cache keyed by model revision and exact decoded pixel digest, with separate source checks and records for each photo.

Fixtures cover resized images, visually distinct images with the same synthetic embedding, expansion behavior, older payloads and inference-free backfill and reuse. Runtime ranking, false-family, indexing-cost and storage measurements are still open.


## OCR retrieval index continuation

O04 now builds an incremental in-memory inverted index over local and approved legacy OCR. A query verifies an exact phrase against individual OCR lines only for candidates matching its rarest indexed token. Multiple terms can still retrieve a partial candidate across lines. Scope changes filter existing postings. Revised or removed text detaches its previous postings. Filenames remain a separate route, with whole-token matching for numeric queries to protect leading zeros and similar identifiers.

The initial index budget is 250,000 token-to-photo memberships. Documents exceeding 512 distinct tokens or the current budget retain exact lookup through a scan fallback. Continuous Han and Japanese queries also use the scan path for substring semantics. Regression sources cover an empty posting budget, same-line boundaries, leading zeros, CJK and changed source scope. Comparative performance, FTS5 capability and ranking evaluation remain open.
