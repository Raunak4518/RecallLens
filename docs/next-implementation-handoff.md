# Review and next implementation handoff

Review dated 17 November 2026. Work belongs in D:/photo-library. Read the architecture review, scenario matrix and original dependency checklist together.

## Current assessment

The app has encrypted persistent OCR and embeddings, face/object suggestions, explicit name confirmation, durable WorkManager indexing, encrypted nearby-location caching and an authenticated cloud OCR development service. Android full/redacted upload, encrypted result persistence and process-restart indexing have passing emulator acceptance evidence. Supported person/color conjunctions use cached garment evidence. Broader relational accuracy and physical-device qualification remain open. Persistent local APK/AAB signing is verified; public distribution qualification remains open. The user now requires emulator-only testing and defers hosting in favor of on-device use. Read execution-status.md for current receipts; this document retains the original package order.

The boundaries around PhotoProcessor, PhotoIndexSession and application composition are worth keeping. They apply adapter, factory and repository concepts with a small API. Naming such as PhotoText, ModelRevision and NamedSubject is clear. IdentityEncoder and IdentityModelFactory now separate matching from the SFace and DINO adapters. The extraction preserved preprocessing, cache serialization and fingerprints and passed unchanged lookalike scores. A candidate with different output dimensions still requires a versioned vector codec change and compatibility tests; the current codec supports the installed128/384-dimensional outputs.

LibraryViewModel owns gallery reload, indexing, search, subject editing and identity review. Its multiple jobs and shared mutable maps make cancellation and ordering difficult to assess. Extract indexing orchestration first, then query execution and identity review. Move media access and UI packages as those responsibilities are extracted. A folder-only reorganization would leave their coupling intact.

The current UI requires understanding Content, Text and Named search modes. Prefer one search entry with evidence-aware result groups, clear indexing coverage, source permission status, an explicit self identity setup and a separate name-review area. Preserve confirmed-only Named semantics. A result for a full conjunction must have evidence for all its predicates.

The worker verifies bounded encrypted request fields and returns authenticated encrypted output with idempotent receipts and model provenance. The service now enforces caller ownership, bounded queue admission, expiry/deletion and fenced job leases. Inference runs outside the SQLite write transaction. Full/redacted Android development-service exchange passed; public TLS configuration and operational qualification remain separate work.

SQLCipher protects derived database pages. Keystore wraps a random passphrase using AES-GCM. This protects copied app files subject to OS/key security. A running authorized app can access plaintext. Local redaction uses OCR identifier patterns and face detection, so undetected content remains exposed in approved cloud copies. The worker decrypts approved pixels for inference. These boundaries belong in the presentation and UI.

## Ordered work packages

| Order | Scope and task IDs | Dependency | Completion evidence |
| --- | --- | --- | --- |
| 1 | Durable bounded local indexing, PL021, PL041, PL066, PL070 | Existing source/model revisions and encrypted storage | Restart mid-batch, finish only pending work, handle edit/revocation during inference, expose per-task coverage and pause/resume. |
| 2 | Shared model contracts and benchmark integration, PL058, PL061, PL062 | Package 1 revision/commit contract | Swap OCR adapters at composition with unchanged source/storage workflow. Include decoder, model and preprocessing version in dependencies. |
| 3 | Larger OCR candidate comparison, PL071 | Frozen 300-photo manifest and package 2 | Run ML Kit and Tiny on identical inputs, export every missed query and timing, inspect difficult cases. Select on quality and actual device cost. |
| 4 | Query evidence and result groups, PL064, PL065 | Cached outputs and confirmed entity store | Self alias, confirmed intersection, optional related results, missing-evidence explanations, absent-query checks. |
| 5 | Person-to-clothing relation slice, PL064, PL071 | Package 4 and permitted group-photo annotations | Bind garment region to a specific person, test two people in different shirts and lookalikes, retain uncertain results as suggestions. |
| 6 | Encrypted location cache, PL062, PL064, PL070 | Source revision and permission invalidation contract | Repeated nearby queries avoid EXIF reads, location revocation and source edits invalidate cached use. |
| 7 | Production package and security verification, PL029–PL036, PL069, PL134 | Selected models and prior packages | ARM package size and peak memory, copied-state tests, backup/cache inspection, sustained indexing and thermal evidence. |
| 8 | Cloud workflow, PL091–PL112 | Local gate and service resources | Authenticated job API, exact-artifact consent, replay/tamper/revocation, safe retries and reviewed redaction. |

