# Face and object identity benchmark

This evaluation advances PL058 and PL071 and supplies Android emulator evidence for PL069. It measures retrieval from one user-confirmed reference per query. The models, preprocessing, color weights and gallery scoring are the ones used by the app. Measurements cover local Android inference on an x86_64 emulator. Device battery, thermal behavior and physical-device throughput remain open.

## Evaluation sets

| Set | Queries | Gallery photos | Distractors |
| --- | ---: | ---: | --- |
| PubFig83 | 48 identities with one reference each | 131 | 96 photos from the 48 query identities, two per identity, and 35 extra people. Each extra person's image is the closest to any query by grayscale HOG, from a seeded pool of up to 16 photos. |
| Objectron test split | 48 physical-object tracks, 16 each for cameras, cups and shoes | 96 | Two gallery frames per track. An object query faces 30 other images in its own category and 64 in the other categories. |

PubFig83 publishes cropped, mainly frontal faces from 83 public figures and provides errata. The sampler excludes the listed image numbers where the identity and filename match. The retrieval query identities and their three images were selected with seed 38457. HOG is independent of the app's face encoder and selects visually similar distractors without using benchmark model scores. The source publishes these photos for evaluation and requests citation. The staged images stay in ignored benchmark files. Upstream SFace training overlap with these people is unknown. [PubFig83 publication and archive](https://vision.seas.harvard.edu/pubfig83/)

