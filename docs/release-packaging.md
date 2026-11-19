# Release packaging and qualification

PL029–PL036, PL069 and PL134 cover packaging, sensitive-state handling and device qualification. Build an ARM64 artifact separately from emulator regression APKs:

```powershell
$env:JAVA_HOME = 'D:\andriod studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$env:GRADLE_USER_HOME = 'D:\Android\Gradle'
.\gradlew.bat --offline -PphotolibraryAbi=arm64-v8a :app:assembleRelease :app:bundleRelease
python tools/inspect_release.py --build-tools "$env:ANDROID_HOME/build-tools/36.0.0"
```

Release artifacts are unsigned unless a release signing configuration has been supplied. Never use the debug certificate as a production identity. Keep signing credentials outside the repository. An Android App Bundle is needed for new Play apps; an APK remains useful for authorized direct installation and inspection.

The current ARM64 APK is 314,926,571 bytes (300.3 MiB) and the App Bundle is 217,265,585 bytes (207.2 MiB). All staged assets match in both artifacts, including license notices. Native ELF load segments and APK ZIP alignment pass 16 KB checks. Packaged backup and transfer exclusions, disabled backup, non-debuggable status and cleartext denial pass inspection. Exported components are the launcher and three framework components guarded by BIND_JOB_SERVICE or DUMP permissions. All ten static checks passed. The receipt is at app/build/release-inspection/results.json. Use bundletool or Play Console with the bundle and a target device configuration to estimate the store download size.

Checked17 November2026: Google's dedicated size-limit page lists a500MB compressed base-module limit and a non-blocking mobile-data notice above200MB. This does not establish that our app has passed Play review or that its download size is optimal. [Current Play size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)

Keep local-first inference usable with bundled baseline models. Evaluate optional model delivery only after measuring installation and update cost. Google's Play for On-device AI is currently marked beta and documents install-time, fast-follow and on-demand model packs. It is a possible later distribution adapter, not a required dependency of this first working system. [Play model delivery](https://developer.android.com/google/play/on-device-ai)

Inspect the merged release manifest and packaged assets for non-debuggable status, disabled backup, no cleartext network exception, only intended exported components, expected ABI and model/license inventory. The worker sees approved plaintext during OCR; application-layer encryption does not change that boundary. Signed store distribution, production TLS, physical ARM peak memory, sustained indexing, thermal and battery measurements remain unverified. Current automation is authorized only on emulator-5580.
