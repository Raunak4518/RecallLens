# Complete implementation checklist

Planning date: 14 November 2026. Version 3. Scope: the Android-first three-mode [blueprint](blueprint.md).

See the [G0 evidence record](g0-feasibility.md) for completed requirements and partial feasibility evidence. An unchecked item can have partial evidence. Check it only after its prerequisites and completion criteria are satisfied.

The [public dataset evidence](dataset-evaluation.md) records the real-image sample, metrics, and acceptance conditions for PL007, PL011, PL015, PL023 and PL058.

There are 184 planned core/product tasks (144 PL and 40 PR), plus 8 optional extension-entry tasks. Required later LLM/VLM evaluation is in PL124-PL128, not in the optional extensions. Optional entries are scoping gates; a selected extension receives its own detailed implementation checklist before coding.

## How to use this list

- Choose a task ID and read its direct prerequisites. Their prerequisites apply transitively.
- Independent means no task dependency, not that external resources are unnecessary.
- Complete a task only when its specific evidence is recorded.
- Implement a capability once and test meaningful behaviour; avoid duplicate tests that merely mirror code.
- Do not begin product coding until the Android support and dependency-feasibility gates are passed. Small feasibility experiments belong to G0.
- Use a handoff containing task ID, changed paths, test commands/results, artifacts and unresolved dependencies.
- No novelty or market-switching proof is required. Functionality, privacy, correctness and maintainability are the criteria.
- Source/model/privacy inspection can proceed independently where the listed prerequisites permit it.
- Do not add source-code comments, per the user's instruction. Keep explanations and design rationale in the documentation.

## Milestone map

| Gate | Completion task | Meaning |
| --- | --- | --- |
| G0 | PL019 | Qualified Android support, baseline runtime, encryption and key storage |
| G1 | PL072 | First working local encrypted photo-library app |
| G2 | PL090 | Both cloud export modes and exact-artifact review |
| G3 | PL112 | Three working modes using the non-generative baseline |
| G4 | PL128 | Required later non-generative/generative comparison; training is conditional |
| G5 | PL144 | Android release candidate and teammate handoff |
| G6 | PR040 | Production, Play and paid-service readiness |

The dependencies are a directed acyclic graph. Number order is a convenient reading order, not a requirement to wait on unrelated tasks.

## Resources that can block work

| Resource | Affected tasks | What to do until available |
| --- | --- | --- |
| Physical target machine/device and library inventory | PL002, PL003, PL015, PL018, PL069, PL134 | Continue schema, threat-model and permitted-fixture planning |
| Missing class descriptions | PL004 | Keep the project title provisional; do not claim the whole class was checked conclusively |
| Rights/consent for realistic photos and tasks | PL007, PL058, PL113-PL117 | Use owned/generated functional fixtures; do not claim realistic retrieval performance |
| Independent relevance/privacy assessor | PL114, PL117 | Prepare annotation rubric; keep judgments provisional |
| Supported stronger-model compute | PL099, PL125 | Test local base and privacy path; no invented cloud results |
| Auth/service deployment resources | PL091-PL112 | Use isolated local service integration when feasible; remote acceptance remains pending |
| Independent tester/reviewer | PL142, PL143 | Produce a concrete reproducible release candidate and evidence |

## Workstreams

| Workstream | Typical tasks | Shared contracts |
| --- | --- | --- |
| Local app and lifecycle | PL020-PL024, PL037-PL056, PL065-PL070 | PL009, PL010 |
| Cryptography and storage | PL025-PL036, PL095-PL108 | PL006, PL010 |
| Models and evaluation | PL057-PL064, PL071, PL113-PL128 | Model/version/output schema |
| Redaction and consent | PL073-PL090 | Exact-artifact approval and revision semantics |
| Remote service | PL091-PL112 | Authenticated encrypted job format |
| UX and release | PL129-PL144 | Actual test results and supported boundaries |

Parallel work requires the listed contracts to be fixed. It does not justify separate teams inventing incompatible schemas or cryptographic formats.

## Task register

### A. Requirements and evidence

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [x] | PL001 | Freeze the current objective | Independent | The blueprint and G0 evidence record capture the approved Android-first objective, three processing modes and required later generative comparison. |
| [ ] | PL002 | Inventory target Android phones and photo libraries | Independent | Record Android version, ABI, RAM, free storage, actual gallery volume, removable storage, languages and at least one physical test phone; mark unavailable device evidence pending. |
| [ ] | PL003 | Fix the Android support matrix | PL001, PL002 | Android is already selected. Choose minimum OS, current target SDK, ABIs and low/mid/high device test coverage from actual access; do not build a second client. |
| [ ] | PL004 | Resolve missing class-project descriptions | Independent | Check the blank/vague titles before submitting the final title; do not publish classmates' personal data. |
| [ ] | PL005 | Freeze supported image/task/language scope | PL001, PL003 | List indexed/exported formats, retrieval/extraction tasks, languages, explicit bounds and unsupported cases. |
| [ ] | PL006 | Freeze threat model and disclosure policy | PL001, PL005 | Separate LOCAL, CLOUD_FULL and CLOUD_REDACTED; document worker-visible plaintext, threat limits, consent, key lifecycle and account-free local use. |
| [ ] | PL007 | Define media consent and data rights | PL005, PL006 | Separate permission to evaluate, train, upload and redistribute; specify private storage and deletion rules. |
| [ ] | PL008 | Review model, SDK and library licenses | PL005 | Pin candidate sources and separate software, weights and data terms; reject unqualified redistribution. |
| [ ] | PL009 | Specify entities, state machines and ownership | PL005, PL006 | Define asset/location/revision/artifact/consent/job/tombstone records and atomic transitions. |
| [ ] | PL010 | Specify extraction and encrypted-job schemas | PL006, PL009 | Specify bounded extraction and authenticated mode, artifact digest, source revision, destination, model/policy, consent epoch and return key; model output never executes actions. |
| [ ] | PL011 | Freeze measurable acceptance conditions | PL002, PL005, PL007 | Specify baseline tasks, correctness/privacy measures and provisional hardware targets without claiming results. |
| [x] | PL012 | Preserve existing CueSeal work | Independent | The Android work lives in a separate root with a documented handoff and independent build. |

