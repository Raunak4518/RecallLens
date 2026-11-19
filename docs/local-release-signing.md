# Local release signing and distribution

Use the intended persistent release identity for installable local APKs. Debug/feasibility keys are development identities. For a future Play release, an upload key authorizes bundle submission while Play App Signing can manage the app-signing key. Keep those roles explicit. Android requires APK signatures, and Play requires an upload-signed AAB. [Official signing guide](https://developer.android.com/studio/publish/app-signing)

The workstation has keytool and jarsigner in D:/andriod studio/jbr/bin and apksigner in Android SDK Build Tools 36.0.0. The root agent is responsible for deciding and executing key creation. This document does not establish that a key or signed artifact already exists; consult the final signing receipt.

Executed local signing: a persistent RSA-3072 PKCS12 key with alias photolibrary-upload now exists in D:/photo-library-private/signing. The directory ACL is restricted to the owner and SYSTEM, and its generated password is DPAPI-protected outside the checkout. The key and password have not been printed, committed or uploaded. The signed APK verifies with APK Signature Scheme v3 and the AAB reports jar verified. The public certificate SHA-256 is 86701cfc360db53bb9a6e1227fd7fdc9d2adcf4bcddb2c2c5b99e7660ffde315. Receipt: app/build/signed-release-receipt.json.

Current signed artifacts are app/build/outputs/signed/photo-library-arm64.apk and photo-library-arm64.aab. This local signing closes signature generation/verification for this build, not Play submission or physical ARM runtime qualification. Exact model notices are packaged and readable in Sources. Public distribution remains pending runtime/software dependency notice review, owner-controlled portable key backup and applicable Play declarations. The current x86_64 emulator cannot validate an ARM64-only APK's runtime. No hosting was created; the user explicitly deferred hosting and restricted tests to the emulator.

## Key custody

The intended private directory is D:/photo-library-private/signing, outside the source checkout and build outputs. Restrict its Windows ACL to the owner and required system administration, generate strong independent passwords with an OS-backed generator, and store password material under the owner's DPAPI protection or a password manager. Never put passwords in command-line arguments, source files, screenshots, build transcripts or messages.

Before depending on this identity, retain an encrypted keystore backup and password/recovery instructions in a separate owner-controlled location. A DPAPI-protected password on this workstation is not by itself a portable backup. Record the key alias, certificate validity and public certificate SHA-256 fingerprint separately from secret material.

A local APK signed with an upload key is a valid sideloaded identity, but a future Play-delivered APK may use a different Play app-signing key. Android normally cannot update between unrelated signing identities. Decide the release channel before asking teammates to install over a real data-bearing version. Do not solve a mismatch by uninstalling a user's app without explaining that uninstall can remove its private encrypted index and Keystore-dependent data.

## Produce and verify artifacts

1. Finish source/model verification and ARM64 build with the documented Gradle environment. Generate app-release-unsigned.apk and app-release.aab.
2. Align the unsigned APK before signing. Use apksigner to produce a distinct signed output; retain the unsigned artifact and build receipt for traceability.
3. Sign the AAB with jarsigner or Android Studio's signed-bundle workflow. apksigner does not sign AAB files. [Bundletool documentation](https://developer.android.com/tools/bundletool)
4. Verify the signed APK and print its public certificate details. Verify the AAB's signature. Compare the certificate fingerprint with the intended identity; a successful signature check alone does not prove the correct key was used.
5. Record artifact paths, file sizes, SHA-256 hashes, certificate SHA-256, package/version and the source/model receipt. Do not include passwords or the private keystore in that public receipt.
6. Install only on the explicitly authorized emulator for current testing. A debug package, the feasibility-suffixed package and a production release package can coexist; always use explicit package names when launching or clearing data.

Commands for public verification after the root produces signed outputs:

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
& "$sdk/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs 'D:/photo-library/app/build/outputs/signed/photo-library-arm64.apk'
& 'D:/andriod studio/jbr/bin/jarsigner.exe' -verify 'D:/photo-library/app/build/outputs/signed/photo-library-arm64.aab'
Get-FileHash -Algorithm SHA256 'D:/photo-library/app/build/outputs/signed/photo-library-arm64.apk'
Get-FileHash -Algorithm SHA256 'D:/photo-library/app/build/outputs/signed/photo-library-arm64.aab'
```

The paths above match the recorded local signing outputs; use the latest receipt to verify their hashes and source snapshot. Do not run an install against a missing artifact or assume an old file was regenerated. A signed ARM64 APK cannot run on an x86_64-only emulator image. Validate ARM64 artifact structure separately and use the matching x86_64 build for the current emulator; physical ARM runtime remains unmeasured.

## Rotation and recovery

Keep the same key for sideloaded updates unless a supported signing-lineage migration has been deliberately implemented and tested. Play upload-key reset and app-signing-key changes are different procedures, controlled through the Play account. Losing a local signing key is not repaired by generating another key with the same alias. Preserve its backup before distributing updates. A certificate does not recover the private key.

Publishing to Google Play, hosting the optional cloud worker and using a physical phone are separate actions. No hosting account is needed to run or distribute the account-free local app. See play-release-audit.md for current Play-specific gates and the distinction between built functionality and unverified release qualification.

## Latest refresh status

The current unsigned ARM64 build includes partial Content, coverage, explicit self selection and complex nearby filtering, and passed all 11 package checks. The previous refresh signing command was rejected before execution by automatic approval review with “blocked by policy”; no retry was made. The earlier signed files were hash-verified unchanged. They do not include the newer partial-search/cache/coverage/self/nearby changes. Do not present those older signed files as the latest app. app/build/signed-release-receipt.json records the distinction and current unsigned package inspection; no alternate signing route was attempted.
