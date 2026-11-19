# Android project review and implementation order

The first release should let someone grant access to a gallery, find photos with remembered words or a description, and confirm names for people or objects. All three workflows should work offline. The local release gate PL072 includes lifecycle, performance and quality evidence that the current prototype still needs.

This table records the earlier architecture review. Implementation has since added durable indexing, task-specific identity adapters, encrypted location caching, cached clothing evidence and authenticated cloud transport/service. The main app now declares Internet permission for explicitly approved cloud jobs; local indexing/search needs no cloud account or upload. Read execution-status.md for current verification and remaining requirements. Historical recommendations below are not all still pending.

| Area | Current state | Change before release |
| --- | --- | --- |
| Structure and naming | `index`, `semantic`, `identity`, `detection`, `subjects` and `privacy` have recognizable boundaries. The `library` package mixes MediaStore, UI and orchestration. | Keep the data and inference packages. Move search and gallery workflows out of `LibraryViewModel` as their responsibilities change. Give new components behavior-specific names. |
| Model API | `PhotoProcessor` is a useful adapter contract. `PhotoProcessorFactory` declares the revision before opening OCR or visual inference. `PhotoIndexSession` separates storage from refresh. Identity still owns concrete model loaders. | Keep the factory and composition root. Add replaceable face and object encoder boundaries when the next candidate is integrated. Use shared input and output contracts across benchmarks and app. |
| Refresh | `LibraryViewModel.reload` launches text and visual indexing. Stable source and model revisions enable cached restore. Unknown source revisions require bounded pixel decoding for validation. Initial OCR and visual jobs each decode their own inputs. | Separate cached restore from queued inference. Prioritize user-requested images and use stable gallery revisions. Measure duplicate decode cost before designing a shared pixel cache. |
| OCR | Full OCR across new photos makes coverage predictable but adds work to thousands of gallery images. Remembered text currently matches normalized phrases within one recognized line. | Measure document-first scheduling and a lightweight text detector against the exhaustive baseline. Include signs, package labels, memes and screenshots. Provide manual scan and background coverage, and show which photos still need processing. |
| Search and UI | Content, Text and Named modes expose distinct mechanisms. Name candidates and identity suggestions have separate review screens. | Keep one visible search entry and group exact evidence and related suggestions. Display why a result matched, coverage and whether a name was confirmed. Test permission changes and interruptions in the normal screen flow. |
| Personal identity | Face and object embeddings support review of likely matches. Confirmed links have stronger semantics than embedding similarity. | Keep Named results confirmed-only. Build relation evidence between a specific person region and their shirt before supporting a conjunctive query. Evaluate exact clothes and cars against same-brand or same-color negatives. |
| Places and history | Nearby search reads EXIF on demand through `PhotoLocations`. College history is user recorded. | Cache authorized coordinates by source revision in the encrypted index. Rank by distance and expose the radius. Treat location metadata and alias history as separate evidence. Missing GPS cannot satisfy a strict geographic filter. |
| Local security | SQLCipher stores derived state. Android Keystore protects the database passphrase. The main app removes Internet permission. | Test deletion, trash, move, edit and permission revocation against every derived table and late job. Inspect thumbnail, backup and diagnostic paths. Keep transformed media and key material out of logs. |
| Worker API | The OCR worker demonstrates bounded payloads, encrypted request and response exchange and receipts in a development harness. | Before enabling cloud in the app, implement authenticated sessions, authorization and quotas, consent bound to exact bytes, expiry, retries and deletion. A worker decrypts approved bytes in memory. Reviewed masking reduces exposed pixels. |

## Performance design

Use one encrypted record per photo and task with source revision, model revision, output and coverage. The search path should use cached text and embeddings. A semantic query encodes the query once and ranks stored vectors. At 5,000 photos, benchmark exact cosine ranking before introducing another index and its maintenance costs.

A 512-float visual embedding uses 2,048 bytes, a 128-float face descriptor uses 512 bytes, and a 384-float object descriptor uses 1,536 bytes before regions, histograms and row overhead. One of each across 5,000 photos uses 20.48 MB of raw vector data. Measure SQLCipher page overhead, peak memory and galleries with many regions separately. The packaged model size is a separate budget. Keep production model assets limited to candidates that pass measured quality and cost gates.

## Gallery evaluation matrix