### B. Platform and dependency feasibility

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [x] | PL013 | Create the isolated project layout | PL012, PL001 | The photo-library project owns its app module, toolchain, plans and agent handoff in its own root. |
| [ ] | PL014 | Prepare Android development and service test tooling | PL003, PL008, PL013 | Pin Android Studio/JDK/Gradle/Kotlin/runtime versions and emulator/device setup; keep private media outside source/dependency trees. |
| [ ] | PL015 | Qualify the mobile encoder and OCR baseline | PL014, PL005 | Measure inference, export compatibility, memory and packaging on a physical Android device. Generative LLM/VLM feasibility belongs to PL124-PL125, not this gate. |
| [ ] | PL016 | Qualify Room and current SQLCipher Android integration | PL014, PL008 | Test maintained compatible artifacts, Android ABIs/native page-size support, encrypted migration and journals; reject incompatible or unmaintained bindings. |
| [ ] | PL017 | Validate cryptographic library interoperability | PL014, PL008, PL010 | Client and worker can use the proposed standard AEAD/envelope primitives with reproducible test vectors. |
| [ ] | PL018 | Qualify Android Keystore and unlock policy | PL014, PL006 | Verify supported key backing, authentication, lock/invalidation and restore behaviour; document compromised-runtime limits and unavailable hardware guarantees. |
| [ ] | PL019 | Pass feasibility gate G0 | PL011, PL015, PL016, PL017, PL018 | Choose viable artifacts/platform or revise the proposal before building around an unworkable dependency. |
| [ ] | PL020 | Create the native Kotlin/Compose app shell | PL019, PL009 | Installable Android app has lifecycle-aware navigation and state with no fake populated results. |
| [ ] | PL021 | Create bounded Android inference execution | PL020, PL009 | Coroutines/native runtime work off the main thread; decoder/model limits, cancellation and process-death handling are defined. Add process isolation only where justified; no embedded localhost server. |
| [ ] | PL022 | Set up CI and focused quality checks | PL020 | Clean builds, linting, meaningful unit tests and dependency/secret checks run repeatably. |
| [ ] | PL023 | Create permitted functional fixtures | PL007, PL013 | Include supported formats, rotations, Unicode paths, corrupt inputs, synthetic sensitive data and controlled lifecycle changes. |
| [ ] | PL024 | Create Android instrumented and service integration harnesses | PL022, PL023 | Emulator and physical-device tests use permitted fixtures, disposable encrypted catalogs and isolated service state; no personal gallery is mutated. |

### C. Encrypted local state and keys

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL025 | Implement independent local data keys | PL017, PL018 | Library-generated keys are wrapped by the OS and never saved raw beside the data or in logs. |
| [ ] | PL026 | Decide and test independent app-secret support | PL025 | Choose Keystore-only first unlock or a separately scoped passphrase wrapper. If included, use a vetted KDF/AEAD with recovery and wrong-secret tests; if deferred, document the exact supported threat boundary. |
| [ ] | PL027 | Integrate encrypted SQLite | PL016, PL025 | All sensitive tables persist encrypted and wrong-key opening fails without replacing the catalog. |
| [ ] | PL028 | Implement catalog records and migrations | PL027, PL009 | Versioned asset/location/revision/artifact/job records migrate without losing valid state. |
| [ ] | PL029 | Protect thumbnails and auxiliary artifacts | PL025, PL028 | Protect disk thumbnails, vectors, OCR and captions; test image-loader disk caches, exports, clipboard and Android backup exclusions rather than encrypting only Room tables. |
| [ ] | PL030 | Configure WAL, temporary files and diagnostics | PL027 | Inspect actual journal/temp/log/crash paths and prevent plaintext copies or key/payload logs. |
| [ ] | PL031 | Implement application lock semantics | PL021, PL025, PL026, PL027 | Lock pauses key-dependent work, closes handles and clears retained plaintext references as far as supported. |
| [ ] | PL032 | Protect locked UI and notifications | PL020, PL031 | Recent-app previews, notifications, clipboard and error messages do not expose private content or secrets. |
| [ ] | PL033 | Implement lost-key/index-deletion rebuild flow | PL031, PL028 | The app explains what is rebuildable, preserves originals and does not claim recovery of unexported annotations. |
| [ ] | PL034 | Version keys and encrypted artifact compatibility | PL017, PL028 | Unsupported/corrupt envelopes fail safely; future key/format migrations have explicit compatibility boundaries. |
| [ ] | PL035 | Test copied-state confidentiality | PL025, PL026, PL027, PL030, PL024 | Copied database/caches cannot be read without intended key access; test wrong user, wrong key and modified state. |
| [ ] | PL036 | Review the local security foundation | PL029, PL030, PL031, PL032, PL033, PL034, PL035 | Document supported guarantees and same-user/malicious-runtime limits before handling private corpora. |

