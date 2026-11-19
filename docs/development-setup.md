# Stack recommendation and development setup

Prepared on 14 November 2026 and updated for the first Kotlin build on 15 November 2026.

## Recommendation

Use Kotlin for the Android application and Jetpack Compose for its interface. Android-first is a user decision; Kotlin/Compose is the recommended implementation, not a claim that Flutter or React Native cannot meet the requirements.

The difficult parts of this app are gallery lifecycle and permission changes, persistent indexing, device key protection, encrypted data and mobile inference. Direct Android integration reduces the number of language/plugin boundaries we need to own and debug. This is an engineering judgment for this scope, not a measured claim that Kotlin development is universally faster.

Google recommends [Kotlin for new Android work](https://developer.android.com/kotlin/first) and [Compose for modern native UI](https://developer.android.com/compose).

## Alternatives and development speed

| Choice | Strong reason to choose it | Tradeoff for this project |
| --- | --- | --- |
| Kotlin + Compose | Android is the first platform and native media/security/background integration is central | Learn Kotlin/Compose; another platform needs additional client work |
| Flutter + Dart | Team knows Flutter or Android and iOS are both near-term requirements | Qualify plugins and write native integration where coverage or lifecycle guarantees are insufficient |
| React Native + TypeScript | Team has substantial React Native experience and values sharing mobile UI/business logic | Web React experience does not remove Android work; custom native modules may still be needed |

Flutter supports [native platform channels](https://docs.flutter.dev/platform-integration/platform-channels), and React Native supports [custom native modules](https://reactnative.dev/docs/turbo-native-modules-introduction). Both can run native ML and use Android security APIs. Neither is inherently insecure or automatically slow. Avoid copying large image tensors through UI-language boundaries regardless of framework.

A familiar cross-platform framework can shorten initial screen development. For the complete Android system, Kotlin is the current recommendation because it avoids introducing an extra UI/runtime integration layer alongside the substantial native work. Team experience or a firm near-term iOS requirement could change that decision; no speed percentage is asserted.

## Proposed component stack

| Component | Plan |
| --- | --- |
| Android UI/application logic | Kotlin, Jetpack Compose, lifecycle-aware state and coroutines |
| Gallery | MediaStore and selected-photo access with permission reconciliation |
| Scheduling | WorkManager and bounded resumable work; qualify current OS execution limits |
| Local storage | Room with a compatible maintained SQLCipher Android integration |
| Key wrapping | Android Keystore; explicitly tested lock/invalidation/backup behaviour |
| Image-text embeddings and OCR | Qualified mobile inference runtime and pretrained models; exact artifacts selected by G0/G1 tests |
| Model experiments and later cloud worker | Python is a practical candidate; it does not run as a Python server inside the phone app |
| Later service | Proposed FastAPI, PostgreSQL job metadata and encrypted payload storage; final dependencies need qualification |

Kotlin does not replace the ML runtime: it coordinates decoding, inference, indexing and UI. Native runtime libraries execute the model. Do not download large models or choose an accelerator just from a model-card result.

## What to install first

1. Download the current stable [Android Studio](https://developer.android.com/studio/install) for Windows from Google.
2. Complete its Setup Wizard, including the Android SDK, Platform-Tools and required build tools. Let the future project pin compatible SDK/Gradle/JDK versions; do not install unrelated global Kotlin or Gradle versions now.
3. Use the bundled JDK initially, subject to the project's compatibility check. A separate Java installation is not a prerequisite for reading the plan.
4. Prefer a physical Android phone for gallery, battery and local AI testing. Enable developer options and USB debugging when ready to test, then authorize only the trusted development computer. An emulator is useful for OS/permission tests but cannot substitute for physical-device performance measurements.
5. Install an emulator/system image if needed and supported by available disk/RAM. We do not need to download every API image or install Flutter/Node solely for the recommended native app.

The IDE is recommended rather than technically mandatory: Android projects can be built with the [Gradle wrapper from a terminal](https://developer.android.com/build/building-cmdline), given the required SDK/JDK. Android Studio makes SDK management, device debugging, profiling and Compose previews easier. The agent can edit source and run builds from the terminal; the IDE need not stay open for every future build.

Open the project root in Android Studio and follow the [build and run instructions](../README.md). The first build uses the installed SDK Platform 37 and Build Tools 36.0.0.

## Full installation checklist

| Stage | Install or prepare | How it is supplied |
| --- | --- | --- |
| First Android build | Stable Android Studio for Windows, one Android SDK Platform matching the project's compile SDK, SDK Build-Tools and SDK Platform-Tools (ADB) | Studio installer and [SDK Manager](https://developer.android.com/studio/intro/update#sdk-manager); the bundled JDK is the starting point for compatible builds |
| Physical phone | Android phone with enough free storage, a data-capable USB cable or supported wireless debugging, and developer options/USB debugging for USB | [Device setup](https://developer.android.com/studio/run/device); install the [OEM USB driver](https://developer.android.com/studio/run/oem-usb) only if Windows needs one |
| Source and build | Git; project Gradle wrapper, Android Gradle Plugin and Kotlin toolchain | Git is already installed; the project's pinned build files manage the rest. No standalone Gradle or Kotlin compiler installation |
| Local app | Compose, Room, a compatible SQLCipher Android dependency, WorkManager, app test dependencies and any selected crypto library | Gradle downloads pinned dependencies. MediaStore and Android Keystore are OS APIs. SQLCipher does not require a Windows database server |
| Local AI and redaction | One qualified Android embedding runtime (ONNX Runtime or LiteRT candidate), selected OCR and redaction libraries, and licensed model assets | App dependencies plus project-managed model downloads with checksums. Exact runtime, OCR, and model versions still need device and license qualification |
| Model experiments | Python in a project virtual environment, with matched packages for the selected models and benchmarks (such as PyTorch, Transformers, ONNX/export tools, and pytest) | Python 3.12 and 3.13 are available; select the compatible runtime and pin dependencies. Download approved model weights and evaluation fixtures only when selected. GPU tooling is conditional on the worker and framework build |
| Hybrid/cloud development | Docker Desktop and Compose, service dependencies (proposed FastAPI, database client/migrations and crypto libraries), PostgreSQL and encrypted job payload storage | Docker and Compose are installed; verify the engine before service work. Use a PostgreSQL container for local testing rather than installing a second database server. Service Python dependencies belong in the container or project environment |
| Cloud deployment | Hosting for the API and inference worker, PostgreSQL, payload storage, and TLS/service credentials; GPU capacity only if benchmarks justify it | Provision later; these are services and credentials, not Windows installs. Local mode runs without them |
| Play release | Google Play Console developer account, upload signing key, Android App Bundle, testing track, applicable target API and release checks | Use [Android app signing](https://developer.android.com/studio/publish/app-signing) and [current target API rules](https://developer.android.com/google/play/requirements/target-sdk). For paid cloud features, add Play Billing through Gradle and verify purchases on the backend |

Install the emulator and a small number of system images later for additional OS coverage; use a physical phone for performance, memory and battery measurements. Command-line SDK management is optional when using Studio. Install Android NDK/CMake only if a selected native dependency needs compilation. Flutter, Dart, React Native, Node.js, and Xcode are unnecessary for the planned native Android client.

## Current setup observations

Checked on 15 November 2026. The user installed Android Studio on D: and the SDK under the default Windows user profile. The terminal build uses the bundled JetBrains Runtime 25 and keeps its Gradle cache on D:. Git, Python 3.12 and 3.13, Docker and Docker Compose are also available.

ADB detected an authorized Samsung SM-M146B running Android 15 (API 35) with arm64 support. This is the first physical device recorded for PL002. Wider device coverage and inference measurements remain pending.

Next implementation work begins with PL001-PL019: establish real device/support requirements, permitted fixtures, artifact licenses, baseline inference and encrypted storage feasibility. Folder preparation alone does not satisfy those gates.
