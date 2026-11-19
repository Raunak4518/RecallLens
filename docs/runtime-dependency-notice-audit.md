# Release runtime dependency and notice audit

Audited 18 November 2026 from existing Gradle caches, AGP release dependency reports, the current unsigned APK and small version-tagged upstream license files. No compile, emulator, phone, signing operation or weight/dataset download was performed. This is an engineering inventory of identifiable notice gaps, not completed legal clearance.

## Reproducible inventory artifacts

The local artifact directory is app/build/dependency-notice-audit. It contains:

- release-runtime-inventory.json: exact AGP report path/hash, Maven coordinates, cached POM paths/hashes/license elements, AAR/JAR paths/hashes and preserved notice-entry hashes.
- release-runtime-inventory.tsv and .md: readable coordinate/license inventory.
- apk-notice-inventory.json: observed APK notice-like entries and actual ARM64 native library names.
- cached-notices/: notice/license text extracted byte-for-byte from local cached release AAR/JAR files.
- staged-notices/sources.json and staged-notices/: candidate runtime notice assets with source/archive/version/hash mapping and explicit completeness status. These have not been applied to app source/assets.
- upstream-root-check.json, onnx-upstream-version-checks.json and onnx-native-version-strings.json: exact upstream availability and native version evidence.

The inventory decodes app/build/intermediates/bundle_dependency_report/release/configureReleaseDependencies/dependencies.pb as protobuf wire records without resolving dependencies or running Gradle. The observed coordinate record stores group/artifact/version as fields 1/2/5, and release artifact digests appear under record field 2/field 1. Each claimed cached binary match was checked against that digest rather than inferred merely from a directory name.

The report contains 144 Maven coordinates. Of these, 109 have a cached AAR/JAR whose SHA-256 matches the AGP release digest. The other 35 include metadata/BOM/multiplatform coordinates or records without a matching cached binary; they are not asserted to be 35 shipped libraries. Every coordinate has a cached POM, but some POMs have no license element or only a license URL. The inventory is not a full native SBOM and does not prove all 144 license obligations complete.

## Direct/runtime findings

| Exact resolved component | Evidence from cache/release | Concrete distribution work |
| --- | --- | --- |
| com.microsoft.onnxruntime:onnxruntime-android:1.26.0 | Matching AAR; POM says MIT; no license/notice in AAR or classes.jar. Native version strings include VERS_1.26.0 and 1.26.0 | Include the correct Microsoft copyright/MIT text and applicable native third-party notices from a verified release source. Exact upstream provenance is unresolved below; no main-branch text was substituted |
| net.zetetic:sqlcipher-android:4.20.0 | Matching AAR; POM points to Zetetic's license URL with no license name; AAR/classes.jar have no notice files | Exact sqlcipher-android v4.20.0 LICENSE downloaded from the official repository and staged. It requires the copyright, conditions and disclaimer with binary redistribution. Native crypto/SQLite-related notices still need build-specific review |
| com.google.crypto.tink:tink-android:1.24.0 | Matching JAR; exact POM declares Apache 2.0; JAR has no license/notice entry | Exact tink-java v1.24.0 LICENSE downloaded and staged. Root repository listing has LICENSE and third_party but no root NOTICE; that is not proof that all bundled native/transitive obligations are exhausted |
| com.google.ai.edge.litert:litert:1.4.2 and litert-api:1.4.2 | Matching AARs; exact POMs declare Apache 2.0; each AAR contains root LICENSE | Preserve those exact license texts in the distribution. Root AAR LICENSE entries were not found at that path in the inspected APK; an Apache model license elsewhere does not identify every runtime/native component |
| com.google.mlkit:text-recognition:16.0.1 | Matching AAR; POM identifies ML Kit Terms of Service; AAR includes third_party_licenses.txt/json | Preserve the SDK's supplied third-party texts and applicable SDK terms/disclosures. Do not label ML Kit proprietary SDK/model terms as Apache merely because its bundled dependencies have Apache notices |
| ML Kit transitive common/vision/text-recognition modules, Play services base/basement/tasks and DataTransport/Firebase components | Exact resolved versions and POMs in JSON/TSV; many matching AARs carry third_party_licenses.txt/json | Their per-artifact text/offset maps are staged under unique coordinate paths. Do not overwrite same-named notices when combining them. Determine readable distribution coverage rather than assuming AGP retained root AAR text |
| androidx.activity:activity-compose:1.10.1, Compose material3-android:1.3.2, Work runtime:2.12.0, Room runtime:2.8.5, SQLite:2.6.2, Kotlin stdlib:2.2.10 | Exact resolved POMs and matching release artifacts where applicable; mainly Apache 2.0 declarations | Preserve required license/attribution materials for the actual resolved graph. Several AndroidX LICENSE entries survive in META-INF, but that does not establish complete runtime coverage |

The inspected APK contains six native libraries: libandroidx.graphics.path.so, libmlkit_google_ocr_pipeline.so, libonnxruntime.so, libonnxruntime4j_jni.so, libsqlcipher.so and libtensorflowlite_jni.so. Their nested native dependency/code provenance is not fully described by Maven POM licenses. Test-only Tesseract/OpenCV and host worker Python DLLs were not classified as Android release runtimes simply because they exist in app/build.

## ONNX Runtime exact-version gap