### D. Media identity, moves, trash and deletion

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL037 | Implement Android gallery permission workflows | PL020, PL006 | The main setup uses Android's gallery permission prompt. Verify full, selected-only, denied and revoked access and a separate folder grant. Display only the authorized scope. |
| [ ] | PL038 | Implement paginated source enumeration | PL037, PL028 | Large sources are enumerated with bounded memory and explicit inaccessible/offline states. |
| [ ] | PL039 | Implement location and content identity | PL038, PL009 | Use scoped MediaStore URI/provider/volume identity and generation/content validation; filenames or reusable row IDs alone are insufficient. |
| [ ] | PL040 | Implement bounded decoding and orientation | PL023, PL038 | Supported images decode consistently; invalid dimensions/formats and excessive memory are rejected. |
| [ ] | PL041 | Implement durable bounded Android work | PL028, PL021 | WorkManager checkpoints and idempotent jobs survive process death, reboot and retries; obey key-lock, battery, network and OS quota constraints; no endless background-runtime promise. |
| [ ] | PL042 | Observe MediaStore and permission changes | PL039 | Normalize observer events and recheck on resume; do not assume a complete durable event stream or access to other apps' private trash. |
| [ ] | PL043 | Implement reconciliation after missed events | PL042 | Provider version resets, missed notifications, permission changes and app restart trigger paginated reconciliation; unavailable is distinct from deleted. |
| [ ] | PL044 | Handle same-volume rename and move | PL042, PL043 | Location updates preserve valid derived data and do not trigger avoidable full reprocessing. |
| [ ] | PL045 | Handle copies and cross-volume moves | PL043, PL039 | Verified content equality can reuse results while distinct locations remain independently tracked. |
| [ ] | PL046 | Handle edits at an unchanged path | PL040, PL039 | A new content revision invalidates stale derived output and existing cloud approval. |
| [ ] | PL047 | Handle Android trash evidence where accessible | PL042, PL043 | Use visible MediaStore trash state and access checks; ambiguous disappearance is suppressed without claiming permanent deletion. |
| [ ] | PL048 | Purge trashed items from active results | PL047, PL029 | Hide immediately, remove sensitive derived data and retain only minimal encrypted stale-result suppression state. |
| [ ] | PL049 | Handle restoration from trash | PL047, PL048 | Revalidate and reindex restored content without silently restoring old cloud consent. |
| [ ] | PL050 | Handle permanent deletion and revocation epochs | PL043, PL048, PL041 | Remove derivatives, cancel work and keep a minimal tombstone while late jobs remain possible. |
| [ ] | PL051 | Handle removable/offline sources | PL038, PL043 | A disconnected source is unavailable, not deleted; encrypted state can reconcile after reconnection. |
| [ ] | PL052 | Handle revoked permissions and moves outside scope | PL037, PL043 | Stop access and purge affected derivatives; do not follow the file into unauthorized locations. |
| [ ] | PL053 | Capture stable processing revisions | PL040, PL046 | Detect source mutation during reads and restart from a fresh snapshot instead of applying mixed-version results. |
| [ ] | PL054 | Implement atomic current-revision commit checks | PL041, PL050, PL052, PL053 | Jobs cannot commit against deleted, trashed, revoked or superseded content. |
| [ ] | PL055 | Run the complete local lifecycle matrix | PL044, PL045, PL046, PL049, PL050, PL051, PL052, PL054 | All named events and process-restart combinations have expected-state tests with preserved originals. |
| [ ] | PL056 | Accept the source/lifecycle foundation | PL055, PL036 | Source state is consistent under permissions, interruption and privacy operations. |

