# Separate cloud-system blueprint: private photo library with selective cloud assistance

This blueprint describes the separate hybrid/cloud system. It is not the active Android app plan. The Android phone app is local-only and is governed by [final-android-search-plan.md](final-android-search-plan.md). Do not couple the app to cloud services or transfer this blueprint's cloud requirements into its implementation.

Version 4. Updated 18 November 2026, Asia/Calcutta; original plan dated 14 November.
Status: working Kotlin/Compose implementation with scoped emulator acceptance; production qualification remains incomplete. Read execution-status.md for current source-specific receipts and remaining dependencies.

This revision fixes Android as the first client and includes local, explicitly approved full-image cloud and locally redacted cloud processing. Generative LLM/VLM evaluation is required later, alongside the non-generative baseline; it is not removed or an optional extension. It supersedes the earlier draft that made encrypted backup central. Backup and file sharing are optional extensions. Novelty and convincing users to switch products are not acceptance requirements.

Read [research and decisions](research-and-decisions.md), [model and evaluation plan](model-and-evaluation-plan.md) and the [dependency checklist](implementation-todo.md).

## 1. Objective

Make an existing photo library searchable while protecting its derived private information. Process photos locally first; offer stronger processing for selected tasks through either a reviewed unmasked copy or a reviewed locally redacted copy.

Two-sentence explanation:

> The Android app helps you find photos already on your phone and encrypts its private search index. You can keep processing on the phone or explicitly send selected photos, with or without redaction, for stronger cloud processing.

The second sentence describes a controlled disclosure, not computation on ordinary ciphertext.

Current delivery priority is a complete on-device app. The user explicitly restricted testing to the emulator and deferred public hosting. Local use must not wait for a cloud account or a generative model. Optional cloud modes stay separately consented; no service was provisioned. Later LLM/VLM comparison remains planned, with deployment conditional on measured benefit and phone cost. A pinned small VLM has only a Windows host smoke, not Android qualification.

Search results must distinguish confirmed reference matches, text matches, estimated person/color evidence and approximate scene/partial suggestions. Unsupported conditions remain visible. An arbitrary natural-language prompt is not a promise that all its people, ownership, clothing, spatial, location and historical constraints are understood or true. Thousand-image scene evaluation must not be reused as identity/OCR/GPS ground truth. See large-gallery-evaluation.md and next-user-workflows.md for the measured baseline and remaining predicate work.

Sources now provides an explicit Who is me? selection from saved people, persisted in the encrypted schema11 index. This binds retrieval pronouns without authenticating identity or confirming new photos. Complex affirmative Content queries ending near/around/in an exact saved-place name use confirmed GPS anchors and a 30 km radius before scene ranking. A verified location does not verify unsupported clothing/spatial/history conditions. Missing permission, anchors or stale source evidence cannot trigger an ungeofiltered fallback. The emulator-only acceptance record is self-and-complex-location-plan.md.

### The essential correction

A conventional vision model processes decrypted pixels. TLS, AES or public-key encryption can protect those pixels in transit and while queued/stored; the authorized inference worker sees the approved full or redacted image in memory.

The protection claim is:
- The gallery file is not moved or modified; local mode uploads no image or derived content.
- Cloud modes send only the approved rendered artifact. Full-image mode discloses the visible content; redacted mode discloses the remaining visible content.
- Removing metadata is not the same as redacting private details visible in pixels.
- Intermediary storage and network observers cannot read the encrypted payload.
- The authorized worker can read that artifact and its generated output.
- The local index and returned result are encrypted at rest.
- Processing can be restricted to a trusted personal workstation instead of a third-party worker.

To hide plaintext from a cloud operator, a different architecture such as attested confidential computing or appropriate FHE/MPC is needed. These are separate research extensions, not properties supplied by ordinary encryption. Redaction does not guarantee anonymity.

## 2. What the system does