Objectron releases multi-view capture sequences under C-UDA 1.0. The sampler uses the published category test lists and takes single-object tracks with a visible annotation near 20%, 50% and 80% of the sequence. The early frame is the reference. The other two are positives. Gallery frames retain the scene and are resized to at most 768 pixels on the longest edge. The annotations select and validate the frames. They supply no crop to the matcher. Distinct tracks are treated as distinct objects. The benchmark does not establish whether a physical item ever appears in multiple source tracks. Frames within a track can share lighting and background, so this measures viewpoint changes more directly than independent photo sessions. [Objectron data format and license](https://github.com/google-research-datasets/Objectron) · [Objectron paper](https://openaccess.thecvf.com/content/CVPR2021/papers/Ahmadyan_Objectron_A_Large_Scale_Dataset_of_Object-Centric_Videos_in_the_CVPR_2021_paper.pdf)

## Metrics and timings

Top-1 and top-5 are query-level hit rates. A query is a hit if at least one of the two true gallery photos appears in the first one or five results. All 48 queries per dataset count, including any unusable reference or missed candidate. The primary object results use the mixed gallery. Category-only results use the same scores with just same-category comparators.

The instrumented benchmark encodes the shared gallery once for the 48-query accuracy measurement, keeping the vectors in memory. It uses the production model sessions, source checks, similarity scores and tie order. It also times three independent calls to the production on-demand scanner per dataset. Each timed call opens fresh model sessions and scans the full gallery with a single reference. The timing includes Android source checks, decode, region detection, embedding, scoring and sorting. It excludes staging the photos into MediaStore. The three calls follow the shared-gallery run, so the emulator and file cache can be warm.

The larger-gallery Named search guard confirms one reference in a disposable encrypted catalog and generates suggestions against the dataset. It checks that the encrypted database has the same bytes after ranking and that Named search still returns exactly the confirmed reference after reopening.

### Emulator results

The Android 17 x86_64 emulator ran with four virtual CPUs and roughly 3.8 GiB of guest memory. Wi-Fi and mobile data were disabled. Models used the CPU through ONNX Runtime 1.26.0.

The PubFig83 run scored **45/48 (93.8%) for both top 1 and top 5**. Three references produced no usable vector. Among the other 45 queries, all ranked a true gallery photo first. The matcher could score 129 of the 131 gallery photos. An extra HOG-selected impostor appeared in the top five for 26 queries. The full-gallery scans took 18.89, 19.25 and 19.41 seconds, with a median of 19.25 seconds. Encoding the shared gallery took 19.56 seconds, and ranking a query against the encoded gallery took a median of 5.25 ms, excluding reference encoding. The Named search guard passed.

The Objectron run scored **44/48 (91.7%) for top 1 and 48/48 (100%) for top 5**. All four missed first positions had a true alternate frame at position two. The same-category-only evaluation produced the same hit rates as the mixed gallery. The app scored all 96 gallery frames. The three full-gallery scans took 32.59, 33.26 and 33.35 seconds, with a median of 33.26 seconds. Encoding the shared gallery took 33.23 seconds, and ranking a query against the encoded gallery took a median of 5.16 ms, excluding reference encoding. The Named search guard passed.

| Objectron type | Top 1 | Top 5 |
| --- | ---: | ---: |
| Camera | 13/16 | 16/16 |
| Cup | 16/16 | 16/16 |
| Shoe | 15/16 | 16/16 |

For each of the six timed calls, the top-five identity order matched the shared-gallery benchmark. The face misses happened at reference preparation. In the object run, showing more than one suggestion recovered every query. Both datasets exercised the Named search boundary independently.

Both benchmark methods passed, followed by 16 offline regression tests covering identity review, Name suggestions, Named search, GPS, encrypted index lifecycle, OCR and CLIP. Debug lint reported zero errors and 21 warnings.

## Encrypted materialized identity features

Schema version 5 adds SQLCipher tables for face features and object features. A row stores a photo URI, its source revision, the feature pipeline revision, a list of gallery regions and vectors, and any cached reference crops. Gallery misses with valid source metadata can store an empty result. The application hashes the actual bundled encoder, detector and relevant label files and incorporates a pipeline revision. Changing alignment, detection, preprocessing or the binary payload format requires a pipeline revision update. A change to the payload format also requires a schema migration.

Library refresh removes entries for deleted or inaccessible photos and entries with a changed listed revision. A repeat search validates gallery rows against one scoped MediaStore metadata query. Folder and individual grants use their providers, with the same local pixel digest as the reference workflow when revision metadata is unavailable. Matching opens the models only for misses and saves completed work a photo at a time. Matches and rankings live outside the confirmation tables. Only the existing explicit confirmation operation can add a Named result.

The cache benchmark reuses the original PubFig83 and Objectron manifests and the same first timed query from each domain. It runs three uncached app scans, builds the encrypted cache once, runs three warmed searches, then constructs a new matcher and searches from disk again. All times include the production lookup, reference preparation, source checks and ranking. It requires that every returned URI, score and marked region match the uncached ranking exactly. It also checks zero model calls on warmed searches, unchanged encrypted catalog bytes during warmed reads, and unchanged Named results before and after cache writes.

| Dataset | Gallery | Uncached median | First cache build | Repeat median | Fresh matcher, same encrypted cache | Speedup |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PubFig83 faces | 131 | 21.672 s | 19.738 s | 0.838 s | 0.880 s | 25.9× |
| Objectron objects | 96 | 28.170 s | 30.755 s | 0.819 s | 0.866 s | 34.4× |

The uncached column uses three measurements per dataset from the first cache comparison run. That pass used individual metadata lookups on hits and measured warmed medians of 2.709 s for faces and 1.953 s for objects. The batched metadata pass made one additional uncached validation call per dataset. Those calls took 16.342 s and 36.796 s. Cold and repeat times in the table come from the batched pass. The variation in uncached timings reflects the emulator runs, and the table does not establish a first-query speedup.

The final repeated and fresh-matcher searches reused all 131 face or 96 object gallery entries and the reference entry with zero model calls. The face run includes the two gallery photos with zero detections. The encrypted catalog grew 160 KiB in the face fixture and 516 KiB in the object fixture. Both cache benchmark tests passed and Named search still returned only the explicitly confirmed reference.

Nine targeted regression tests passed on the final batched build. Coverage included migration from schema 4, encrypted feature storage, model replacement, photo edits, deletion, removal from the accessible snapshot, and Named search. The edit fixture uses MediaStore's pending-to-published write sequence to make revision updates deterministic. Debug lint reported zero errors and 22 warnings.

## Reproduction

Stage the pinned app models using the README. The benchmark uses Python with Pillow, NumPy and OpenCV. For Objectron, save the upstream `annotation_data.proto`, `object.proto` and `a_r_capture_metadata.proto` into `app/build/identity-benchmark`. Compile the schemas into that same directory with `grpc_tools.protoc`. The local isolated tools directory can be prepared with `python -m pip install --target app/build/identity-benchmark/proto-tools grpcio-tools`. Set `PYTHONPATH` to that directory for the compiler. [Objectron schemas](https://github.com/google-research-datasets/Objectron/tree/master/objectron/schema)

```powershell
python tools/prepare_identity_benchmark.py face
python tools/prepare_identity_benchmark.py object
.\gradlew.bat --offline :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$device = 'emulator-5580'
& $adb -s $device install -r app/build/outputs/apk/feasibility/app-feasibility.apk
& $adb -s $device install -r app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk
& $adb -s $device shell am instrument -w -r -e identityBenchmark true -e class org.photolibrary.app.feasibility.IdentityBenchmarkTest org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
```

The run writes `cache/identity-benchmark-face.json` and `cache/identity-benchmark-object.json` within the feasibility app. Retrieve them with `adb exec-out run-as org.photolibrary.app.feasibility cat` using the cache paths. The reports contain anonymous query IDs, ranks, timing and confirmation checks. Staging records source identifiers and SHA-256 in the ignored manifests. The local archive and downloaded clips are excluded from the app's production assets.

To reproduce the cache timing pass, add `-e identityCacheBenchmark true` to the same instrumentation command. The outputs are `cache/identity-cache-benchmark-face.json` and `cache/identity-cache-benchmark-object.json`. Add `-e repeatCacheTiming true` after an initial timing pass to reuse its three-scan uncached median and run one uncached validation against the new cache.
