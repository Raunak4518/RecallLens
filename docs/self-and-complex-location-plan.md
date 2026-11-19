# Explicit self identity and grounded complex locations

PL028/PL064/PL065/PL070 implementation plan, 18 November 2026. This continues the complete on-device goal; it does not replace unfinished production/model gates. Use only emulator-5580 and existing public/generated fixtures. No hosting, phone testing, signing workaround or new downloads.

## Independent work

The implementation agent owns schema11, encrypted self selection, NamedLibrary pronoun resolution, the self-selection dialog and store/migration tests. The root owns ViewModel/menu wiring, partial nearby composition, final source/permission validation and integration tests. Avoid shared-file edits; build only after both source sets stabilize. No source-code comments.

## Explicit self selection

Store one selected PERSON in SQLCipher. An absent setting retains legacy Me/Myself inference; an explicit clear suppresses it. Explicit selection overrides ambiguous pronoun labels, survives reopen and can be replaced/cleared without changing confirmations. Reject non-person/missing IDs. Offer the setting in Sources using saved people with confirmed references. The user can keep a normal name such as Ronak. This is a retrieval preference rather than biometric authentication or a claim of legal identity.

Acceptance: migrate old encrypted schema without data loss; select Ronak, query my photos/me/myself against only Ronak references, change to another person, clear and reopen, retain unchanged names/references, reject invalid selections and verify copied database bytes omit private labels/URIs. Test actual UI selection/query behavior.

## Complex nearby Content queries

An unsupported spatial/clothing phrase must not cause a recognized nearby place to be silently ignored. Extract affirmative near/around/in + exact saved-place suffixes, retain confirmed person/object scope, then apply the existing 30 km GPS filter before scene ranking. Unknown places remain requirements. Unsupported exclusions/alternatives must not become affirmative GPS filters. Place names are not proof of coordinates.

Keep location status distinct from Named confirmation. Permission denial, absent anchors, missing coordinates or stale anchors must produce no ungeofiltered fallback. Results remain partial where spatial/clothing predicates are unsupported; a verified location does not verify the entire prompt. Show the applied radius only after actual validation. Named mode stays unchanged and confirmed-only.

Resolve GPS before expensive query encoding; skip encoding if no candidates. Revalidate matched photos and anchor source revisions after potentially slow encoding/ranking, and recheck media-location permission before publishing. Reuse current cached coordinates and embeddings. Keep orchestration separate from UI/model implementations with narrow injectable boundaries; avoid a second parser framework.

Acceptance: parser cases, negation/unknown-place handling; real MediaStore fixtures with controlled EXIF; near and far photos of the same confirmed subjects plus unconfirmed near lookalikes; no-location/denied-permission behavior; revoke/edit/delete an anchor or candidate while ranking is blocked; no stale result, no full-gallery fallback. Reopen encrypted caches and verify no repeat image decoding/EXIF reads where valid. Controlled GPS proves metadata filtering, not visual landmark recognition or that the original public photo was captured in Surat.

## Verification and handoff

Run host checks, Kotlin build/lint, existing query/Named/location/source-publication regressions, new self/migration tests and real UI flows. Keep exact source/model/APK hashes and separate executed/skipped counts. Refresh unsigned ARM package checks if source is accepted. The prior release signing action was rejected by automatic approval review; do not retry through another route. Preserve old signed artifacts and distinguish them from current unsigned builds.

## Executed acceptance

The coordinated 16-class run completed on emulator-5580 with 58 executed Android passes, one explicit opt-in skip,25 passing host checks and build/lint success. Source/model/APK hashes stayed unchanged throughout. The preserved receipt is app/build/self-nearby-verification/receipt.json. Five self-store/migration checks and the real self-selection UI workflow passed. Three nearby-plan cases, six content-search lifecycle cases, two final-publication cases and the complex real-photo UI workflow passed alongside persistence, Named, clothing-store, matching and query regressions.

The complex UI fixture uses five controlled EXIF copies of one existing public photo. Only the confirmed near copy appeared; the confirmed far, unconfirmed near and confirmed GPS-missing copies did not. The screen displays the 30 km bound and keeps the spatial condition unverified. This proves filtering and disclosure, not person/object recognition accuracy, the original capture location, general spatial understanding or phone performance. The final-publication tests control permission suppliers; they are not a real AppOps revocation benchmark. See complex-location-review.md for the independent review.

The single public photo can be prepared from the existing cache using python tools/prepare_complex_content_fixture.py. If absent, pass --source to an exact matching file, or explicitly pass --download to fetch only that pinned image. Its SHA-256 and publisher attribution are checked/staged outside source assets; test EXIF mutations never change the original. The preparer was added after preserving the device receipt; it changes no Android source or model and is checked separately.