| Area | Required responsibility |
| --- | --- |
| AI/ML | Image-text retrieval; local text extraction; required later evaluation of generative visual descriptions; estimated benefit of stronger processing; sensitive-region detection |
| Cryptography | Encrypt the local database/caches; protect queued requests/results; bind encrypted data to the correct job and context; protect keys |
| Information security | Explicit source access, cloud consent, safe redaction, stale-result rejection, account isolation, parser limits, secret-free diagnostics and lifecycle consistency |

A pretrained model is acceptable. Using standard cryptographic libraries is correct. Engineering quality, measured usefulness, privacy boundaries and failure handling are the project contributions.

The application never automatically shares a file, deletes an original, changes permissions or executes instructions found in an image or model response.

## 3. Scope and milestones

| Gate | Completion | Outcome |
| --- | --- | --- |
| G0 | PL019 | Android hardware/support matrix, model/runtime, encrypted storage and trust contracts qualified |
| G1 | PL072 | First working Android app: local search, encrypted state, lock, permissions and lifecycle handling |
| G2 | PL090 | Full-image and redacted export preparation, preview, consent and cancellation |
| G3 | PL112 | Three working modes using the non-generative baseline; authenticated encrypted cloud jobs and safe integration |
| G4 | PL128 | Required later non-generative versus generative LLM/VLM evaluation across the three modes, with routing evidence |
| G5 | PL144 | Independently tested Android release candidate and contributor handoff |
| G6 | PR040 | Production/Play release readiness, operational controls and paid-service readiness |

G1 is useful without an account, cloud service or generative model. G3 is a working hybrid system before the later LLM/VLM comparison. G4 requires completing that comparison, not automatically deploying the largest model. Fine-tuning and learned-router deployment depend on measured benefit.

The [product roadmap](product-roadmap.md) defines the complete product destination. G1-G3 are staged working versions, not claims of production readiness. All task dependencies and acceptance evidence are in the checklist.

Current exclusions: healthcare interpretation, identification of strangers or biometric authentication, deepfake detection, cloud training on user photos, general chat, automatic photo deletion and fully homomorphic VLM inference. The user subsequently authorized local face/instance similarity for their own permitted library: detectors suggest regions, the user confirms names, encrypted reference vectors support separate possible-match review, and unconfirmed suggestions cannot become Named results. Backup, general file sharing and other clients remain separately scoped extensions.

## 4. Android app and platform choices

Android is the approved first platform. Build a native Kotlin application with Jetpack Compose, not a desktop Python app repackaged for phones.

Proposed stack, subject to G0 qualification:
- Kotlin, Compose and lifecycle-aware state holders; suspend functions and bounded coroutines keep inference off the main thread.
- MediaStore for the permitted gallery, with full, selected-only, denied and revoked permission states. The main setup requests gallery access through Android's permission dialog. The system directory picker grants access to individual folders and their subfolders.
- Room with a maintained, compatible SQLCipher Android integration for the encrypted catalog; qualify migration, journals and native library packaging.
- Android Keystore for wrapping random data keys. User-authenticated key use determines when indexing may proceed.
- WorkManager for durable bounded work, checkpoints and retries; qualify foreground execution and current OS quotas. Do not promise uninterrupted indexing while locked or in the background.
- A qualified Android inference runtime such as ONNX Runtime Mobile or LiteRT, selected after model conversion, quality, license and device tests.
- Local OCR with a qualified Android SDK or native runtime; evaluate ML Kit and a suitable native alternative rather than assuming a desktop OCR package is drop-in.
- Versioned HTTPS service API, PostgreSQL job metadata, encrypted blob storage and bounded inference workers for optional cloud use.
- Maintained authenticated-encryption and envelope libraries with tested Android/server interoperability.

Record minimum OS, current Play target requirements, ABIs, physical device RAM/storage and accelerator capabilities at G0. Android 11/API 30 is a provisional support-floor proposal, not a verified compatibility promise. Test selected access on Android 14+ and scheduling changes on newer versions. Recheck store requirements before release.