The exact local AAR hash is 09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032 and matches the release dependency report. Its exact cached POM identifies version 1.26.0, MIT and Microsoft/ONNX Runtime project URLs. The AAR itself carries native 1.26.0 version strings.

However, direct HTTPS requests for the official v1.26.0 LICENSE, tag API, release API and rel-1.26.0 branch returned HTTP 404 during this audit. A web text reader returned cached MIT content for one raw URL, but that is insufficient to claim the exact versioned notice was downloaded. The staging manifest records the failure and contains no invented ONNX notice. Obtain a publisher-verified source commit/release archive or artifact notice bundle for this exact AAR, then preserve its actual copyright/license and third-party attribution. [Exact cached POM's project](https://microsoft.github.io/onnxruntime/) · [Attempted version-tagged license](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.26.0/LICENSE)

The successful small upstream downloads were [SQLCipher Android v4.20.0 LICENSE](https://raw.githubusercontent.com/sqlcipher/sqlcipher-android/v4.20.0/LICENSE) and [Tink Java v1.24.0 LICENSE](https://raw.githubusercontent.com/tink-crypto/tink-java/v1.24.0/LICENSE). Their original bytes and SHA-256 values are recorded in staged-notices/sources.json. An upstream license is not a guarantee that a binary's complete third-party notice set has been identified.

## Current APK versus candidate notice bundle

The observed APK now contains model notices under assets/third-party-models, including OpenAI CLIP MIT, model cards, Apache/model-specific license texts and sources.json. Those are distinct from the runtime notices audited here. See model-distribution-audit.md for their provenance and remaining model-specific limits.

APK filename search found 14 entries containing license/notice/copying, primarily model license files and six AndroidX META-INF licenses. Additional model texts use names such as Apache-2.0.txt and OpenAI-CLIP-MIT.txt and were checked separately. None of the runtime AAR third_party_licenses.txt/json root filenames or LiteRT root LICENSE survived under those names in the inspected APK. An absence of matching filenames alone is not proof no equivalent text exists elsewhere; the current app has no established runtime notice mapping/viewer proving equivalent coverage.

The staged runtime candidate contains 83 preserved/downloaded notice files (about10.1MiB including mapping overhead). It includes exact cached artifacts' notice entries and two verified upstream licenses. Several SDK text files repeat common dependencies. First preserve coordinate provenance and exact text; a later deduplication may share identical content by SHA-256 while retaining every component mapping. Do not merge by filename or remove copyright/NOTICE content merely to reduce the size.

## Next integration and verification

1. Resolve the exact ONNX Runtime notice/source gap and review native third-party coverage. Keep this unresolved status visible; MIT in a POM is not the missing copyright text itself.
2. Review the staged candidate and component obligations; integrate accepted texts under a dedicated third-party-runtime asset namespace, separate from model notices.
3. Add readable offline About/licenses access with component/version/source mapping. A license file buried inside an APK is not an accessible app credit path. Preserve required distribution documentation even if a UI is not mandated by a particular license.
4. Extend package inspection to verify notice hashes and expected mappings in APK and AAB. Rebuild only after the source/model freeze ends, then record final artifact hashes.
5. Repeat the inventory when Gradle versions, models, ABIs or native runtimes change. Preserve exact runtime coordinates, artifact hashes and upstream attribution provenance in a versioned release SBOM.

This audit did not edit app sources, assets, Gradle configuration or tools; it wrote documents and ignored build artifacts only. It did not inspect signing material, run a compile, install an app, sign a file, access a phone or deploy a worker.

## Subsequent isolated app integration — acceptance pending

Under PL036/PL129/PL140 release-attribution work, the reviewed candidate runtime texts have now been copied into app/src/main/assets/third-party-runtime with one file per unique SHA-256. The 83 candidate files reduce to 22 distinct documents totaling 6,180,464 bytes. The 144-coordinate index retains component versions, matched artifact hashes, source URLs/entries and unresolved statuses without local Gradle, Windows-user or signing paths. It does not mark all components legally cleared.

LibrarySourcesMenu now has a separate App licenses and notices entry. RuntimeNotices reads the bounded component index first and reads/verifies only the selected notice on demand. RuntimeNoticesDialog performs those reads on Dispatchers.IO, renders long content as lazy 4,000-character text chunks and preserves full text. The existing self-identity action and model-notice viewer remain separate and unchanged.

RuntimeNoticesTest checks initial index-only access, exact package hashes and UTF-8 round trips, sanitized metadata, missing or altered text failures and the actual Sources → SQLCipher notice → back navigation. Source and assets were checked for all 22 hashes, deduplication, UTF-8 and valid resources. One exact cached notice is empty and maps to four coordinates. Its zero-byte asset and links are preserved and the viewer explains the empty upstream file.

The release inspector validates the asset index and bidirectional component mappings before checking every declared byte count and SHA-256 in the APK and bundle. It rejects missing files, extra paths under the runtime notice namespace, duplicate entries and additional copies in another bundle module. The artifact report records the 23 expected file digests and separate findings for each package. The 23 files comprise 22 documents and their index, covering 83 mappings across the 144-coordinate inventory. Six host tests pass, including the empty-file digest and archive tampering. The previous release archives lack all 23 runtime notice paths. Compilation, lint, the new release archive checks and emulator UI verification are tracked separately in execution status.
