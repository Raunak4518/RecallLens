# Real-gallery evaluation

The user requested thousands of real images and broader prompt coverage while keeping tests on the emulator and prioritizing on-device processing. Physical-phone tests remain unauthorized; no public hosting was provisioned.

## Frozen corpus

The Open Images validation selection contains 2,000 verified JPEG files totaling 604,963,729 image bytes. Two timed-out downloads were recovered using their original IDs; no failed photo was replaced. The manifest records per-image source URL, publisher-attributed author/license, file SHA-256, verified positive/negative labels and official visual relationships. The image license fields come from the publisher metadata and were not independently rechecked on each original hosting page. Annotation and image licenses are recorded separately.

Source/content grouping found no duplicate groups. The frozen split has 400 validation and 1,600 test images. Sixty queries comprise 20 category, 20 multi-category conjunction and 20 official relationship cases. Categories/conjunctions include explicit human-verified negatives. Relationship queries have annotated positives but no complete negative labels, so unknown results cannot be called false alerts or used to claim perfect precision. The manifest SHA-256 is 5e74aa1abb016007ab3a334b6be81c529d054330d0224883c918c6a3171a4fc6.

The corpus supports scene/object/relationship evaluation. It does not invent ownership, named identities, GPS or remembered-text ground truth. Those require separate identity, location and OCR cohorts. Passing these queries cannot prove that every natural-language prompt is understood.

Preparation tools are tools/large-gallery/prepare_large_gallery.py and finalize_large_gallery.py. The actual corpus is private local evaluation data under .benchmark-data/gallery-v1, excluded from version control. Do not redistribute photos by copying the corpus into the app or a public repository.

## Emulator run

tools/large-gallery/run_large_gallery.py stages the manifest and photos only on emulator-5580, checks a three-copy disk budget plus a 1 GiB reserve, and starts LargeGalleryRetrievalTest. The test hashes imported files, pauses the normal index worker, stores actual CLIP vectors in a fixture-specific encrypted index and ranks every query. It reports import/index/query times, index bytes, initial/restore decodes and cache reuse. Unknown judgments remain separate from explicit negatives.

The run completed on emulator-5580: instrumentation passed one scale acceptance test in 878.286 seconds. The result has complete=true and the exact frozen manifest hash. Import took 385.614 seconds; CLIP indexing took 425.520 seconds with 2,000 image decodes and one image-model open. Encrypted scene-index restore took 2.315 seconds with zero image decodes. The scene-only encrypted fixture occupied 10,219,581 bytes (9.75 MiB), compared with 604,963,729 original image bytes. This excludes OCR, face/object/garment records, app models and the originals; it is not an all-task index measurement.

| Query family | Known positive at rank 1 | At least one known positive in top 5 | Explicit negatives in 100 top-5 slots | Unjudged top-5 slots |
| --- | --- | --- | --- | --- |
| Category | 15/20 | 19/20 | 13 | 22 |
| Category conjunction | 13/20 | 20/20 | 21 | 22 |
| Official relationship, positive-only | 11/20 | 18/20 | Not measurable | 63 |

Family median query-encoding times were 11, 15 and 13 ms respectively; median full-catalog ranking times were 24.5, 17 and 11 ms. The first text query was cold: 4,362 ms encoding plus 70 ms ranking. Warm component medians exclude UI, source enumeration and model startup; they are emulator measurements, not phone latency or energy measurements.

The conjunction probes need redesign before product-quality claims. Many combine broad or anatomically overlapping labels, such as “Human body and Clothing and Human arm.” These are automatic annotation probes rather than independently reviewed natural user requests. The 20/20 top-five result does not establish useful understanding of “me in a red shirt in front of my car around Surat.” The report aggregates the frozen validation/test images and must not be described as a fully held-out natural-query result. Manual inspection also found five visually plausible mammals with no positive Mammal annotation, illustrating why an unjudged slot cannot automatically be called wrong. Official explicit negatives can also differ from a user's interpretation of a partial object. The table measures agreement with the supplied labels, not exhaustively reviewed visual truth. No threshold/model was tuned on this run. Preserve this v1 result; freeze a more useful independently reviewed prompt cohort for the next comparison.

Actual rankings and timings are in app/build/large-gallery-benchmark/emulator.json, metrics.json and summary.json. Paired examples-*.png and examples-*.html include failures, explicit judgment status and source/license credits in the HTML. tools/large-gallery/report_results.py regenerates metrics without rerunning inference and rejects incomplete, stale, conflicting or truncated results. A review found the initial host metric named judged_top5_precision incorrectly filtered the whole ranking before taking five; it now considers only the actual five displayed slots and returns null if no slots are judged or negatives are unavailable. Five focused host regressions passed, also rejecting duplicate manifest IDs and invalid timing values. The hit/negative counts in the table were unaffected.

On successful completion the test removed its own imported MediaStore photos, encrypted fixture and key. The host removed its staging copies. No phone or private photos were used.

An interrupted run retains its fixture and paused indexing for explicit --resume. The staged resume logic is implemented but has not yet passed a kill/resume acceptance test; do not claim crash-safe benchmark recovery yet. Resume only the same manifest, using the run.json token. A valid existing photo is not marked pending again, preserving its source revision and cached output.

## VLM comparison

The pinned SmolVLM2-500M Q8 model/projector pair totals 545,593,888 bytes and has verified hashes and captured model cards. An official pinned llama.cpp Windows CPU runtime was built from source and described one real corpus image successfully. app/build/vlm-host-smoke.json records that case. This is a host smoke test, not an Android integration, retrieval-quality benchmark or proof that the model is better than CLIP. The runtime and candidate must still be tested on the same labeled queries with cost and hallucination evidence before deployment changes.

See model-distribution-audit.md for exact source licenses and intended-use limitations. The released model notices are now packaged separately and readable through Sources → Model licenses and notices.