Keep the first architecture small: UI, application/domain, data/platform, inference and remote transport boundaries. Start with packages, not a separate Gradle module for every class. Constructor injection is sufficient; add an interface when a real platform/model boundary needs substitution.

A Windows or iOS companion comes later under PX01. Sharing schemas does not mean all mobile inference code can be reused on every platform. Existing CueSeal services are not an Android backend implementation.

## 5. No-upload library workflow

1. User grants access to gallery items under Android's full or selected-only media permissions, or authorizes a folder through the system directory picker.
2. The app enumerates accessible media without copying or uploading every original.
3. It decodes bounded local inputs and computes local embeddings and supported extraction.
4. It stores paths/references, descriptions, OCR, model versions, vectors and job state in its encrypted catalog.
5. Search runs locally over decrypted-in-memory representations while the app is unlocked.
6. New or changed items enter a durable incremental queue.
7. Cloud assistance remains off until explicitly enabled, and each proposed artifact is reviewed before first-release transmission.

Downloading model weights is separate from uploading photographs. Model-ready local search must work with networking disabled. A third-party SDK's telemetry is separately audited; local inference alone does not prove zero network traffic.

The database is derived data. Deleting it can be repaired by rescanning accessible originals, but encryption does not prevent database deletion or provide availability. Losing the only photo copy cannot be repaired from an embedding or caption.

## 6. Local AI architecture and later generative evaluation

### Non-generative foundation

Start with a versioned image-text encoder, qualified local OCR and metadata filters. Evaluate CLIP/SigLIP candidates for actual Android compatibility before selecting weights. A text query and image embedding must use compatible preprocessing, tokenizer and model versions.

This already supplies AI/ML: semantic retrieval can find images without requiring a generated caption for every item. OCR provides exact-text evidence. A low search similarity is not a reliable measure of image difficulty and must never trigger gallery uploads.

Store vectors, OCR, supported local facts and provenance encrypted. Search initially uses a bounded in-memory scan or similarly simple measured approach; choose an ANN index only if library-size benchmarks justify it, and protect its persisted representation too.

### Required later LLM/VLM comparison

After the baseline three-mode system works, PL124-PL128 evaluate non-generative retrieval/extraction against generative enrichment. A vision-language model can describe visible relations; a text-only LLM can reformulate queries or process extracted text but cannot independently see image pixels.

Evaluate a small local VLM where the phone supports it and a stronger cloud VLM on both approved full and approved redacted artifacts. If a candidate does not fit or export, record that measured failure and evaluate a viable alternative. Do not call a desktop benchmark an Android result.

Evaluation is committed scope. Deployment, running generation over every photo and fine-tuning remain evidence-based decisions. Additional cloud text sent to an LLM is also a disclosure and requires a defined mode/policy; hidden OCR must not bypass image redaction.

### Output and search integration

Bound the schema for extraction, descriptions and visible facts. Treat model output as untrusted data with no tools, file operations or permission changes. Record model/preprocessing version, source revision, mode and redaction scope.

Preserve the original-image embedding and verified local OCR. Fuse image similarity, metadata and lexical/compatible semantic enrichment through a versioned ranking method. Never mix unrelated cloud and local vector spaces.

A redacted-cloud result cannot assert concealed details or overwrite verified local text with guesses. Preserve partial failures and show coverage, pause and resumption. Larger models and unmasked inputs may help, but no universal quality ordering is assumed.

## 7. Three modes and independent assistance gates

| Mode | Input and destination | Approval and limitations |
| --- | --- | --- |
| LOCAL | Accessible original pixels processed on the phone | Default, account-free; no image, embedding, OCR or search-query upload |
| CLOUD_FULL | Reviewed unmasked rendered copy sent to the selected worker | Explicit per-artifact approval; visible private details are disclosed; strip unnecessary container metadata |
| CLOUD_REDACTED | Locally cropped/masked, rendered and reviewed copy | Required local detectors/policy must succeed; only the exact approved export is sent |