| Cohort | Cases and failure controls | Evidence |
| --- | --- | --- |
| Remembered text | Low contrast, reflections, curved bottles, rotations, crowded scenes, repeated strings, multilingual text, handwriting, vertical lines, small distant signs and text-free photos | Held-out full photos from [HierText](https://github.com/google-research-datasets/hiertext) with per-image attribution, plus separately licensed additions for scripts and handwriting |
| Objects and scenes | Same make/model/color cars, changed viewpoint, occlusion, similar room layouts, indoor/outdoor pictures, group photos and edited memes | [Open Images](https://storage.googleapis.com/openimages/web/download_v7.html) for category and relationship cases. Exact-instance labels require separate ground truth. |
| People and clothing | Small faces, groups, masks, changes in light and age, red shirt on the wrong person, same uniform on several people and repeated outfits | Licensed or consented identity fixtures with separated enrollment and query views. [DeepFashion2](https://github.com/switchablenorms/DeepFashion2) can evaluate clothing retrieval after checking its dataset terms. |
| Place and time | Adjacent city, missing GPS, incorrect location, repeated venues, historical names and photos outside the requested date | [Google Landmarks v2](https://research.google/pubs/google-landmarks-dataset-v2-a-large-scale-benchmark-for-instance-level-recognition-and-retrieval/) for landmark instances. Use controlled coordinates and user-entered history for radius and dates. |
| Source lifecycle | Move across folders, same-path edit, trash and restore, permanent delete, duplicate grants, partial permission and revocation during inference | Disposable emulator galleries with known revisions, assertions on derived rows and hashes of untouched originals |

Start with an attributed manifest of 200 or more full photos for OCR and scene evaluation. Keep separate identity and lifecycle fixtures. Use scene or identity groups when splitting tuning and evaluation. Report recall@1/5, false positives on absent queries, coverage, indexing time, query latency, peak memory and bytes on disk. Track recognized words and remembered-phrase retrieval separately.

## Implementation order

1. PL046, PL052–PL055, PL061–PL062 and PL070. Preserve cached output metadata and exercise edit, trash, delete and revocation against each task, including stale work.
2. PL064, PL066 and PL069. Measure a 5,000-row encrypted restore and cached ranking, then measure first indexing, repeat launch and search on ARM Android with cold and warm runs.
3. PL058, PL061 and PL071. Compare ML Kit and PP-OCRv6 Tiny on a larger held-out cohort. Benchmark exhaustive local OCR and text-first scheduling using missed remembered phrases, time and decode count.
4. PL064, PL065 and PL071. Add the evidence needed for combined identity, garment and location queries. Require consistent links to the same person and distinguish confirmed from related results.
5. PL056 and PL065–PL072. Verify the local install, gallery, search, confirmation and permission flows and record model package size, index size and accuracy.
6. PL084–PL112. Verify both opt-in cloud modes and ship them after the local gate, API authorization and privacy tests pass.
7. PL124–PL128. Evaluate a generative VLM against the same queries and privacy boundaries after the encoder baseline is established.

The existing OCR benchmark covers 26 real HierText photos and generated stress fixtures. ML Kit found 18 of 52 remembered-text queries on the real photos. PP-OCRv6 Tiny found 26, and the development worker with PP-OCRv6 Medium found 33 with full images and 31 with redacted images. Keep ML Kit as the app baseline while testing Tiny on a larger corpus. The results differ by photo and redaction can change the available evidence. See the [paired benchmark](hybrid-ocr-benchmark.md).

The existing identity benchmark covers 48 queries per dataset. PubFig83 reached 45/48 at both top 1 and top 5. Objectron reached 44/48 at top 1 and 48/48 at top 5. The repeated cache comparison had median face and object scan times of 0.838 s and 0.819 s on the emulator. These cohorts measure prepared references and specific lookalikes. Group portraits, everyday clothes and same-model vehicles remain evaluation priorities. See the [identity benchmark](identity-benchmark.md).

## November 17 verification

PL061 and PL062 now preserve the OCR completeness flag in versioned payloads. Existing JSON array rows remain readable and retain their historical default because their completeness was never stored. New payload versions outside the supported schema fail closed.

The offline feasibility APK and instrumentation APK built successfully. Eight checks passed on Android 17 x86_64, covering wrong-key and altered-key handling, legacy records, incremental updates, deletion, cancelled work, model changes and cached restore. The scale fixture inserted four synthetic rows per photo in one transaction, representing one OCR line, one visual vector, one face and one object per photo. The test then used production SQLCipher readers and search. It measured 51,142,717 bytes on disk, 6,903 ms to restore four tables and 494 ms for the first text lookup and visual rank together. The restore opened zero processors and decoded zero images. Timings exclude initial indexing, MediaStore enumeration, query encoding and rendering. Logs are `app/build/index-audit-instrumentation.txt` and `app/build/index-audit-scale.txt`.

For PL069, measure normal app startup with real photos and profile the four restoration costs independently. Keep separate measurements for high-region-count photos and device thermal and memory behavior. PL071 still needs the larger held-out cohort.

## Cached search startup

For PL052, PL065, PL066 and PL069, the library publishes the permitted source list and restores OCR and scene embeddings before identity cleanup. Cleanup still runs on refresh and reads only source revisions and photo identifiers. A new refresh cancels the old cleanup job and waits for it to finish before taking a new source snapshot. An identity review opens the requested face or object table only if a reference is present in the current gallery scope. Named search still reads confirmed references from the named catalog.

The paired 5,000-photo test uses one encrypted fixture with the same four payload types as the original scale test. It measures the previous refresh order with identity metadata cleanup first and the new order with cleanup after cached search. Both paths use the production storage and ranking code. The fixture has current source revisions and uses one warm-up per order, then five runs each with alternating order. The test measures text availability and combined text and visual availability with a supplied query vector. It excludes source listing, Named catalog refresh, text encoding for visual queries and UI rendering.

| Median on Android 17 x86_64 | Previous order | New order |
| --- | ---: | ---: |
| Restore cached OCR and find the text result | 2,431 ms | 1,339 ms |
| Restore and query both text and scene vectors | 4,381 ms | 3,195 ms |

Both orders restored 5,000 current rows per search task with zero image decodes or processor opens. The database occupied 51,142,717 bytes. On-demand reads of the face and object tables took 1,227 ms and 1,940 ms respectively in one additional pass. These are table reads and exclude identity ranking and inference. Raw samples are in `app/build/search-restore-scale.txt`.

The feasibility and instrumentation APKs built offline. All 15 targeted Android 17 x86_64 tests passed with emulator networking disabled. Face and object lookalike tests verified Named search before and after explicit confirmation, including encrypted identity cache reuse. The suite also exercised model replacement, source changes, deleted rows, revoked scope and real-photo visual ranking. The complete run is in `app/build/search-restore-instrumentation.txt`.
