# Local acceptance and remaining release gates

This matrix describes the Android project in D:/photo-library. It separates implemented workflow checks from evidence still required by the original task checklist. Read execution-status.md for the latest run receipts. A passing emulator suite does not close the physical-device or production gates.

| Workflow and task IDs | Existing acceptance coverage | Remaining requirement |
| --- | --- | --- |
| Persistent indexing, PL041/PL066 | DurableIndexingTest; separate process-restart and reboot acceptance | Physical OS scheduling, key-lock and constrained battery/storage behavior |
| Source access, PL037/PL042/PL043/PL051 | GalleryAvailabilityTest, FolderSourceTest, SourceObservationTest; available/unavailable distinction and scope pruning | Broader real document providers, selected-photo permission transitions, removable storage |
| Same-URI lifecycle, PL044/PL046/PL052/PL070 | OCR/scene pixel reuse, full-file Named proof, identity and garment input validation, EXIF-only GPS edits | New-URI and cross-volume continuity, provider version reset/reused-ID matrix, late work across those cases |
| Copied state, PL025–PL036 | PersistentPhotoIndexTest, NamedSubjectsTest, garment store tests, separate crypto/package checks | Complete threat-model review, intended signing/key recovery and physical-device security qualification |
| Remembered-text retrieval, PL061/PL071 | Local ML Kit default; paired 300-photo Tiny comparison; all 27 lost queries reviewed | Fresh independent query labels, multilingual/script coverage, ARM model qualification |
| Scene and identity search, PL060/PL063/PL064 | LocalVisualSearchTest, IdentityMatchingTest, IdentityCacheMetadataTest; confirmed-only Named results | Broad held-out exact-instance/face retrieval, absent-item calibration and supported query coverage |
| Person/color queries, PL064/PL071 | Clothing workflow screen, confirmed-face/region matching, real garment cohort, estimated-color group | Independent ownership/color labels, wider groups/lookalikes, garment subtype/spatial relations |
| Nearby queries, PL062/PL064 | PhotoLocationsTest; encrypted coordinates, EXIF-only changes, permission clearing, separate 5,000-photo cached benchmark | Physical real-GPS and inaccurate/missing-location cases |
| Local privacy, PL068 | Separate connected/offline audit with network positive control, private-file phrase search | Continued SDK/version inventory and broader cache/export review |
| Package and cost, PL069/PL134 | ARM64 APK/AAB builds; ten static package checks; controlled index footprint | Physical memory, sustained latency, battery, thermal and realistic region-heavy index measurements |
| Cloud, PL091–PL112 | Nineteen host tests, Android client/store tests, separate authenticated full/redacted development-service interop | Public TLS operation, deployment/retention qualification, broader privacy and consent tests |
| Complete local gate, PL072 | Combined core verification plus explicitly enabled workflow tests | All applicable lifecycle, quality and physical performance requirements above |

The combined verifier checks source/model hashes before and after its run, builds and lints the app, runs the host service tests and executes its listed Android classes on a disposable emulator. Its receipt records reported tests, executed passes and skips separately. It removes a previous receipt before starting; a failed or interrupted rerun must not leave an old receipt that appears current. Opt-in datasets, live-service interop, reboot tests, clothing-screen acceptance and physical measurements remain separate evidence unless explicitly enabled in a particular run.

Keep the app's query path focused on cached evidence. LibraryViewModel still owns several independent jobs and query branches. After this integrated baseline, extract query execution with explicit input/version snapshots and stale-result guards, preserving existing evidence groups and confirmed-only Named behavior. The folder organization should follow that responsibility change. Swapping model implementations must continue to leave source access, encrypted storage and query evidence semantics independent.