LOCAL does not redact before inference: doing so discards useful information without reducing cloud disclosure because no cloud request occurs. Cloud is never necessary just to finish indexing the library.

For the first cloud version, the user selects one or more items and reviews the actual exports. Batch approval records each artifact separately and cannot extend to new or changed photos. No upload-all setting is implicit in enabling cloud.

Gate A is utility: a user request, failed local extraction, small text, unsupported layout or a qualified benefit rule may suggest help. Darkness or blur does not prove missing information is recoverable. A suggestion never authorizes an upload.

Gate B is permission to disclose this exact artifact. Verify source revision, current access, selected mode, destination, bounded task, model/policy version, consent epoch, size and budget. CLOUD_REDACTED additionally requires completed local redaction and serialized-export inspection. A masked secret cannot be recovered by the worker.

Failure leaves the task local or blocked with an explanation. Failed redaction must never switch to CLOUD_FULL. Choosing another mode is a new explicit action, regenerates the export and requires new approval. Ordinary search cannot quietly upload poor matches.

The recommendation model cannot override privacy rules. The later experiment measures usefulness separately for full and redacted cloud inputs; it does not assume either always beats the local result.

## 8. Local redaction and export

Redaction must happen before any third-party upload. Calling cloud OCR to decide what to redact would already disclose the original.

Pipeline:
1. Work from the current accessible source revision.
2. Normalize orientation and decode bounded pixels.
3. Detect relevant text, named entities, faces and supported identifier regions locally.
4. Apply task/user policy. Prefer the minimum useful crop when appropriate.
5. Use opaque masks or removal, not blur as a guarantee of concealment.
6. Render into a new image with an allowlisted metadata structure.
7. Strip original EXIF/GPS, filenames, embedded thumbnails, layers and extra frames.
8. Re-open and inspect the exact serialized export, not only a UI overlay.
9. Let the user add masks, crop, cancel or keep processing local.
10. Bind approval to that exact artifact digest and policy version.

Faces and OCR are separate detection responsibilities. Presidio's text-image redaction does not by itself cover every face, badge, screen, reflection, location clue or identifying background.

No automatic detector guarantees that all private information has been removed. Redaction reduces disclosed information; it is not a proof of anonymity.

For the first supported export, emit a single-frame PNG or JPEG after decode/render. Reject unsupported animated/layered/motion inputs from the cloud path until all contained content is handled.

If the task needs a masked secret, keep it local or explain that redacted cloud cannot answer it. CLOUD_FULL is a separate explicit choice, never a redaction-error fallback. Its fresh export strips container metadata but intentionally leaves visible pixels unmasked; the preview must explain that disclosure.

## 9. Local encrypted data and keys

Sensitive data includes filenames, paths, GPS, OCR text, captions, face regions, embeddings, thumbnails, query history, source fingerprints and cloud-job details.

Proposed key hierarchy:
- Random local data key for SQLCipher and separately scoped cache keys.
- OS-backed wrapping for normal device-bound protection.
- Optional app passphrase using a vetted memory-hard KDF and authenticated wrapping, if protection from copied app state under a separate unlock secret is required.
- Independent ephemeral request/result key material for remote jobs.
- Cloud account/session credentials separate from all content keys.

Do not store the raw data key beside the database. Do not hardcode an encryption password. Derive independent purpose keys through a vetted KDF or generate separate random keys.

Android Keystore protects wrapping keys, with hardware backing only where supported and verified. Configure authentication and key invalidation deliberately. Once the app unwraps the database key, plaintext is usable by the running app; Keystore does not make a compromised process safe. Device credential/biometric changes, reinstall and restoration onto another phone require explicit lost-key handling. Exclude device-bound key material and sensitive state from unintended Android backups; test backup/restore behaviour.

When unlocked, search needs plaintext vectors/text in memory. Close handles, pause key-dependent work and discard references on lock. Do not promise perfect RAM erasure in a managed runtime or protection against a hostile administrator.