### E. Local models, index and search

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL057 | Pin reference model artifacts and preprocessing | PL008, PL015, PL023 | Record model/tokenizer/preprocess hashes and licenses; block mismatched/tampered artifacts. |
| [ ] | PL058 | Benchmark non-generative Android baseline candidates | PL057, PL007 | Compare qualified image-text encoders and local OCR for retrieval/text error, battery, memory and latency. Do not wait for LLM/VLM evaluation. |
| [ ] | PL059 | Validate exported and quantized model variants | PL057 | Compare with reference outputs and retrieval/extraction quality; retain precision when compression hurts the task. |
| [ ] | PL060 | Implement incremental local embedding index | PL040, PL041, PL059, PL031 | Vectors are encrypted at rest and bound to source/model revisions; partial work is not exposed as complete. |
| [ ] | PL061 | Implement local OCR with bounded output | PL058, PL040 | Text and region coordinates are stored privately with provenance, language and revision. |
| [ ] | PL062 | Implement versioned non-generative enrichment records | PL058, PL061 | Store OCR/metadata evidence with bounded schema and provenance; reserve an extension point for later generated descriptions without implementing generation now. |
| [ ] | PL063 | Implement compatible local query encoding | PL059, PL060 | Text uses the correct model space and runs without sending queries to a server. |
| [ ] | PL064 | Implement filters and evidence-aware ranking | PL063, PL028 | Metadata, image similarity and enrichment ranking are separable and versioned; unrelated vector spaces are never mixed. |
| [ ] | PL065 | Build usable search and photo detail | PL020, PL064, PL029 | A user can retrieve and open actual accessible originals without tokens, upload screens or fake results. |
| [ ] | PL066 | Expose progress, pause and resumption | PL060, PL041 | Index coverage is accurate and completed items survive cancellation/restart. |
| [ ] | PL067 | Implement safe model/index updates | PL059, PL060 | A failed model change preserves the working index; incompatible versions cannot be combined. |
| [ ] | PL068 | Verify local-only network and cache behaviour | PL065, PL030 | Model-ready search works offline; audit any SDK network activity and stored plaintext before making privacy claims. |
| [ ] | PL069 | Measure real library performance | PL064, PL066, PL058 | Measure cold/warm search, sustained indexing, memory, battery and thermal effects at 1k/10k and justified larger libraries on physical devices; report extrapolations separately. |
| [ ] | PL070 | Integrate lifecycle events with all derived artifacts | PL055, PL060, PL061, PL062 | Edits/deletion/revocation consistently invalidate embeddings, text, thumbnails and pending work. |
| [ ] | PL071 | Evaluate local retrieval/extraction quality | PL058, PL064, PL062 | Use grouped held-out retrieval/OCR tasks, report absent-item behaviour and per-category failure; do not claim generative quality results. |
| [ ] | PL072 | Pass first-working-app gate G1 | PL056, PL065, PL066, PL067, PL068, PL069, PL070, PL071 | A clean install indexes/searches actual local media, protects its index and handles the full local lifecycle. |

### F. Privacy policy, redaction and exact-artifact approval

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL073 | Specify three-mode disclosure and sensitive-category policy | PL006, PL007, PL005 | Define default LOCAL, separate CLOUD_FULL and CLOUD_REDACTED consent, mask categories, explicit no-downgrade rules and mode changes invalidating approval. |
| [ ] | PL074 | Establish local OCR-based redaction baseline | PL061, PL073 | Original pixels never go to cloud OCR to decide redaction; detector failures are explicit. |
| [ ] | PL075 | Qualify separate visual-region detectors | PL073, PL040 | Face/other promised-region detection has licensed artifacts and category tests; no face identification is added. |
| [ ] | PL076 | Implement text entity/pattern recognition | PL074, PL073 | Detected OCR regions map correctly to sensitive spans; bounded rules and model outputs are versioned. |
| [ ] | PL077 | Implement minimal crop and opaque masks | PL075, PL076, PL053 | Masks operate on current correctly oriented pixels; crop/mask transforms preserve needed task scope. |
| [ ] | PL078 | Render distinct full and redacted exports | PL077 | Produce fresh single-frame bytes. Full preserves approved visible content; redacted applies approved masks/crop. Never modify originals or upload the original multi-content container implicitly. |
| [ ] | PL079 | Strip metadata and hidden content | PL078 | Original EXIF/GPS, names, thumbnails, layers and extra frames cannot accompany the exported artifact. |
| [ ] | PL080 | Verify the serialized export | PL079 | Reopen and inspect the bytes that will actually be sent, with hashes and bounded dimensions. |
| [ ] | PL081 | Enforce mode-specific disclosure gates | PL080, PL073 | Both cloud paths require current access and exact approval; redacted path requires all mandated privacy steps. Failed redaction blocks rather than switching to full. |
| [ ] | PL082 | Build exact-artifact and three-mode review UI | PL065, PL080 | Show actual serialized export, selected mode, worker visibility, destination and task; local is always available and full disclosure cannot masquerade as redacted. |
| [ ] | PL083 | Implement manual mask/crop correction | PL082 | User edits regenerate and revalidate the export; originals remain untouched. |
| [ ] | PL084 | Bind consent to mode, bytes, revision, task and destination | PL083, PL054, PL010 | Changing mode/artifact/policy/context invalidates approval. Batch review records each item; new photos or revised bytes are never automatically approved. |
| [ ] | PL085 | Implement consent revocation | PL084, PL050, PL052 | Deletion/edit/permission loss cancels pending approval/work and increments the revocation state. |
| [ ] | PL086 | Implement keep-local and cancel decisions | PL082, PL085 | User can decline either cloud path and retain the existing local result; no sign-in or cloud connectivity is needed to keep local. |
| [ ] | PL087 | Build privacy test corpus with fake identifiers | PL023, PL073 | Annotated regions cover faces, text, rotations, low quality, metadata and deliberately missed cases without exposing real secrets. |
| [ ] | PL088 | Measure redaction recall and over-redaction | PL087, PL081, PL083 | Report category misses and damaged utility; no zero-risk claim follows from a finite test set. |
| [ ] | PL089 | Measure baseline utility loss from redaction | PL058, PL088 | On permitted fixtures compare baseline extraction/retrieval on original versus rendered full/redacted copies. This pipeline check does not require a generative model or prove cloud superiority. |
| [ ] | PL090 | Pass privacy-pipeline gate G2 | PL072, PL084, PL085, PL086, PL088, PL089 | Both cloud export modes can be prepared, inspected, approved or declined with no silent fallback; local search remains usable. |

