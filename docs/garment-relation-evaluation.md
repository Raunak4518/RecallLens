# Person and garment relation evaluation

This implementation advances PL064 and PL071 with a parser contract, a YOLOX person adapter, conservative face-to-person association and clothing color evidence. SQLCipher persistence and the normal ViewModel analysis/query path now pass emulator checks. The production adapter uses Google's Apache-2.0 Selfie Multiclass 256x256 model through LiteRT1.4.2. The SegFormer candidate below remains a research-only experiment and is not bundled. Larger accuracy and color calibration remain open.

## Integrated model evidence

Pinned model SHA-256: c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0. tools/stage_app_garment.py records source, model card and notices. The parser uses clothing class4 in the upper154/256 crop rows. It supports color evidence, not a shirt subtype or a spatial relationship.

The four attributed real photos contained seven annotated people. Four produced Found records, with three dominant colors agreeing with coarse annotations; three were withheld for overlapping ownership or missing faces. One gray/beige example was classified orange. A green example split into green0.494/yellow0.452. These are smoke cases, not a broad accuracy estimate. Raw evidence is app/build/garment-evaluation/emulator.json.

Named color search uses current evidence for the explicitly confirmed person's marked face and the50% color-area rule. Content search can additionally show up to five related candidates with at least25% area; these heuristic rules are not calibrated probabilities. Unconfirmed identities never enter Named search. A real black-clothing photo passed confirmation, explicit analysis, encrypted reopen, cache-only repeat and black/red query checks across two ViewModels. See execution-status.md for the latest regression receipt.

## Expanded emulator evaluation

The frozen cohort contains 24 attributed CC BY 2.0 photos from the existing Open Images and HierText gallery. Sixteen photos contain 56 marked people, and eight photos serve as controls without people. One reviewer marked visible upper clothing and approximate face boxes before the expanded run. Some people have multiple visible colors. Four images come from the earlier smoke test. Each detection can match at most one marked face, using greedy matching by box intersection over union of at least 0.25. The test verifies each image hash and records the bundled model hashes and policy. The annotations cover visible clothing within a photo. Cross-photo identity and product query latency have separate tests.

Both runs use YuNet, YOLOX-Nano and MediaPipe with the app's 1,600-pixel decode bound. The candidate keeps the single-owner face requirement and discards garment pixels inside other detected person boxes. The installed policy withholds garment evidence for substantial box overlap.

| Measured outcome | Installed policy | Exclude neighboring boxes |
| --- | ---: | ---: |
| Annotated faces detected | 51/56 | 51/56 |
| People with garment evidence | 17/56 | 43/56 |
| Dominant color agrees with a visible label | 12/17 | 31/43 |
| People with an expected color covering at least 50% of the mask | 11/56 | 29/56 |
| False alerts at 50% across 422 negative color queries | 5 | 11 |
| Precision among color alerts at 50% | 11/16 (68.8%) | 29/40 (72.5%) |
| Alerts for a color marked only on another person | 2/86 | 2/86 |
| Controls with a color alert | 0/8 | 0/8 |
| Total emulator model time across all photos | 9.53 s | 21.66 s |

Model timings exclude decoding, setup, encrypted storage and rendering. They come from individual emulator runs. The same candidate previously took 17.54 s with identical counts. The palette lacks beige, brown and pink, which account for nine labels. The overlap rule explains 29 of 39 baseline misses for garment evidence. The candidate reduces overlap misses to three and modestly improves precision among emitted alerts. It retains all five baseline false alerts and adds six. The two alerts that coincide with another person's labeled color persist in both runs. This coincidence alone does not establish which person's pixels entered the mask. The installed policy remains the default while the candidate awaits evaluation with independent labels and fresh photos.

The results screen identifies the person's confirmation and the color estimate separately. A clothing match requires a confirmed face reference and the existing area threshold. The workflow test checks the resulting evidence group, encrypted reopen, cache-only repeat and rejection of an absent color. It also opens the app, enters a Named clothing query through the accessibility tree and verifies the result card reads “Confirmed person · estimated color.” See app/build/garment-screen-regression.txt.

Reproduce with `tools/run_garment_evaluation.py` using `--adb` and `--serial emulator-5580`. Use `--overlap-policy EXCLUDE_OTHER_PEOPLE` for the candidate. The runner stages each photo in private test storage, validates the report against the manifest, and removes its fixture files. Run `tools/report_garment_evaluation.py` with each output's `emulator.json` to regenerate the summary and per-query outcomes. The cohort is `evaluation/garment-cohort-v1.json`. Artifacts are under `app/build/garment-cohort-v1` and `app/build/garment-cohort-v1-exclude-other-people`.

