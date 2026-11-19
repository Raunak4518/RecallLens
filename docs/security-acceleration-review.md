# Security and lifecycle acceleration review

Reviewed 18 November 2026 against the current Kotlin source. The core-verification source freeze was respected: implementation drafts are staged under .agent-staging/security/app/src and are not part of the installed or verified app until integrated. No independent device tests have run for these drafts yet.

After the root reported the frozen core baseline passed, the identity changes were integrated into actual source. Root owns the combined build and emulator runs; this review does not claim their outcome. Integrated files are IdentityReview.kt, CachedIdentityScan.kt, PhotoIndexStore.kt, EncryptedPhotoIndex.kt and the new IdentityCandidateRaceTest.kt. The database schema was left at 9 for root to apply its independent garment migration.

## Confirmed source-level findings

| Priority | Finding and current source | Consequence | Focused acceptance |
| --- | --- | --- | --- |
| P1 | CachedIdentityScan.kt:95–101 keeps the first gallery revision map for the whole rank. IdentityReview.kt:103–111 invokes a progress callback after reference lookup and finally validates only references. | A cached candidate edited, deleted, trashed or made inaccessible after the initial map can retain its previous vector and appear as a suggestion. Valid anchors do not establish current candidate access/content. | Populate real object-fixture cache, use a throwing encoder factory, mutate a candidate at progress(0), assert it is excluded and its stale encrypted cache row removed. Deny gallery AppOps to check all cached results are suppressed. |
| P2 | CachedGarmentAnalysis.kt:59–60 and 75–76 supply only currentRevision mapped to source: for garment commits. | A source that has no provider revision can produce a valid pixels: inspection result but the commit callback always returns null, preventing persistent garment analysis. | Injectable unversioned source: initial save, cached reopen, changed pixels, access loss during verification. Root owns this fix separately. |

## Identity draft

The staged IdentityReview.kt adds a final gallery snapshot before returning any ranking. The same snapshot validates both anchors and returned candidates, including provider-version information carried by GallerySource. Unknown gallery revisions and non-gallery sources use the existing referenceRevision fallback. A stale anchor yields NoReference; stale candidates are filtered. CachedIdentityScan exposes revision-checked invalidation so the cached route also removes stale entries. The uncached route receives the same final result filtering.

Peer review identified that checking an in-memory revision followed by an unconditional DELETE could remove a newer writer's row. PhotoIndexSession now exposes conditional removal with the expected source revision and model revision. SQLCipher implements it as one DELETE matching URI, source_revision, model name/version and edge. Unsupported alternate stores conservatively return false. CachedIdentityScan uses this for both normal discards and final invalidation. A fifth regression opens two persistent sessions and verifies that stale removal preserves a newer-source row and a same-source replacement-model row; only matching removal succeeds.

This adds one batched gallery enumeration rather than a MediaStore query for every returned candidate. It does not claim a simultaneous immutable filesystem snapshot: changes after final validation are still handled by the existing observer/reload mechanism. Measured performance evidence must report the additional enumeration cost. The draft race test logs warm/race elapsed times and has a factory that fails if an encoder opens during either warm scan.

The staged IdentityCandidateRaceTest contains deletion, pixel-edit, trash and gallery-access-denial cases. Each prepares three attributed bundled real-photo fixtures, confirms only the anchor, fills the encrypted cache, checks an unchanged warm result and mutates during the next scan. Gallery denial uses AppOps because revoking a runtime permission can terminate the instrumentation process. AppOps state is restored in finally, owned fixtures are removed, and fixture-specific keys/index files are deleted.

## Other boundaries inspected

Named content proofs are separate from bounded model-input hashes. PhotoSubjects rehashes unversioned references and NamedSubjectStore requires a matching prior proof and existing reference before revision rebinding. The current cloud transport validates approval before POST/retry and during polling, and CloudTextStore checks revision both before and after its transaction. These checks are useful but are not proof of a complete race-free revocation-epoch design. The broader deletion/revocation lifecycle matrix remains an open release gate.

No findings here establish physical-phone speed, zero disclosure to an authorized cloud worker, perfect redaction or complete production readiness. Existing tests remain evidence for their actual cases, independently of these review findings.

## AppOps denial found during focused acceptance

The root's combined focused run passed the candidate delete/edit/trash cases, but the initial gallery-denial case returned cached candidates. The prior GalleryAccess.current checked only Context.checkSelfPermission. MediaStore may continue returning photos owned by this app even when broad library access is denied, so those fixture queries did not independently enforce the application's declared gallery scope. GalleryAccess now uses AndroidX PermissionChecker.checkSelfPermission for both full and selected access. That API checks the runtime permission and its AppOps state, as documented by the [official AndroidX reference](https://developer.android.com/reference/androidx/core/content/PermissionChecker). The race test now asserts that the shell operation actually reports MODE_IGNORED and that the app's gallery scope is NONE, retaining the original NoReference and cache-purge assertions. Root owns the build and follow-up device evidence for this change.

The next paired run correctly stopped at the stronger setup assertion: effective AppOps remained MODE_ALLOWED. Direct emulator shell inspection showed the package-level AppOps command exiting successfully while the effective media op remained allowed. Setting the same operation with --uid produced an effective ignored UID mode. The test now uses --uid, captures command output, checks the effective mode, and restores captured prior modes explicitly. This distinguishes an ineffective test setup from app result-publication behavior; the strict denial/result/cache assertions remain unchanged. No passing denial acceptance is claimed until root's rerun completes.
