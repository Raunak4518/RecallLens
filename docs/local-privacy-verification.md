# Local search privacy verification

PL068 and PL110 use separate checks for offline operation, stored data and network activity. This run used the feasibility APK and test APK on the Android 17 x86_64 emulator. The device and both APK hashes are recorded in app/build/local-privacy-verification/receipt.json.

| Check | Result |
| --- | --- |
| Local OCR on a new synthetic MediaStore photo | The bundled ML Kit recognizer extracted a generated phrase. The cached search found it after reopening SQLCipher, with a test failure if the cached read attempted to load the photo. |
| Local scene search on five attributed fixtures | All five descriptions ranked their intended photo first. The encrypted reopen reused the vectors with zero image decodes. |
| Offline repeat | Both tests passed with airplane mode on and Wi-Fi and mobile data off. The runner restored the initial settings. |
| Connected app-level traffic | Android's live counters recorded zero bytes and packets in both directions for the app UID and test UID during the two tests and 30 seconds afterward. |
| Consented cloud control | Both full and redacted synthetic jobs passed against the development service. The same counters registered 75,348 bytes sent and 6,602 bytes received by the app UID. |
| Stored synthetic plaintext | The temporary encrypted index lacked the SQLite header, source URI and OCR marker. The OCR marker was also absent from 22 inspected files under the app's cache, code cache, files, database and preferences folders, with an 8 MiB limit per file. |

The byte scan checks the exact synthetic phrase as case-insensitive contiguous text. It covers the stated app directories and test index, without decoding compressed payloads or scanning private storage owned by other packages. A separate index test checks the active rollback journal, wrong passphrase and a changed or deleted wrapping key. No original gallery image is modified apart from inserting and removing the generated test photo.

## SDK diagnostics and consent

The app includes Google ML Kit Latin OCR 16.0.1 and has Internet permission for user-approved cloud jobs. Its merged manifest includes Google's DataTransport scheduler, and the SDK created a private diagnostics database on the emulator. Google's published terms say that ML Kit processes input on the device, keeps inputs and results off its servers, and may contact Google for updates, diagnostics and usage metrics. Its data disclosure lists categories including app and device information, timing, and input or output sizes. The short UID measurement cannot verify delayed sends, measure traffic under other UIDs or identify packet contents. [ML Kit privacy terms](https://developers.google.com/ml-kit/terms) · [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

The application's cloud HTTP client runs for explicitly approved jobs. The consent test sends generated fixtures to the local development service and verifies the authenticated full and redacted results. An immediate counter read in the initial control missed the transfer. The final verifier polls after the consented control to account for reporting delay. Public TLS operation and real-device measurement remain separate qualification work. See cloud-service.md.

## Repeat the emulator audit

Build the feasibility APK and its instrumentation APK with the pinned assets. Run the verifier on the disposable emulator. It executes the same two tests with Wi-Fi connected and with all radios disabled.

```powershell
.\gradlew.bat --offline :app:assembleFeasibility :app:assembleFeasibilityAndroidTest
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
python tools/verify_local_privacy.py --adb $adb --serial emulator-5580 --cloud-control
```
