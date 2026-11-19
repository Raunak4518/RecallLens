# Cached inference after rename and move

PL044 now has a shared OCR and scene-embedding reuse path. A changed source revision remains pending and cannot appear in cached search until verified. The encrypted row retains a SHA-256 fingerprint of the bounded, orientation-normalized decoded model input. A matching fingerprint and model revision allow the indexer to save the existing output under the new source revision without opening the inference engine. It decodes only the affected photo. An unchanged source revision still restores without decoding.

The indexer checks cancellation and source access/revision again before committing either reused or newly inferred output. A changed image or model runs inference. Missing access removes the row. Records from older builds without a pixel fingerprint take the normal inference path once their source revision changes. Fingerprints remain inside SQLCipher; there is no new plaintext sidecar or database schema migration.

This fingerprint describes the input seen by that particular OCR or scene model. It does not prove equality of original file bytes and must not authorize migration of cloud consent, face confirmation, garment evidence or other records. Same-volume MediaStore operations that retain the URI can use this path. Copies, new-URI moves and cross-volume identity require separate work.

The Android acceptance coverage includes model opening counts, changed pixels, access loss during decode, encrypted reopening, real MediaStore rename/move/edit/deletion and byte-for-byte scene-vector reuse on bundled real-photo fixtures. The provider test verifies the originals' SHA-256 digest before and after both metadata operations. No phone photos or new datasets are used.

The real MediaStore acceptance on Android 17 x86_64 emulator-5580 recorded:

| Operation | Image decodes | OCR calls | Model opens |
| --- | --- | --- | --- |
| Index two generated text photos | 2 | 2 | 1 |
| Reopen encrypted index | 0 | 0 | 0 |
| Rename one photo | 1 | 0 | 0 |
| Move the photo within its volume | 1 | 0 | 0 |
| Reopen after the move | 0 | 0 | 0 |
| Edit one photo | 1 | 1 | 1 |
| Delete the photo | 0 | 0 | 0 |

These are operation counts, not an ARM speed or battery benchmark. Source image digests stayed unchanged through rename and move. Logs: app/build/metadata-lifecycle-counters.txt and metadata-lifecycle-regression.txt.

The expanded provider acceptance, two CLIP tests and corrected unknown-revision scheduling test passed together: OK (4 tests), 87.279 seconds. The earlier 22-test run had one obsolete expectation that an unknown-revision refresh would decode only once. The refresh now decodes again before saving to detect access/content changes, while retaining three total inference calls across the initial three photos and the explicit refresh. The original failed transcript is preserved in app/build/metadata-cache-regression.txt. Build/lint and ARM64 APK/AAB production packaging passed. All ten static checks in app/build/release-inspection/results.json passed; release signing and physical qualification remain separate.

Task PL044 remains incomplete for the whole app. Location, garment and cloud provenance have their own invalidation contracts. Reusing them safely requires evidence specific to those records. Hashing also adds one bounded image pass during inference; large-corpus and ARM cost measurements remain pending.

Named confirmations now have a separate original-file proof and their own [verified lifecycle](named-reference-lifecycle.md). Their migration does not use the bounded OCR/scene fingerprint described here.

Face and object embeddings now also have a [decoded-input reuse path](identity-cache-lifecycle.md), with model and source validation specific to identity analysis.
