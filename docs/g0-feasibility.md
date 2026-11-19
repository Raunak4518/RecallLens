# G0 requirements and feasibility evidence

This record tracks the work in [the implementation checklist](implementation-todo.md). Device and dependency qualification can begin on synthetic data in the emulator. Closing G0 requires the remaining physical-device evidence.

## PL001–PL008 requirements

| Task | Decision or evidence |
| --- | --- |
| PL001 | The [blueprint](blueprint.md) fixes the objective. Android offers account-free local photo search with encrypted derived data, plus separately approved full and redacted cloud modes. PL124–PL128 compares generative and non-generative approaches after the baseline. Sharing and backup remain optional. |
| PL002 | ADB previously identified a Samsung SM-M146B, Android 15/API 35, supporting arm64-v8a. The Android 17/API 37 emulator is x86_64. Phone RAM, free storage, gallery size, removable storage, and the confirmation of the desired OCR languages are pending. Use generated data for initial tests. |
| PL003 | Build floor API 30, compile SDK 37, target SDK 36. First supported phone ABI candidate is arm64-v8a, with x86_64 for emulator checks. Verify API 30 on a test environment and permission behavior on API 34+, then measure at least the Samsung phone before confirming the support matrix. Additional real-device tiers depend on availability. Recheck the target API for release. |
| PL004 | The existing class CSV still lacks descriptions for two teams and has one vague alert-system entry. Project overlap is unresolved for those entries. See [the source review](research-and-decisions.md#8-class-overlap). |
| PL005 | Start with static JPEG, PNG and WebP for qualification. Support filename, OCR text and image-to-text retrieval. Qualify English text and queries first. Add proven scripts separately. Initial cloud export is a rendered single-frame JPEG or PNG. Bound source images to 128 megapixels and preview to 1,600 pixels on its longest edge. Keep unsupported media visible with a reason. HEIC, RAW, motion photos, animations, video and scientific or medical interpretation are outside initial indexing support. |
| PL006 | The [threat model](blueprint.md#16-security-and-trust-limits), [key design](blueprint.md#9-local-encrypted-data-and-keys) and [mode policy](blueprint.md#7-three-modes-and-independent-assistance-gates) define device, service and worker trust boundaries. Local mode requires no upload or account. The worker sees the exact approved export. Consent binds mode, version, revision, destination and job. Redaction errors block the redacted path. |
| PL007 | Generated images and synthetic identifiers are the initial corpus. Record origin, allowed evaluation, training, upload, and redistribution for each later fixture; treat each as a separate right. Keep private or licensed restricted corpora outside the repository in access-controlled storage. Use synthetic content for network and tamper tests. Delete copies and derived results at the end of the approved evaluation period. |
| PL008 | Pin candidates for test use, then verify the exact dependency and model terms before shipping. AndroidX Room uses Apache-2.0. Use SQLCipher Android Community and carry the exact release notices. Bundled ML Kit Latin text recognition is an SDK under Google terms, with separate metrics obligations. Verify the selected encoder's weights as well as its source license. MobileCLIP research-weight terms do not qualify for a commercial build without a separate grant. |

Library references are in the [research register](research-and-decisions.md). Candidate pins for this pass are [Room 2.8.5](https://developer.android.com/jetpack/androidx/releases/room), [SQLCipher for Android 4.20.0](https://github.com/sqlcipher/sqlcipher-android/releases/tag/v4.20.0), and [bundled ML Kit 16.0.1](https://developers.google.com/ml-kit/vision/text-recognition/v2/android). [ML Kit terms](https://developers.google.com/ml-kit/terms) describe on-device content processing and metrics.

## PL009–PL010 minimum contracts

The encrypted catalog owns stable local asset IDs. A source location is keyed by provider, volume, and scoped URI, and stores access state. A content revision belongs to one asset and captures version and source checks. Artifacts bind to an asset, revision, model, task and applicable policy. Consent binds a revision and allowed processing mode to the exact reviewed export and destination. Jobs carry that authorization snapshot and a consent epoch. Tombstones contain the smallest local identity needed to reject late work.

Publish artifacts and complete jobs in the same transaction that compares access, active revision and epoch. A new edit or observed access revocation invalidates queued work and active derivatives. A temporarily unavailable provider moves the location to an unavailable state. Restore access by revalidating the location before exposing the artifact. Cancellation and expiry are terminal for that job. A later intentional retry gets its own identity.

The v1 extraction contract is a fixed typed record with model and task identifiers, bounded UTF-8 text, confidence where the model provides it, and regions in normalized image coordinates. Set initial limits of 64 KiB of UTF-8 text and 1,024 regions per extraction. Treat output as inert data, and reject malformed or unsupported fields before catalog integration.

Use separate envelope directions for request and result. Authenticate protocol version, direction, opaque job ID, destination key ID, and consent epoch. The encrypted request contains the approved mode, export SHA-256, opaque source revision, model/policy version, fixed task and return key. The encrypted result echoes the job binding and export identity and includes the extraction. Recompute the export hash at the worker before inference. Verify authenticated API principal, worker result origin, revision and consent again before commit. Fix a canonical wire encoding and supported HPKE library pair in the interoperability test before enabling cloud jobs.

## PL011–PL014 acceptance and setup

- For the first local gate, measure OCR text errors, top-k image retrieval, latency and memory against permitted fixtures. Include missing and unsupported input. Record actual device, ABI, OS, model hashes and conditions. The [evaluation plan](model-and-evaluation-plan.md) defines held-out splits and the later 1k and 10k asset performance checks. The first success criteria are real offline retrieval, protected derived data and correct handling of content edits, loss of access, and deletion. Set performance budgets after measuring the baseline.
- CueSeal and the Android app occupy separate roots. Android source lives under `app`, with its own build, instructions and plans. No CueSeal artifacts or account credentials are needed for the Android test.
- Android Studio 2026.2.2.9, bundled JDK 25, Gradle 9.3.1, AGP 9.1.1 and Kotlin 2.2.10 compiled the debug app. Build Tools are 36.0.0. Keep the Gradle cache on D: on this workstation. Phone launch remains deferred at the user's direction. See [the build instructions](../README.md#build-and-run).

## PL015–PL018 feasibility results

Six instrumented tests passed on the Android 17 x86_64 emulator with airplane mode on and Wi-Fi disabled. The emulator uses 4 KiB pages. The regular debug APK still declares neither Internet nor broad media-read permissions.

| Task | Observed result | Remaining qualification |
| --- | --- | --- |
| PL015, encoder | ONNX Runtime 1.26.0 ran the two quantized CLIP ViT-B/32 graphs. Both synthetic queries ranked the matching image first. Desktop CPU loaded both graphs in 2,422 ms and took 240 ms median after warm-up. On the emulator, load took 1,539 ms and the first two-image and two-query pass took 505 ms. | Real arm64 timing, memory, battery, and meaningful held-out retrieval. Desktop and emulator conditions differ. Android used fixed token IDs from the pinned tokenizer. App-side tokenization still needs implementation and parity checks. |
| PL015, OCR | Bundled Latin ML Kit passed the synthetic smoke test. In the [public crop comparison](dataset-evaluation.md#ocr-engine-comparison), it read 14 of 30 lines exactly versus 7 for Tesseract fast English. | Full-photo OCR quality, physical-device timing and memory, and release SDK/telemetry review. |
| PL016 | Room 2.8.5 and SQLCipher 4.20.0 opened an encrypted database, rejected a wrong key, and migrated the row from schema 1 to 2. The fixture marker was absent from the database and its active WAL. SQLCipher's four ABI libraries had 16 KiB ELF load alignment, and both feasibility APKs passed 16 KiB ZIP alignment. | Real arm64 and 16 KiB runtime validation. Static package alignment and one journal test leave other temporary-file paths untested. |
| PL017 | Android AES-GCM matched a Python cryptography 50.0.0 test vector. Tink Android 1.24.0 and Python cryptography opened each other's X25519/HKDF-SHA-256/AES-256-GCM HPKE fixtures. The tests rejected a changed processing mode and modified ciphertext. | Freeze the canonical wire encoding. Exercise service identity, result origin, replay, and authorization when implementing remote jobs. The tests use public synthetic fixture keys. |
| PL018 | A non-exportable AES key wrapped and unwrapped a random local data key. Deleting its alias removed the ability to load it. Keystore reported software security level on the emulator. | Validate authentication, lock and invalidation, backup, and key-loss behavior on a physical phone. |

The models are pinned to the [ONNX candidate revision](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523). The two graphs total 153,621,508 bytes. The [fixture preparation script](../tools/prepare_g0_clip.py) pins both graph hashes, the tokenizer, and preprocessing metadata. It verifies the files after download and after transferring them to the test app. Release provenance and licensing review remain open.

## Reproduce the Android checks

Use the toolchain paths from [the README](../README.md#build-and-run) and run the standard tests on the connected test emulator.

```powershell
$env:ANDROID_SERIAL = 'emulator-5580'
.\gradlew.bat :app:connectedFeasibilityAndroidTest :app:lintDebug
```

The encoder check uses Python with Pillow and about 154 MB of locally staged model files. The following commands reinstall the test packages after Gradle's test run and opt into the encoder check.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$serial = 'emulator-5580'
& $adb -s $serial install -r 'app/build/outputs/apk/feasibility/app-feasibility.apk'
& $adb -s $serial install -r 'app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk'
python tools/prepare_g0_clip.py --adb $adb --serial $serial
& $adb -s $serial shell am instrument -w -e clipFixtures true org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
```

Use airplane mode with Wi-Fi off on the test emulator to reproduce offline inference. The last run with the staged encoder printed `OK (6 tests)`. Gradle's standard test report lives under `app/build/reports/androidTests/connected/feasibility/`. Android lint reported zero errors and ten warnings.

## Next checklist work

PL001, PL012 and PL013 are complete. PL002–PL011 and PL014–PL018 have recorded decisions or partial evidence. PL019 remains open. [Public dataset evaluation](dataset-evaluation.md) records the first real-image subsets and the G1 acceptance conditions. It uses the workstation and emulator. Physical hardware checks remain separate from dataset sourcing.