A focused day can implement and verify package 1 with a working handoff for package 2. The entire production roadmap requires additional model, device, security and service acceptance evidence. Further agents can work independently on package 3 preparation, UI flow review and annotated relational fixtures while one owner changes scheduling.

Use WorkManager to schedule persistent work with Android lifecycle support. Keep encrypted task records authoritative and revalidate source revisions at commit. Android scheduling limits, constraints and force-stop behavior must be tested. WorkManager does not promise uninterrupted processing. [Android persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)

## Measured constraints

The 5,000-photo synthetic fixture uses 48.8 MiB for one OCR line, scene vector, face vector and object vector per photo. Region-heavy photos can use more. The audited seven weight files total 237,030,786 bytes (226.05 MiB). Current signed package sizes and exact hashes are in app/build/signed-release-receipt.json; historical feasibility size is not the ARM64 release download size. Package budget and index budget need separate acceptance checks.

Cached search median on the paired fixture is 1.34 s for text and 3.20 s for text plus scene ranking. This excludes gallery enumeration, query encoding and rendering. The 15 focused emulator tests passed in the previous cache change.

The 300-real-photo ML Kit experiment found the target on 143/360 remembered queries. Preview selection saved 1.36% and reduced hits to 142. Use this as evidence against deploying that permanent skip rule. Prioritizing likely text while eventually processing all permitted photos needs a time-to-coverage experiment.

The larger paired 300-photo benchmark has completed: ML Kit found143/360 remembered-text targets, Tiny160/360, with44 gains and27 losses for Tiny. Total OCR components were499.30s and268.17s respectively on the x86_64 emulator. ML Kit remains the app default pending lost-query review and ARM qualification. See large-ocr-comparison.md. There is no measured universal best model or evidence supporting zero accuracy/compute tradeoff. Training should follow a labeled residual problem after pretrained comparisons.

## Review limitations

The three delegated reviewers hit a usage limit before delivering independent findings. This handoff comes from the primary agent's direct code inspection and existing test artifacts. The code graph tool rejected the folder because it has no repository marker, so relationships were inspected from source. This review added documentation and ran no new device tests. The earlier benchmark transcripts remain the cited execution evidence.

## Current handoff additions, 18 November

The real 2,000-photo/60-query scene benchmark completed. Its generated body-part conjunctions are not natural personal-query acceptance; preserve its known-positive, explicit-negative and unjudged counts separately. See large-gallery-evaluation.md. The current emulator page size was measured as 4,096 bytes, so static 16 KiB alignment is not a 16 KiB runtime test. No physical phone was touched.

PL064/PL065 now have an initial passing partial Content workflow: 21 focused Android checks plus25 host checks passed before independent-review corrections. A named real-person anchor appeared only as a partial suggestion for an unsupported spatial request; unconfirmed same-person/lookalike photos stayed out, and Named stayed strict. Independent review requested generic relation/exclusion warnings and an unknown-self/known-object test, now passing. Independent source review exposed an async cache-publication race; its guarded helper and four deterministic tests passed. Screenshot review separately found the stale worker-batch denominator. The corrected current-gallery denominator and exact rendered-string assertion passed the final two UI reruns. The logic snapshot passed28 checks, with separate exact receipts in partial-content-search.md.

Exact model notices are packaged and readable in Sources. The privacy-policy draft records inspected data flows without inventing a publisher/contact. Owner-provided publication identity/contact, portable signing custody and Play account/testing remain external release inputs. Hosting is deferred, not a missing local-app prerequisite.

PL028/PL064/PL065/PL070 now also include explicit encrypted self selection and real GPS filtering inside bounded complex Content queries. The preserved self-nearby-verification receipt records 58 Android passes, one opt-in skip, 25 host passes and build/lint. Both actual self-selection and complex nearby UI flows passed. Named stays confirmed-only. The location fixture controls EXIF coordinates; it does not establish visual place recognition or universal query accuracy. See self-and-complex-location-plan.md, self-identity.md and complex-location-review.md. Current source is newer than the old signed artifacts. The previous refresh signing command was rejected before execution; no retry or alternative route was attempted.