### G. Authenticated encrypted remote processing

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL091 | Define service identity, API and deployment contracts | PL013, PL006, PL010 | Select maintained native-app authentication, worker trust roots and bounded versioned endpoints. |
| [ ] | PL092 | Implement relational job metadata and migrations | PL091 | Owners, encrypted payload references, states, idempotency and expiry have database constraints. |
| [ ] | PL093 | Implement normal app sign-in and session validation | PL091, PL020 | Use a maintained flow; validate issuer/audience/expiry as applicable; no manual access-token entry. |
| [ ] | PL094 | Enforce authorization on every job operation | PL092, PL093 | Other principals cannot submit as, read, cancel or delete a user's jobs even with known IDs. |
| [ ] | PL095 | Provision authenticated worker encryption keys | PL017, PL091 | Worker key distribution/rotation and trust are pinned to the supported authenticated configuration. |
| [ ] | PL096 | Implement client request/result key envelopes | PL095, PL084, PL054 | Fresh per-job key material and authenticated mode/context protect the approved artifact and return key; bind destination/consent epoch and keep private paths and source hashes off server metadata. |
| [ ] | PL097 | Persist only encrypted queued payloads | PL094, PL096 | Queue/blob storage contains ciphertext and minimum operational metadata, with bounded upload handling. |
| [ ] | PL098 | Authenticate/decrypt authorized worker jobs | PL097, PL095 | Worker rejects tampered, substituted, replayed or unauthorized jobs before model processing. |
| [ ] | PL099 | Run bounded non-generative cloud extraction | PL098, PL058, PL010 | Use a qualified stronger OCR/vision baseline under fixed task schemas and resource limits. Later generative adapters preserve the same contracts; cloud benefit is measured, not assumed. |
| [ ] | PL100 | Encrypt and authenticate job results | PL099, PL096 | Result origin and exact request/artifact context are authenticated; only the intended return key can decrypt. |
| [ ] | PL101 | Implement owner-only result polling/retrieval | PL094, PL100 | App can reconnect safely without exposing result payloads or other accounts' metadata. |
| [ ] | PL102 | Validate decrypted client results | PL101, PL054 | Reject wrong context, excessive schema, unsupported model/policy version and unauthenticated output. |
| [ ] | PL103 | Integrate results transactionally | PL102, PL070, PL085 | Current content, access, consent epoch and active lifecycle are checked atomically before index update. |
| [ ] | PL104 | Implement cancellation, TTL and cleanup | PL097, PL101 | Queued work stops where possible; terminal ciphertext expires; cleanup behaviour and inability to retract processed bytes are explicit. |
| [ ] | PL105 | Enforce quotas, budgets and rate limits | PL094, PL097 | Oversized/concurrent requests cannot bypass cost/resource limits; retry accounting is consistent. |
| [ ] | PL106 | Implement crash-safe idempotency | PL041, PL097, PL103 | Service/worker/client retries cannot duplicate applied output or resurrect cancelled jobs. |
| [ ] | PL107 | Test remote tamper, replay and account isolation | PL094, PL096, PL100, PL103 | Reject changed mode, artifact, key, destination, model/policy, revision or consent epoch; test replay, cross-account reads and forged results with expected outcomes. |
| [ ] | PL108 | Test every in-flight lifecycle race | PL103, PL104, PL055 | Rename/edit/trash/delete/revoke/offline transitions during processing cannot apply stale or forbidden output. |
| [ ] | PL109 | Audit worker disclosure, storage and diagnostics | PL098, PL104, PL030 | For each cloud mode inspect actual image and metadata sent, logs, temp/crash paths and retention. Ensure local OCR/private fields do not leak alongside redacted pixels. |
| [ ] | PL110 | Exercise all three modes end to end | PL090, PL103, PL104, PL105 | LOCAL works offline. Approved full and redacted jobs return authenticated encrypted results to the correct current asset. Capture permitted-fixture network evidence and redaction-failure/no-downgrade behaviour. |
| [ ] | PL111 | Test network and service failure recovery | PL106, PL110 | Timeout, disconnect, restart, queue expiry, DB failure and disk-full states leave local search working and status accurate. |
| [ ] | PL112 | Pass full-hybrid-system gate G3 | PL107, PL108, PL109, PL110, PL111 | All three baseline modes work with real outputs and measured boundaries; no generative dependency or demonstration bypass. |

