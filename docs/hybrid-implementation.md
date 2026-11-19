# Hybrid processing implementation

This pass advances PL073–PL087 and the envelope, worker and replay portions of PL095–PL100, PL106 and PL107.

The Android review and envelope live in [the privacy package](../app/src/main/kotlin/org/photolibrary/app/privacy). The [OCR worker](../worker/ocr_worker.py) can run independently of the app. The [emulator runner](../tools/run_worker_roundtrip.py) connects them for the fixture test.

## Build order

1. Prepare and approve the exact export on Android. Keep local search usable independently.
2. Exercise the request and response contract with real OCR in an isolated worker. Bind the approved mode, bytes, task and worker identity.
3. Measure remembered-text recovery across local OCR and approved full and redacted exports. Use those results to decide whether to connect hosted OCR. [OCR model comparison](hybrid-ocr-benchmark.md)
4. Measure automatic privacy misses and over-redaction on independently labeled fixtures. Qualify local models on physical devices and complete the lifecycle matrix. If hosted OCR clears the usefulness bar, add authenticated transport, a durable owner-scoped job queue, expiry and cancellation. Accept results only while the approved revision and consent still apply. Test account isolation and lifecycle races.
5. Run the required non-generative and generative model comparisons, then finish release verification and deployment.

## Android review

From photo detail, open **Review cloud copy**. **Keep on device** returns without approval. **Full copy** renders the visible image. **Redacted copy** runs bundled ML Kit Latin OCR and YuNet face detection on the phone. The baseline masks OCR lines with an email, PAN-like identifier or a run of at least seven digits, along with detected faces. Drag on the serialized preview to add opaque masks or to retain a smaller crop. Preview, mode, task and destination are visible in the review. Approval is in memory for that session and is discarded on edits, mode changes, leaving review or backgrounding the app. Review the contents that remain visible. A detector can miss people or identifying details. [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder.ImageInfo)

The source gate admits bounded still JPEG and PNG images. PNG animation chunks, CRC failures and trailing payloads fail validation. JPEG multipicture markers and trailing data after the image end fail validation. Android decodes oriented sRGB pixels. The export re-renders a single PNG and validates its chunk allowlist and checksums, then decodes those exact bytes for display. It keeps source filenames and container metadata outside the artifact. Source access, provider revision and source bytes are checked again on approval and immediately before sealing. Redaction errors stay within the redacted path. A redacted approval requires a mask or a crop that removes pixels.

## Worker contract

Android seals the approved PNG in an X25519/HKDF-SHA-256/AES-256-GCM HPKE request to a verified worker key fingerprint. The outer envelope contains an opaque job ID, worker key ID and ciphertext. The encrypted record binds mode, export digest, opaque source token, task, policy and a fresh response key. The Python worker independently validates the envelope, image bounds and export digest before running Tesseract English OCR. It encrypts the result to the per-job response key and echoes the authenticated request fields. Android rejects modified replies and allows the envelope to be consumed once. The worker's SQLite receipt ledger stores a request digest and encrypted result. It returns the same result for an exact retry and rejects a different request for the same job ID. The worker reads the approved visible PNG in memory to run OCR. [HPKE interoperability reference](https://cryptography.io/en/50.0.2/hazmat/primitives/hpke/)

The development round trip uses ADB to move only the encrypted fixtures. The app review can approve a copy in memory. Authenticated network transport, account sign-in, job lifecycle and production result integration remain open.

## Verification

The offline debug and feasibility APK builds passed. Debug lint reported zero errors and 25 warnings. Thirteen focused checks passed on the Android 17 x86_64 emulator. They covered redaction, metadata removal, stale-source rejection, confirmed-only Named search, identity ranking, encrypted index reuse, visual search and HPKE interoperability. The host-dependent test ran separately and passed against Tesseract 5.5.4.

| Approved mode | OCR found public text | OCR found synthetic email | Encrypted request bytes | Encrypted result bytes |
| --- | --- | --- | ---: | ---: |
| Full copy | Yes | Yes | 47,030 | 524 |
| Redacted copy | Yes | No | 22,258 | 492 |

Both modes rejected bit-altered requests and results, returned an identical encrypted result after the receipt ledger was reopened, and rejected a different valid request reusing the job ID. Android refused to consume the same result twice. The fixture demonstrates the protocol and this specific redaction case. Detector coverage and extraction accuracy across real documents remain to be measured.

In the debug app, the emulator found a generated fixture using filename search, opened its review, showed one text mask, approved the redacted preview and cleared that approval when switched to Full copy. **Keep on device** then returned to photo detail. The screen capture is in `app/build/privacy-review-emulator.png`.

## Reproduction

Use the pinned application models and emulator setup from the [README](../README.md). Build the feasibility app and test APK. Use pinned English traineddata as recorded in the [OCR comparison](dataset-evaluation.md#ocr-engine-comparison).

```powershell
.\gradlew.bat --offline :app:assembleFeasibility :app:assembleFeasibilityAndroidTest :app:lintDebug
python -m pip install --target app/build/worker-runtime -r worker/requirements.txt
$env:PYTHONPATH = (Resolve-Path app/build/worker-runtime).Path
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -s emulator-5580 install -r app/build/outputs/apk/feasibility/app-feasibility.apk
& $adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk
& $adb -s emulator-5580 shell am instrument -w -r -e class org.photolibrary.app.feasibility.PhotoExportTest org.photolibrary.app.feasibility.test/androidx.test.runner.AndroidJUnitRunner
python tools/run_worker_roundtrip.py --device emulator-5580
```

The round-trip runner uses an ephemeral worker key and writes aggregate evidence under `app/build/worker-roundtrip`. Its fixture contains public text and a synthetic email. The full copy must return both. The redacted copy must return the public text while hiding the email. The runner checks request and response tampering, one-time client consumption and exact worker retry behavior.