Encrypt disk caches; configure database temp storage, WAL and journals; keep query history off by default; redact diagnostics. Full-disk encryption is complementary. This app does not automatically encrypt originals in the user's normal gallery.

Lost index key: explain that derived data can be rebuilt from accessible originals. User-written annotations require an explicit encrypted export/recovery feature if they are to survive key loss. No silent promise that all data can be reconstructed.

## 10. Remote job cryptography and trust

### Ordinary remote worker mode

Use TLS and standard application-layer encrypted envelopes for asynchronous storage. The envelope is useful because requests may wait in a queue and results may wait for the device to reconnect.

Proposed flow:
1. Client creates random job/request IDs and a return-encryption keypair.
2. Client packages the approved full or redacted export, mode, bounded task parameters and return public key.
3. Encrypt the package to the authenticated worker key using a supported envelope/HPKE construction.
4. Authenticated API stores ciphertext and operational job metadata.
5. Authorized worker decrypts in memory and runs the configured model.
6. Worker validates/bounds output and encrypts the result to the job's return key.
7. Client decrypts, validates schema/context and checks current local lifecycle state before committing.

Include protocol version, request ID, processing mode, artifact digest, destination identity, task type, model/policy version, consent epoch and opaque source revision token in the authenticated context. Private filesystem paths and original-photo hashes do not belong in server-visible identifiers.

Public-key encryption by itself does not prove sender identity. Bind requests to authenticated principals, authorized jobs and service-authenticated queue records. Authenticate result origin as well as decryptability, using the supported service/worker authentication mechanism. Review this composition and library interoperability before implementation.

A matching request ID alone is insufficient. Prevent replay, cross-account jobs, envelope substitution and unauthorized key replacement. Worker public-key updates use a trusted authenticated configuration path.

The worker and any provider administrator able to inspect that process can see the approved full or redacted plaintext and output. No server-blind claim applies to this mode.

### Retention and cancellation

A proposed default is deletion of request/result ciphertext within 24 hours after terminal state, with a separate maximum job lifetime. These are design targets to validate, not existing guarantees.

Do not log payloads, OCR, captions or secrets. Disable content dumps and unintended model telemetry. Document GPU memory, process crash and temporary-file behaviour; encrypted storage is not a substitute for these controls.

Cancellation stops queued work where possible and requests cleanup. It cannot retract bytes already delivered to a worker. The client must still reject stale results.

### Stronger privacy alternatives

Trusted workstation: original or redacted processing on a machine the user controls, under an explicit destination policy.

Confidential computing: release request keys only after verified attestation of the expected worker/configuration; include GPU protection where used. Requires supported hardware, key-release policy and a much broader trust review.

FHE/MPC: suitable for separately evaluated supported computations; ordinary VLM weights cannot simply consume AES-encrypted photos. Full vision-language processing is not a first-release commitment.

## 11. File identity and changes

Model content, location and lifecycle separately.

Records:
- Asset: logical item and access policy.
- Location: provider/volume identity, file ID/URI and current path, availability.
- ContentRevision: observed generation, size/time hints, verified local digest when needed.
- DerivedArtifact: model/policy version, source revision and encrypted output.
- ConsentRecord: processing mode, exact export digest, destination, task, revision, policy and revocation epoch.
- Job: immutable request snapshot and cancellation/revocation epoch.
- Tombstone: minimal encrypted identity needed to suppress stale results.

Paths are locations, not permanent identities. File IDs are scoped and can be reused; verify context and content/version rather than assuming eternal uniqueness.