### H. Data, routing and conditional fine-tuning

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL113 | Assemble the paired evaluation corpus | PL007, PL058 | Use permitted varied images/tasks with provenance and explicit cloud/training permissions. |
| [ ] | PL114 | Create independent task and region judgments | PL113 | Review relevance, visible facts, OCR targets and sensitive regions; resolve disputed labels with another assessor. |
| [ ] | PL115 | Freeze grouped training/validation/test splits | PL114 | Keep person/event/template/near-duplicate groups together and preserve a final held-out set. |
| [ ] | PL116 | Generate paired baseline outcomes across three modes | PL115, PL089, PL099 | Record local, approved full and redacted pipelines/artifacts with resource costs and failures. Keep permissions and mode-specific outcomes separate. |
| [ ] | PL117 | Label assistance benefit from reviewed outcomes | PL116 | Labels represent reviewed task improvement for the permitted mode; a larger model's disagreement or unredacted outcome cannot label redacted utility. |
| [ ] | PL118 | Implement explicit routing-rule baseline | PL117, PL062 | Task-specific features and user action suggest help; no query-score-only difficulty rule or privacy bypass. |
| [ ] | PL119 | Evaluate a conditional small learned benefit router | PL117, PL115 | When labels support it, fit an interpretable logistic/tree baseline using only pre-request features. Otherwise document the evidence-based deferral and retain rules/manual operation. |
| [ ] | PL120 | Compare router with rules and manual selection | PL118, PL119 | Measure missed beneficial escalations, unnecessary sends and final task quality at equal privacy/request budgets. |
| [ ] | PL121 | Calibrate and select thresholds on validation | PL120 | Thresholds have recorded tradeoffs and no final-test tuning; unsupported confidence displays are removed. |
| [ ] | PL122 | Integrate suggestions below privacy/consent rules | PL121, PL081, PL084 | A learned suggestion can never authorize a blocked or unapproved artifact. |
| [ ] | PL123 | Measure complete hybrid cost and latency | PL122, PL111 | Include local redaction, user review, network/queue, worker time and result integration, not only inference. |
| [ ] | PL124 | Design the required later LLM/VLM comparison | PL112, PL115, PL071 | Freeze non-generative versus generative tasks across LOCAL, CLOUD_FULL and CLOUD_REDACTED; include Android fit/runtime, hallucination, privacy, latency, battery and cloud cost criteria. |
| [ ] | PL125 | Evaluate generative and non-generative approaches | PL124, PL008 | Run viable small Android VLM and stronger cloud candidates with matched tasks and consent; measure infeasible candidates honestly. Evaluate text-only LLM query handling separately from image understanding. This comparison is required, not an optional extension. |
| [ ] | PL126 | Review learning curves and fine-tuning need | PL119, PL125 | Measure router data scaling where trained; decide narrow VLM tuning from repeated failures and rights. If justified, compare a permitted adapter; otherwise retain pretrained models with evidence. Do not make fine-tuning mandatory. |
| [ ] | PL127 | Run final held-out comparison | PL123, PL126 | Freeze models, policies and routing before final grouped held-out evaluation. Report non-generative/generative and all three modes, failures, uncertainty and device/cost measurements. |
| [ ] | PL128 | Complete required model-comparison gate G4 | PL127, PL112 | Publish measured comparisons and a reasoned deployment decision. Keep a generative capability only if justified; completing its evaluation is mandatory even if deployment is rejected. |

### I. Usability, packaging, release and handoff

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PL129 | Complete coherent native workflows | PL072, PL090, PL112 | Search, detail, stronger processing, redaction, approval, progress and settings form one usable app. |
| [ ] | PL130 | Apply accessible gray-and-white design | PL129 | TalkBack, touch targets, text scaling, contrast, rotation and reduced motion work in the gray-and-white Android design. |
| [ ] | PL131 | Verify understandable privacy/status wording | PL129, PL109 | Users can identify what leaves the device, who can see it and why a result is stale or blocked. |
| [ ] | PL132 | Complete index rebuild and lock guidance | PL033, PL129 | Lost key/deleted index/locked app recovery is clear without implying recovery of lost originals. |
| [ ] | PL133 | Implement signed/verified application-model update path | PL022, PL067, PL017 | Tampered artifacts are rejected; updates preserve data and can recover from interruption. |
| [ ] | PL134 | Package a signed Android release candidate | PL133, PL130 | Signed APK installs on a clean physical phone without development tools; AAB, model acquisition, native dependencies and offline mode are verified. Play publication is later. |
| [ ] | PL135 | Finalize notices and artifact provenance | PL008, PL133 | Software/model/data licenses, hashes and build provenance are accurate for shipped artifacts. |
| [ ] | PL136 | Run the full security and lifecycle suite | PL108, PL035, PL068, PL131 | Record tests for all lifecycle/crypto boundaries, permission transitions, key invalidation, backup/restore, mode substitution and accidental disclosure. Unsupported cases are explicit. |
| [ ] | PL137 | Observe end-to-end tasks without coaching | PL130, PL131, PL132, PL134 | A small consenting test group completes retrieval, redaction approval, cancellation and rebuild; usability findings are recorded without market claims. |
| [ ] | PL138 | Measure the packaged system under real workload | PL128, PL134 | Report quality/resources at documented library sizes and supported hardware; fix measured bottlenecks rather than speculating. |
| [ ] | PL139 | Verify service deployment and operator runbook | PL112, PL109 | TLS, restart, retention, cleanup, quotas, model provisioning and incident steps work with bounded secrets/access. |
| [ ] | PL140 | Write contributor and agent documentation | PL013, PL139, PL128 | README maps setup, module responsibilities, tasks, fixtures, format/model versions, test commands and limitations to actual code. |
| [ ] | PL141 | Prepare safe publishable source and evidence | PL135, PL140 | Secrets/private-media/index/raw-log scans pass; only permitted fixtures and redacted measured reports are included. |
| [ ] | PL142 | Run independent clean-install acceptance | PL136, PL137, PL138, PL139, PL141 | Another tester follows only the docs and completes local and approved-remote flows with expected failure cases. |
| [ ] | PL143 | Review maintainability and security composition | PL142, PL036 | Reviewer checks key lifecycles, races, LLD boundaries, simplicity, debuggability and no overstated guarantees. |
| [ ] | PL144 | Record Android candidate gate G5 and handoff | PL143, PL140, PL004 | Record actual builds/tests and limits. This is not a Play/SaaS launch; PR001-PR040 cover production and paid-service readiness. |