## Earlier research experiment

The existing OnnxRegionModels adapter deliberately excludes COCO person boxes and returns YuNet faces for SubjectKind.PERSON. Face boxes cannot define the garment area. OnnxPersonDetector reuses the pinned YOLOX-Nano weights to return full person boxes without changing existing naming and identity detection.

GarmentEvidence associates a face to one person box only when at least 90% of the face lies within the person and the face center is in its upper 55%. Ambiguous owners, multiple faces assigned to one body, or person boxes overlapping by more than 15% of the smaller area return explicit missing evidence. These thresholds are conservative rules requiring calibration. They have no measured precision or recall claim.

The parser operates on the detected person's crop. Color is measured only where the garment model predicts upper-clothes with softmax confidence at least 0.60. A minimum mask area and pixel count are required. Each result records the parser artifact and preprocessing revision, face and body regions, mask sample count and color fractions. Upper-clothes is the model's category and can include garments beyond shirts. Do not label the result a confirmed shirt or use color as identity.

## Candidate and license

The evaluated model is mattmdjaga/segformer_b0_clothes at revision 7142c7f9d2be9ee39a47bd2091ad9d5fbe61bb1b. The author describes a SegFormer-B0 fine-tune on ATR with 18 parsing categories, including upper-clothes. It has approximately 3.72M parameters and 14.9 MB safetensors. Its published preprocessing resizes RGB input to 512 with ImageNet mean and standard deviation. The Android adapter produces an 18 by 128 by 128 output grid. [Model card](https://huggingface.co/mattmdjaga/segformer_b0_clothes) · [Preprocessing](https://huggingface.co/mattmdjaga/segformer_b0_clothes/blob/main/preprocessor_config.json)

The model card declares MIT, while the upstream SegFormer license restricts use of its work and derivatives to non-commercial research or evaluation. Keep this candidate in evaluation until the applicable weight rights are resolved or a commercially compatible alternative is selected. The staging script stores weights, export and notices only under the ignored build directory. It never copies them into production model assets. [Upstream license](https://github.com/NVlabs/SegFormer/blob/master/LICENSE)

## Current checks and integration

Run python tools/stage_garment_evaluation.py after installing ONNX in the ignored app/build/garment-export-runtime directory. The script pins the source revision, uses safetensors, records SHA-256 values and exports a fixed 512 input graph. The graph is 15,040,661 bytes in this run. A desktop ONNX Runtime smoke test produced upper-clothes masks of 6,205 and 3,298 grid pixels from manually selected crops in the two-person Le Moyne photo. These counts verify output plumbing only. They do not validate automatic association or retrieval accuracy.

GarmentEvidenceTest has three controlled regression tests. They catch color leakage from another person's clothing/background, ambiguous ownership, overlapping people, absent person detections and empty garment masks. The fake parser returns explicitly supplied masks so those tests isolate relation logic. The parent coordinates compilation and emulator execution.

An evaluation entry point must map the pinned YOLOX model and the garment export, run person and face detection, then call describePersonGarment for each face. Match the face to the requested confirmed person using identity evidence. Persist the resulting relation only with the source revision and every dependent model version. Recheck the source before commit and purge on permission loss or edits. Enable a garment query only after the model's device test and relation cohort pass. Existing planPhotoQuery continues to report missing garment relation evidence until that integration is complete.

GarmentEvidenceStore now stores per-photo found and missing records in SQLCipher with source revision and face, person, parser and color-policy revisions. It refuses stale commits, supports lifecycle pruning, and requires a confirmed PERSON face-region reference before cached color evidence can satisfy that person's color query. Whole-photo references lack the ownership information needed for this operation. Cache reads perform no inference.

GarmentModelEvaluationTest reads its model and manifest from targetContext.filesDir/garment-evaluation. Run prepare_garment_evaluation.py to stage the ignored device directory, transfer it into the test application's files directory, then run the instrumentation with garmentEvaluation true. It reports four real source images with manually inspected coarse face boxes and expected upper-clothing colors. The report counts misses from face detection and ownership as well as colors when evidence exists. This small cohort exercises real paths and supplies failure examples. It cannot establish broad accuracy.

GarmentEvidenceStoreTest supplies three additional regressions for encrypted persistence, dependency invalidation, source mutation during commit, revoked scope, and confirmed region ownership.