| Event | Required user-visible and internal behaviour |
| --- | --- |
| Rename within an approved source | Update location; retain derived results only if content/version still matches |
| Move within approved sources on the same volume | Correlate stable provider identity and validate; update path without unnecessary inference |
| Cross-volume move | Usually treat as new location until byte digest confirms equivalence; preserve safe deduplication without conflating different files |
| Copy to another location | Track both locations; reuse compatible derived content only after verified equality |
| Edit at same path | Create new content revision; invalidate affected results and existing cloud approval; reprocess |
| Move outside allowed roots | Stop following it automatically; hide and purge derived data according to policy; require new source authorization |
| Send to recycle bin/trash | Hide from normal search, cancel pending work, revoke approval and purge sensitive derived results; keep only a minimal encrypted tombstone |
| Restore from trash | Revalidate access/content and reindex; do not restore stale approval or old cloud results automatically |
| Permanent delete | Purge derived records/caches and cancel jobs; keep a non-content tombstone only while needed to reject late work |
| Drive disconnected/network folder offline | Mark source unavailable; hide unavailable results by default, preserve encrypted state and avoid falsely declaring deletion |
| Permission revoked | Stop work, hide affected results and remove derived copies; permission changes are not an opportunity to scan outside authorized scope |
| Cloud result arrives after delete/edit/trash/revocation | Reject before persistence/index integration using revision and revocation epoch; request remote cleanup |
| Index directory deleted | Explain rebuild; do not imply photos were deleted; recover user annotations only if separately exported |
| Watcher loses events or app was stopped | Run bounded source reconciliation, then update the index; do not trust event delivery as complete |

Default trash policy prioritizes privacy by deleting derived data instead of keeping readable search results for trashed images. Restoring a photo may therefore require inference again.

For a future Windows companion only, watcher notifications can overflow. Re-enumeration is mandatory after an overflow or a missed interval. Recycle-bin detection varies by filesystem and shell behaviour; if trash state cannot be confirmed, mark the location missing/unavailable and suppress results rather than guessing permanent deletion.

Android uses MediaStore generations/version information and trash state where exposed under granted permissions. Content observers are hints; reconcile on resume, permission changes and provider resets. Do not invent access to another app's hidden trash. Persisted URI access may be revoked. Missing media under partial access is not proof of physical deletion; suppress inaccessible results and apply the documented derived-data purge policy. Removable volumes require availability checks.

Never restore, empty trash or delete originals automatically as part of index repair.

## 12. Job state and stale-result safety

Local indexing:
discovered -> queued -> processing -> indexed.
Changes lead to superseded, inaccessible, unsupported or retryable failure.

Remote assistance:
suggested -> privacy checked -> awaiting approval -> sealed -> queued -> processing -> result ready -> validated -> applied.

Terminal alternatives: rejected, cancelled, expired, failed and stale.

Apply a result only in a transaction checking all of:
- Asset remains accessible, active and in an approved source.
- Content revision still matches.
- Consent/revocation epoch and policy version are still valid.
- Request/artifact identity, processing mode, destination and model/schema are expected.
- Result was authenticated and parsed within size limits.

A tombstone prevents a late reply from recreating a deleted row. Reopening the app does not erase cancellation history for unfinished remote requests.

Retry the same immutable ciphertext or create a fresh request and fresh cryptographic material. Never re-encrypt different content under a manually reused nonce.

## 13. Architecture and LLD

| Boundary | Responsibility |
| --- | --- |
| SourceAdapter | Enumerate/open/observe permitted local assets; platform-specific identity and trash semantics |
| CatalogRepository | Encrypted asset, revision, location and artifact persistence |
| IndexCoordinator | Durable bounded work, cancellation, checkpoints and lifecycle reconciliation |
| ModelAdapter | Preprocessing, inference and schema normalization for a pinned model |
| SearchService | Local query encoding, filters and rank fusion |
| AssistancePolicy | Utility suggestion and hard privacy rules, kept separate |
| RedactionService | Local detection, masking, serialization, preview provenance |
| ConsentService | Approval of exact mode/artifact/task/destination and revocation |
| KeyStoreAdapter | OS wrapping, unlock policy, scoped crypto keys |
| RemoteJobClient | Encrypted submission/polling/cancellation/result retrieval |
| ResultIntegrator | Authenticated schema and current-version checks, transactional apply |
| UI state holders | Present progress, search and approvals; do not implement crypto/model/storage logic |

