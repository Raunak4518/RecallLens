# Accelerated implementation record

The user requested parallel work with a one-hour target. The window is 18 November 2026 00:22:11–01:22:11 IST. The full project scope and external qualification requirements remain in implementation-todo.md and one-hour-acceptance-plan.md.

## Integrated changes

| Responsibility | Implementation | Behavior |
| --- | --- | --- |
| Query execution | PhotoQueryExecution and LibraryViewModel | One cancellation/generation owner, immutable query/source/Named/scope snapshots, late-result/error suppression, owning-scope cancellation and latest-only cache reruns |
| Identity publication | IdentityMatcher and CachedIdentityScan | Final batched source validation of anchors and candidates; deleted, changed, trashed or inaccessible candidates are excluded without reopening encoders |
| Concurrent invalidation | PhotoIndexSession and EncryptedPhotoIndex.Session | Conditional deletion matches source/model revision atomically, preserving newer source/model rows |
| Clothing persistence | VerifiedGarmentEvidence and GarmentEvidenceStore | Suspendable source validation supports unversioned providers; schema 10 per-write tokens fence failed/cancelled cleanup from replacement writes |
| Access policy | GalleryAccess and PhotoLocations | AndroidX PermissionChecker checks permission plus corresponding AppOps policy; final identity publication rechecks gallery scope |
| Search guidance | LibraryScreen and string resources | Mode-specific examples, explicit self-naming guidance, separate confirmation review and deduplicated status/requirement messages |
| Verification | verify_local_core.py | Reported/executed/skipped counts checked separately; old receipt removed at run start; optional real clothing workflow inclusion |

Three independent agents prepared query, security and release/UX changes while the root kept the baseline sources frozen. Drafts were staged outside the app source set, peer-reviewed, then integrated and built together. One emulator runs device tests serially. The staging directory is not an application source or validation artifact and must not be represented as a separate tested build.

## Evidence and failures retained

The frozen pre-change baseline passed 19 host tests, build/lint, 63 executed Android checks and three opt-in skips. Hashes confirmed unchanged sources/models. Receipt: app/build/pre-acceleration-verification/receipt.json.

The first integrated build/lint passed. The 18-case focused run had 16 passes and two failures. The five query-coordination cases, token-owned garment commits, storage/migration checks and completed delete/edit/trash identity races passed. The UI test incorrectly asserted an empty field's placeholder before focus. The access-denial test used a package-level AppOps command that did not alter the effective UID mode on this emulator. These are preserved in app/build/accelerated-focused-regression.txt.

The UI setup was corrected to focus and await accessible examples. A subsequent run passed that UI case and captured the screen, but failed the strengthened denial setup assertion before exercising publication. The test now sets UID-level AppOps, verifies effective denial and GalleryAccess.NONE, and restores prior modes. The strict NoReference/cache-purge expectations remain. See app/build/accelerated-access-regression.txt. The screenshot also exposed duplicated status text, which was corrected in the app and asserted in the test.

Final reruns and receipts must be read before declaring these changes fully verified. Build-only success and a finished test event are not proof of a passing test. Production APK signing, physical hardware performance, independent model labels, public TLS operation and required later VLM evaluation remain separate requirements.

The first 79-case final run lost its host verifier process and Android UI-automation connection. Android logcat recorded 13 failures ending in DeadObjectException; no new success receipt exists. Its buffered transcript was not written, so app/build/local-core-verification/android.txt still contained the older 66-case transcript. That file must not be interpreted as the final run. Failure evidence is retained in app/build/accelerated-interrupted-logcat.txt. The verifier now streams each command's output into its transcript file as the command executes, rather than buffering until completion. A recovery run targets the affected query, garment, identity, guidance and clothing workflows before repeating broader verification.

The first recovery run passed all 13 selected tests in 221.909 seconds: five query-coordination cases, one verified-garment commit case, the real guidance screen, five identity race/storage cases and the real clothing workflow. See app/build/accelerated-recovered-regression.txt. The remaining older identity/export cases affected by the broken automation connection are running separately. The PID-scoped interrupted summary identifies 63 passing cases, three opt-in skips and exactly 13 failed permission-grant setups; no assertion failure preceded the lost connection. Summary: app/build/accelerated-interrupted-summary.json.

Final recovery completed before the one-hour deadline. The remaining identity matching, identity metadata-cache and photo-export group reported OK (8 tests), 136.225 seconds: seven executed passes and one live-worker opt-in skip. Across the original run and both recoveries, all 76 distinct executable cases from the 79-case selection have passing evidence; three opt-in cases remain skipped. The report checks each original test name against passing recovery names and rejects unresolved cases. It does not convert the interrupted run into a clean suite or assert a production gate. Evidence: app/build/accelerated-recovered-legacy-regression.txt and accelerated-split-acceptance.json. Generate the report with tools/report_split_acceptance.py.

The app installed on emulator-5580 matches the current feasibility APK byte-for-byte: SHA-256 cc297f4005c4d678246c089970181b31b15391129e82971f3d5ab0594b032ba2. Host service tests passed 19/19. Final Android build/lint passed. Both ARM64 artifacts passed all ten static checks; the APK remains unsigned. The actual gray/white Named search screenshot, including one unknown-name message and separate Name suggestions, is app/build/search-guidance-named.png.

The three skipped cases are the opt-in 5,000-photo location benchmark, authenticated live cloud export-service workflow and encrypted host-worker round trip. These have separate earlier acceptance records but were not rerun as part of this integration selection. The verifier's single-run success receipt is absent for the interrupted attempt; use the explicitly scoped split report, not an old android.txt.

The targeted recovery contains 13 cases and is written to app/build/accelerated-recovered-regression.txt. It is pending until the root confirms its terminal result; a partial transcript is not a pass. The final build/lint and ARM64 package generation passed, and release-inspection/results.json records ten passing static checks. The inspected unsigned APK is 314,978,399 bytes and the bundle is 217,285,991 bytes. README now includes Android Studio Device Manager steps and explicit emulator-5580 launch commands for the existing feasibility build.

## Known broader gaps

The work does not close the full lifecycle and release gates. Copies and new-URI/cross-volume moves need identity/revocation rules. Provider resets/reused row IDs, all document-provider behaviors and background constraints need broader acceptance. Clothing result publication and multi-photo summaries still need the complete late-source-change matrix. Model selection requires fresh held-out labels and ARM measurements. A signed distributable and production service depend on their actual credentials and operational setup.