## Production and Play delivery

These are required planned stages for the complete public product. They do not block G1. Payment-specific implementation is exercised before a paid launch; a free beta may precede it but must not be called the completed paid product. See [product roadmap](product-roadmap.md).

| Status | ID | Task | Direct prerequisites | Completion evidence |
| --- | --- | --- | --- | --- |
| [ ] | PR001 | Inventory production data and retention | PL112, PL006 | Map operational metadata, ciphertext, account/billing data, deletion windows and processors; no hidden photo-training use. |
| [ ] | PR002 | Define capacity and service objectives | PL123, PR001 | Set measurable latency, uptime, queue age, cost and deletion objectives using measured demand assumptions. |
| [ ] | PR003 | Prepare reproducible service deployment | PR002, PL139 | Version infrastructure/configuration, TLS, environments, health checks and least-privilege network paths; no plaintext payload buckets. |
| [ ] | PR004 | Provision production secrets and worker keys | PR003, PL095 | Separate signing/decryption/account credentials, restrict operators and test rotation plus safe old-job handling. |
| [ ] | PR005 | Test operational database backup and restore | PR003, PR001 | Restore essential metadata in an isolated environment; exclude transient photo payloads or apply documented retention and key destruction to any included backup. |
| [ ] | PR006 | Audit content minimization throughout the service | PR004, PL109 | Inspect API, proxy, worker, APM and crash logs for images/OCR/queries/secrets; retain only necessary metadata. |
| [ ] | PR007 | Add privacy-preserving service observability | PR006, PR002 | Actionable latency/error/queue/cleanup metrics use no photo content or raw private search terms. |
| [ ] | PR008 | Verify queue fairness and abuse limits | PR003, PL105 | Per-account concurrency, total capacity, backpressure and oversized/malicious jobs cannot starve other users. |
| [ ] | PR009 | Implement an idempotent usage ledger | PR008 | Retries and cancellations charge quotas correctly; server-authoritative usage has no duplicated model billing. |
| [ ] | PR010 | Measure cost per supported workflow | PR009, PL138 | Use measured worker time, storage, network and retries; record uncertainty and operational overhead. |
| [ ] | PR011 | Define free and paid service limits | PR010 | Keep local search functional without a paid cloud account; explain limits and never upload merely to sell an upgrade. |
| [ ] | PR012 | Complete production account lifecycle | PL093, PR001 | Create/sign in/recover/sign out using supported authentication; avoid account enumeration and keep local data independent. |
| [ ] | PR013 | Test session/device revocation | PR012, PR004 | Stolen/expired/revoked tokens fail; lost device/account recovery cannot grant content keys to another user. |
| [ ] | PR014 | Implement account and associated-data deletion | PR012, PR001, PL104 | Provide app and web request paths where required; remove account/jobs/data on a documented schedule, preserve only justified records and prevent late-job restoration. |
| [ ] | PR015 | Complete local data reset and backup behaviour | PL132, PR014 | Separate revoke-source, delete index, cloud account deletion and uninstall; test Android backup exclusions and restore without promising lost-key recovery. |
| [ ] | PR016 | Run service recovery drills | PR005, PR007, PR013, PR014 | Exercise database loss, worker failure, expired keys and cleanup outages; restore service without resurrecting deleted content. |
| [ ] | PR017 | Review current Play payment requirements | PR011 | Document applicable countries/product types and supported billing path from current official policy before enabling purchases. |
| [ ] | PR018 | Implement verified purchases in a test environment | PR017, PR012 | Use supported Play Billing integration and server verification; client flags cannot mint entitlements. |
| [ ] | PR019 | Implement entitlement state transitions | PR018, PR009 | Pending, active, cancelled, grace, expired and refunded purchases map to correct cloud access and charging. |
| [ ] | PR020 | Implement purchase restore and account linking | PR019, PR013 | Restore only verified ownership; prevent cross-account reuse and preserve account-free local operation. |
| [ ] | PR021 | Handle billing notifications and reconciliation | PR019 | Authenticate notification inputs, handle duplicates/out-of-order events and periodically reconcile provider state. |
| [ ] | PR022 | Test billing abuse and failures | PR020, PR021 | Forged/replayed tokens, refunds, lost callbacks and offline purchase states cannot bypass quotas or double charge. |
| [ ] | PR023 | Audit shipped SDKs and disclosures | PL135, PR006 | Inspect exact analytics/crash/auth/ML/billing dependencies and network flows; prepare accurate data collection inventory. |
| [ ] | PR024 | Qualify gallery permissions for Play | PL037, PR023 | Document core photo-search need under current policy; support selected-only/denied access and avoid all-files permission as a workaround. |
| [ ] | PR025 | Prepare privacy policy and Data safety declarations | PR023, PR014, PR024 | Statements match actual local/cloud modes, worker visibility, retention, processors and account deletion. |
| [ ] | PR026 | Review generative-feature policy obligations | PL128, PR025 | For shipped generative features check applicable content, user feedback and disclosure requirements; document exclusions with reasons if evaluation rejects deployment. |
| [ ] | PR027 | Freeze supported Android release configuration | PL134, PR024 | Recheck target API, native page-size support, ABIs, minimum OS and compatibility on the current Play requirements. |
| [ ] | PR028 | Protect signing and build the release bundle | PR027, PL133 | Separate upload/signing credentials, configure Play App Signing as appropriate and verify reproducible provenance and recovery procedures. |
| [ ] | PR029 | Secure model distribution and upgrades | PR028, PL067, PL135 | Deliver licensed checksummed artifacts through authenticated manifests; handle interrupted updates, rollback and model/index version mismatch. |
| [ ] | PR030 | Run the production device matrix | PR029, PR015 | Test permissions, lock, thermal load, low storage, process death, offline mode and all three modes on supported real devices. |
| [ ] | PR031 | Run accessibility and realistic user acceptance | PR030, PL137 | Users complete retrieval and informed mode choices with TalkBack/large text; fix blocking findings without coached demo steps. |
| [ ] | PR032 | Prepare accurate store listing and notices | PR025, PR026, PR031 | Screenshots and claims match shipped behaviour; licenses/support/deletion links work; no server-blind or perfect-redaction claim. |
| [ ] | PR033 | Complete applicable Play testing tracks | PR028, PR032 | Use current account-specific internal/closed-testing requirements and record feedback; do not assume eligibility or guaranteed approval. |
| [ ] | PR034 | Run load and failure acceptance | PR008, PR016, PR030 | Exercise representative concurrency, expiry, queue/database outages and cleanup backlog against service objectives. |
| [ ] | PR035 | Review production security composition | PR022, PR029, PR034 | Review auth, envelopes, mode substitution, parser bounds, key rotation, deletion and cross-tenant races with tracked fixes. |
| [ ] | PR036 | Prepare incident and support procedures | PR007, PR035 | Define escalation, disabling cloud processing, compromised-key response, content-free diagnostics and user communications. |
| [ ] | PR037 | Approve operational cost and launch limits | PR010, PR011, PR022, PR034 | Set actual budget caps and account limits from evidence; stop accepting work safely when capacity/budget is exhausted. |
| [ ] | PR038 | Assemble the concrete release candidate | PL144, PR033, PR035, PR036, PR037 | Collect exact builds, migrations, policy text, tests, costs, known limits and rollback plan for release review. |
| [ ] | PR039 | Execute an authorized staged rollout | PR038 | Publish only the approved candidate when release is authorized; monitor health and crash/ANR rates and halt rollout on regressions. |
| [ ] | PR040 | Record production-readiness gate G6 | PR039 | Record rollout evidence, support ownership, billing/retention checks and remaining limits; planning alone never counts as production completion. |