Keep source, model, key store and transport as real interfaces. Start with a few packages and constructor injection. Use an explicit job state machine, repository adapters and a model/transport strategy where there are genuinely multiple implementations.

Do not introduce deep inheritance, a generic workflow engine, global mutable singleton services or one giant UI/backend file. Catch and translate exceptions at external boundaries; preserve structured internal error causes and secret-free diagnostics.

Android UI invokes application services through lifecycle-aware state holders; inference uses bounded background execution and a qualified native runtime. Isolate high-risk decoding where feasible without assuming isolated services can freely access app keys. No embedded Python/localhost server is required. Re-evaluate any additional process boundary against Android memory, URI access and cancellation behaviour.

Cloud has an API, relational job store, ciphertext store, bounded worker and cleanup scheduler. No distributed vector search or microservice fleet is needed.

## 14. Formats, bounds and partial failures

First supported indexed formats: static JPEG, PNG and WebP after verified decode tests. Unsupported formats remain visible with a reason; never silently claim a complete index.

Initial cloud export: single-frame rendered image labelled CLOUD_FULL or CLOUD_REDACTED. File extension does not establish format. Bound dimensions, decoded memory, task/prompt length, queue size, output schema and model resource use.

Do not allocate RAM for the whole gallery. Use paginated enumeration and a durable small worker pool. Maintain per-item progress. Keep successful indexed items if a single file fails.

Constrained resources may make a job slow or unavailable; report that honestly. A corrupt or truncated photo is not automatically repaired by a larger model.

For all CPU/GPU/model/storage failures: preserve originals, avoid false completion, allow bounded retry, and surface a specific next action.

## 15. UX requirements

Gray-and-white native visual style; accessible typography and controls; restrained animation.

Primary areas:
- Library/search with accurate indexed/remaining counts.
- Photo detail with source availability and model provenance.
- Improve selected photo: keep local, send full image or send a redacted copy, with an explicit explanation of each mode.
- Redaction preview with visible destination, exact disclosed artifact and editable masks.
- Activity with cancel/retry and understandable failure reasons.
- Settings for sources, lock, models, privacy policy, network/cost and data removal.

No mandatory account for local use. No manually pasted access token. No uncalibrated confidence percentage. No claim that encrypted means the inference worker cannot see the photo.

For privacy approval, show what is being sent rather than a generic consent checkbox. Changing the mode, image revision, masks, task, destination or policy invalidates that approval.

Cloud failure leaves local search usable. Cloud sign-out leaves local indexing intact. Ordinary search is not a hidden trigger to upload arbitrary candidates.

## 16. Security and trust limits

| Threat | Defence and test |
| --- | --- |
| Copied index directory | Encrypted DB/cache; key not stored alongside; copy/wrong-user/wrong-passphrase tests |
| Malicious running process as user | Limit exposure and require lock policy; explicitly outside a strong at-rest confidentiality guarantee |
| Payload theft from queue/storage | Per-job encrypted envelopes and separate worker/client keys |
| Worker/operator inspects input | Only the approved full or redacted artifact is sent; ordinary inference does not hide it from the worker |
| Sensitive region missed | Conservative local detection, user review, fail-closed errors, region-level evaluation |
| Hidden EXIF or extra frame leak | Re-encode an allowlisted export and inspect exact bytes |
| Another account requests a job/result | Authorization on every operation and scoped storage access |
| Result replay/substitution | Authenticated job context, one-time apply, revision/consent epoch checks |
| Image includes prompt injection | Treat content as data; fixed extraction task, no tools, bounded schema, no executable output |
| Decode bomb/resource exhaustion | Dimension/memory/time/queue limits and isolated workers |
| Logging/temp file leak | Default redaction, encrypted spill or no spill, inspection of journals/temp/crash paths |
| Deletion while worker runs | Client stale-result rejection regardless of remote cancellation success |

