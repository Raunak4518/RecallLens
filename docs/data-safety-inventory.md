# Data safety inventory and release decisions

Prepared 18 November 2026 from app/src, app/build.gradle.kts and docs/local-privacy-verification.md. This is an engineering inventory, not submitted Google Play answers. The publisher is responsible for classifying the exact distributed build and all SDK behavior. Local use is account-free; public cloud hosting is currently deferred.

Google's form distinguishes processing confined to the device from transmission off the device, and includes SDK transmission. Optional cloud capability cannot be omitted solely because a particular tester never enabled it. Encryption readable by a configured worker does not make that transfer invisible to the operator. Complete the actual form using the current definitions and actual enabled flows. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

| Information | Current purpose and storage | Transmission / declaration decision |
| --- | --- | --- |
| Original image pixels and source metadata | Gallery/folder access, local decoding and search; originals remain in their provider | Local workflow does not upload them. Optional explicitly approved rendered copies are a separate off-device flow if enabled in the shipped app |
| OCR words and regions | ML Kit local extraction; encrypted photo_text records | Local only in the normal path. Approved worker returns OCR that is stored in encrypted cloud_text; review both directions and the operator's processing |
| Scene vectors and queries | CLIP image/text encoders; encrypted image vectors and in-memory query encoding | No application cloud query/vector upload found in current local workflow |
| Face/object vectors and confirmed labels | Local identity retrieval, encrypted vectors, names, aliases, reference regions and proof hashes | Local processing can remain outside collected-data answers under Play's definitions; still disclose access/use and sensitive identity-derived state in the privacy policy |
| Clothing evidence | Local person/face regions, model-estimated color fractions and dependency versions | Encrypted local records; not a separate server flow in current code |
| Embedded photo GPS | Nearby-photo search after media-location access; encrypted coordinate cache | Local stored photo coordinates, not live GPS. Optional uploaded visible content can still reveal location even when original EXIF is not copied |
| Filenames, URIs and content fingerprints | Revision validation, matching originals, cache reuse and consent checks | Private derived state. The worker envelope uses a random source token rather than the original URI, but server metadata must be inventoried separately |
| Cloud configuration | Endpoint, bearer credential, pinned public key and debug-only local flag in SQLCipher | Credential goes to the configured service for authentication when a request is made; do not classify it as an app account created by local search |
| Approved cloud copy and job envelope | User-chosen full/redacted rendered PNG, mode/task/policy, digest and encrypted result material | Worker can decrypt approved pixels; operator, service location and configured retention must be identified if this capability ships enabled |
| SDK diagnostics | ML Kit/DataTransport private diagnostics database observed in emulator audit | Google documents device/app information, per-installation identifiers, feature configuration, performance and event metadata. Map exact installed SDK behavior to form categories; do not submit blanket no-collection or no-analytics claims |

ML Kit's published disclosure describes its latest SDKs and separately distinguishes bundled from thin features. This app pins bundled Latin text recognition 16.0.1. Verify version-specific behavior rather than blindly copying every category for unrelated ML Kit features. Google documents HTTPS transport for listed diagnostic data and no onward third-party transfer for that listed data; this does not establish the behavior of the whole app. [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

## Source evidence

- Manifest: INTERNET, READ_MEDIA_IMAGES, selected-photo scope, legacy read access capped at API 32, ACCESS_MEDIA_LOCATION; no live-location, contacts or microphone request.
- PhotoLibraryApplication.kt: local model/index composition; photo-text-index in noBackupFilesDir; optional CloudTransport/CloudConfiguration.
- EncryptedPhotoIndex.kt / IndexKey.kt: SQLCipher, wrapped random key and Android Keystore AES-GCM; backup disabled by manifest/data-extraction rules.
- NamedSubjectStore.kt: user labels and aliases can remain after reference pruning. Do not claim that removing one reference removes every label.
- PhotoExport.kt / OcrEnvelope.kt: fresh canonical rendered PNG, crop/opaque masks, approval binding and random source identifier; original sensitive visible content can remain in the approved copy.
- CloudConfiguration.kt / PhotoExportScreen.kt: user configures destination, key and credential; saved connection can be forgotten.
- CloudTextStore.kt: revision-bound encrypted OCR/result provenance persists locally; source scope/revision reconciliation removes stale use.
- worker/service.py: configurable TTL/quotas, encrypted requests/results, caller ownership, deletion/tombstones and operational metadata. No single default establishes a promise for an arbitrary configured worker.
- local-privacy-verification.md: finite offline/connected tests, network positive control and a bounded plaintext marker scan. Those observations are not a complete SDK data inventory.

## Decisions required before submitting the form

1. Fix the release package/flavor and enabled cloud behavior. The current account-free local app needs no hosting account. If cloud configuration remains available, document its possible transfers and the supported operator arrangement.
2. Confirm the publisher identity, contact/inquiry route, public privacy-policy URL, audience and territories. None has been invented in privacy-policy-draft.md.
3. Review all shipped dependency and model versions. Record SDK diagnostics separately from image/OCR content; inspect current SDK guidance and merged-manifest behavior.
4. Classify actual data types, purpose, optionality, collection/sharing and encryption according to the form. A service provider exception for sharing is not automatically a no-collection answer.
5. Specify local derived-data reset and remote deletion instructions. Do not advertise account deletion when there is no local account, or imply that forgetting a credential deletes a server's retained data.
6. Publish the approved policy and link it in app. Maintain the form if SDKs, models, transport, logging or business features change.

## About, privacy and notices UI gap

Current MainActivity and LibrarySourcesMenu expose sources, permissions, naming and cloud review. Source search found no About, privacy-policy or third-party-notices screen. Bundled model license files exist in .local-models, and the model audit must confirm their redistribution completeness. Files inside an APK are not a discoverable in-app attribution path.

A small proposed follow-up is a clearly named About and privacy entry with app version, offline/local explanation, the approved policy text/link, actual third-party model/library notices, and clear-data guidance. Use bundled notices for offline reading; do not add a web sign-in requirement. External policy/contact details must come from the publisher. This is a proposal only: no screen or release-source edits were made during the current verification freeze.

This inventory and draft are not legal clearance, an approved policy, a Google Play review result or a claim that Data safety responses have been submitted.