## Optional extensions

These are not requirements for G1 or G3 and must not delay the first working system.

| Status | ID | Task | Direct prerequisites | Entry criterion |
| --- | --- | --- | --- | --- |
| [ ] | PX01 | Scope a Windows or iOS companion | PL144 | After Android, justify another client from need, then specify its native media/key/scheduling adapters and model compatibility before implementation. |
| [ ] | PX02 | Scope encrypted annotation export and recovery | PL144 | Specify which user-created data cannot be rebuilt and independent recovery semantics before implementing backup. |
| [ ] | PX03 | Scope selective photo backup | PL144 | Define explicit selected originals, independent-copy status, recovery keys and clean-install byte verification; no destructive mirroring. |
| [ ] | PX04 | Scope dedicated local/internet sharing | PL144 | Validate the requested sharing workflow; define authenticated recipient keys, separate package keys and post-download revocation limits. |
| [ ] | PX05 | Assess attested confidential computing | PL144 | Identify actual CPU/GPU platform, attestation, key release and operator threat model; do not relabel the ordinary worker as confidential. |
| [ ] | PX06 | Run a separately scoped FHE/MPC feasibility study | PL144 | Choose a small supported computation and measure quality, latency and security assumptions; no full-VLM promise. |
| [ ] | PX07 | Qualify additional image/video formats | PL144 | Define decoding, all-frame metadata/redaction and lifecycle tests before admitting another format to the cloud path. |
| [ ] | PX08 | Qualify more languages and sensitive categories | PL144 | Collect permitted coverage, evaluate OCR/redaction misses and release only the measured support. |


## Evidence naming and handoff

Keep private/raw evaluation data outside Git. A task report should identify:
- Task ID and current status.
- Changed modules/files.
- Dataset/fixture/model/format versions and hashes where relevant.
- Commands or user steps used for verification.
- Expected and actual results, including failures.
- Resource/device conditions.
- Remaining direct or external dependencies.

Do not mark a failed check complete merely because a workaround made a screenshot possible. A deliberately unsupported capability must be excluded consistently from the app, documentation and presentation.

## Planning verification

The planning-time dependency audit checked that all 192 IDs are unique, all referenced prerequisites exist and the graph has no cycle. This is a documentation check, not an application test result.

No PL/PR/PX implementation task was executed by creating this checklist.

## Dependency and handoff rules

G1 must not depend on PL124-PL128. G3 delivers all three baseline modes. G4 compares generative alternatives. Record unsupported or conditional outcomes with evidence.

Every handoff records task ID, direct dependencies, changed files, exact device, build and model versions, checks and results, remaining blockers and next runnable tasks.