Encryption does not prevent deletion, availability failures, incorrect model output, privacy leakage from the remaining scene, or someone reading an unlocked original gallery. These limits must be in the project presentation.

## 17. Training and evaluation policy

Use pretrained models first. Build evaluation data before deciding to fine-tune. Detailed proposed sizes and experiments are in [the model plan](model-and-evaluation-plan.md).

Required later work compares generative and non-generative pipelines across all three modes. A learned assistance-benefit router is a conditional additional component; it predicts task improvement for the selected permitted mode, not image difficulty in isolation. Fine-tuning is conditional; completing the LLM/VLM comparison is not.

Do not label an image difficult simply because a large model disagrees. Use human-reviewed task outcomes and record when both are wrong or redaction destroys necessary information.

Fine-tuning a VLM is conditional on repeated measured failures and enough licensed, relevant examples. No training from scratch or automatic collection of users' private galleries.

Split by source person/event/template to avoid near-duplicate leakage. Keep a held-out final test set. Report learning curves, per-category errors and uncertainty; no universal sample-size guarantee.

## 18. Acceptance experiments

Required comparisons:
- Metadata/basic search versus image-text retrieval.
- Non-generative local baseline versus viable stronger local variants.
- Required later generative versus non-generative approaches in LOCAL, CLOUD_FULL and CLOUD_REDACTED.
- Stronger models on permitted full images versus the exact redacted inputs, with mode-specific privacy and cost results.
- Rules router versus optional learned router.
- End-to-end local versus approved hybrid workflow, including queue delay, redaction effort and network cost.

Report retrieval success, factual correctness/hallucinations, OCR error, redaction misses, over-redaction, missed beneficial escalations, unnecessary uploads, latency and actual resource use separately.

Security fixtures use fake identifiers and owned/consented photos. Real sensitive information must not be uploaded solely to prove a test point.

Lifecycle matrix must cover rename, copy, move, cross-volume move, edit, trash, restore, delete, permission removal, offline drive, watcher overflow and every corresponding in-flight cloud race.

A release is acceptable only if a fresh installation can complete real local tasks and a separate tester can trace exactly what was disclosed to the remote worker. No hardcoded positive result, demo-only bypass or claim that every image works perfectly.

## 19. Optional extensions

- Encrypted export/recovery of user annotations and settings.
- Selective encrypted photo backup with verified restore.
- User-controlled photo/file sharing with authenticated recipient keys.
- Windows/iOS companion clients after the Android system.
- Confidential-computing worker with attestation.
- Larger language coverage or additional media formats.
- Small, separately evaluated encrypted-inference/FHE experiment.

These do not gate the first hybrid app and are not inserted merely to increase feature count.

## 20. Delivery and repository policy

The photo project now has its own folder at `D:/photo-library`. Preserve CueSeal separately at `D:/secure audio`. Reuse engineering lessons and isolated utilities only after review; its audio matcher and cue-report architecture are not this app.

The project root and documentation folder are established; finalize the Android/service source layout before coding and retain prior work. Build a real installable Android package, model acquisition/integrity checks, a local-only mode, a versioned remote API, tests and an accurate README.

Publish only permitted fixtures and redacted reports. Never commit private photos, embeddings, raw captions/OCR, keys, tokens or unreviewed logs.

Use the checklist's IDs and direct dependencies. Record actual test evidence before marking an item complete. This planning task does not claim any of those implementation checks have run.

## 21. Production destination

The [product roadmap](product-roadmap.md) and PR001-PR040 cover cloud operations, service keys, retention, account lifecycle, permissions, disclosures, signed app/model delivery, Play testing, purchase verification and cost controls. These are required planned stages for the intended public paid product, not prerequisites for the first local release.

Production readiness requires real device, security, user-flow and operational evidence. No task is complete merely because it appears in this plan. Publication, paid activation and infrastructure spending need concrete release decisions when reached; this documentation update performs none of them.

