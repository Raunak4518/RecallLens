# Durable local indexing

This implementation advances PL021, PL041, PL066 and PL070.

The screen restores OCR and scene embeddings using the current source snapshot. New inference runs in WorkManager through an application-level coordinator. Each worker processes at most eight photos, checking both OCR and scene tasks. A persisted continuation processes the next batch. Workers enumerate the authorized library again before each batch. They infer pending tasks from source and model revisions in encrypted caches.

The encrypted indexing_control row stores the gallery/model signature, cursor, total, pause flag and failed-photo count. Worker scheduler data contains the worker type and no photo references. Photo task output remains in SQLCipher. The existing per-task source revision or pixel-digest check runs before commit.

The cursor advances only after both task passes return. A process death before checkpoint may repeat the current batch. Valid cached outputs skip inference, and unknown-revision sources validate the bounded pixel digest. The cursor avoids repeatedly choosing the first unknown-revision photo within a pass. A changed source or model signature starts a new pass while preserving the pause flag.

Pause persists before work cancellation. Cancellation prevents later task commits through the indexer's coroutine checks. Resume schedules pending work. Explicit refresh after a completed pass rechecks unknown revisions and retries failures. A failed photo contributes to the failed count and does not prevent later photos from being attempted.

The screen subscribes to application progress and restores only changed cache payloads after completed batches. Per-URI update generations preserve changes if StateFlow conflates several updates while the screen is busy. The initial full restore finishes before merging updates. The screen checks the current source list before restoring. Gallery and identity cleanup remain separate from inference. Region and identity model loading remains on demand.

Worker selection reads cache metadata without decoding all output payloads. Processing a selected batch reconciles the full permitted scope and decodes only the selected records. An instrumentation regression puts a corrupt payload in an unselected row to verify that scoped cache reads avoid it.

The scheduler follows Android persistent-work execution constraints and requires battery and storage above Android's low thresholds. The screen can distinguish waiting work from running or paused work. Work may wait for the OS, and force-stop requires Android to permit scheduling again. This implementation makes no promise of uninterrupted execution, physical-device throughput or battery use.

The DurableIndexingTest instrumentation class verifies checkpoint reopening, bounded batches, pause persistence, changed source revisions, unknown-revision pass behavior, cached-only restoration, failed-photo retries and full-scope preservation during partial processing.

Run tools/run_durable_index_acceptance.py with an explicit --adb path and emulator serial after installing both feasibility APKs. Its opt-in instrumentation phases use the production scheduler and encoders. They create 24 disposable MediaStore photos, wait for partial indexing, force-stop and relaunch the application through ADB, verify completion, test persistent pause through a second restart, then resume a 25th photo. The test checks original hashes and unchanged persisted OCR from the partial pass. Successful completion deletes only the test-created originals and prunes their cached OCR and scene records.

The default acceptance run tests force-stop followed by explicit application launch. A forced stop prevents Android from scheduling the package until it becomes eligible again. Use --restart-mode reboot for the separate emulator reboot case. The host waits for Android boot completion, unlocks the disposable emulator, launches the app and verifies that the kernel boot identity changed. Output defaults to app/build/durable-worker-acceptance-reboot so the process-restart receipt is preserved.

The two-reboot run passed all three phases on emulator-5580. It verified25 completed fixture photos, unchanged original hashes and the partial cached OCR, persistent pause and explicit resume of the pending25th photo. It cleaned only its own inserted photos and cached OCR/scene rows. This establishes resumed app behavior after reboot, not autonomous completion while locked before a user opens the app, background process-reclamation behavior or physical-device scheduling performance.
