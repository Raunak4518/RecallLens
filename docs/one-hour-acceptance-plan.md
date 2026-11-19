# One-hour execution and acceptance plan

Prepared 18 November 2026 from the current checkout, implementation checklist, execution status, source and existing artifacts. This is a prioritization plan for the user's accelerated work request. It does not redefine the full project as complete after one hour.

The one-hour window started at 2026-11-17 18:52:11 UTC (18 November 00:22:11 IST), with target 19:52:11 UTC (01:22:11 IST). This is an execution deadline, not an assertion that physical qualification or production launch can be completed without their external requirements.

Outcome recorded at 01:15 IST, within the window: the selected query, identity, garment-provider and search-guidance changes are implemented; 19 host tests passed; build/lint and ten ARM64 package checks passed. Per-test split acceptance accounts for 76 distinct executed Android passes and three explicit skips, with no unresolved case after recovering from a dead automation connection. See app/build/accelerated-split-acceptance.json and accelerated-implementation.md for the exact failure/recovery history. This completes the selected acceleration batch, not all 184 core/product tasks or production launch.

The pre-integration baseline completed with 19 host tests, build/lint and 63 executed Android passes plus three opt-in skips (66 reported). Source/model hashes were unchanged. Its frozen receipt and transcripts are retained under app/build/pre-acceleration-verification. The original running handle was session48993 and completed normally; it must not be resumed or restarted as an active run.

The objective remains the Android-first private photo-library system: account-free local search, encrypted derived state, model-driven detection with explicit naming, confirmed-only Named matches plus separately reviewed suggestions, optional full/redacted cloud processing with exact-artifact consent, maintainable model boundaries and tested lifecycle behavior. Required later VLM comparison and production release gates remain in the original checklist.

## Work allocation

| Owner | Work during frozen baseline verification | Work after source freeze ends |
| --- | --- | --- |
| Root | Finish the current core verification; monitor the existing run rather than restart; preserve source/model hashes | Integrate fixes, run the emulator tests serially, build final artifacts and record actual results |
| Query agent | Inspect LibraryViewModel query scheduling, evidence grouping and stale-result behavior; prepare concrete changes | Own query execution extraction and focused cancellation/evidence tests after root's go signal |
| Security agent | Inspect actual revision, confirmation, encryption and cloud boundaries for consequential defects | Own isolated fixes/tests for confirmed defects; avoid overlapping query files without coordination |
| Release agent | Map requirements to current evidence, inspect app/API usability and identify missing release inputs | Keep the acceptance/handoff accurate; prepare reproducibility and usability changes only when assigned |

There is one authorized emulator, emulator-5580. Concurrent instrumentation would contaminate application state and evidence, so root schedules all device runs. Core verification hashes app/src, worker, tools and staged models; no agent edits those during the frozen run. Documentation can proceed independently.

## Ordered hour

| Window | Deliverable | Dependencies | Acceptance |
| --- | --- | --- | --- |
| First 10 minutes | Finish the in-flight integrated baseline; gather agent findings | Existing live verifier process | Host result, build/lint result, Android transcript, executed/skipped counts and source/model receipt; failures remain visible |
| Minutes 10–20 | Select consequential defects and assign nonoverlapping file ownership | Baseline ends; source inspection findings | Every selected change has a failing scenario and a precise success condition; no broad rewrite solely to reduce line count |
| Minutes 20–40 | Implement query orchestration and confirmed security corrections in parallel | Shared contracts fixed; baseline known | Query/source/name revisions guard late results; evidence groups and Named confirmation boundaries preserved; new source comments absent |
| Minutes 40–50 | Run focused tests and resolve failures; exercise actual search/name/detail flows | Final selected changes compiled | Real emulator interactions, absent-item cases, cancellation/source change cases, and no model execution on repeated cached search unless invalidated |
| Final 10 minutes | Build/lint, production package inspection, concrete handoff | Integrated code and focused checks pass | Artifact paths and hashes, source/model receipt where applicable, exact passed/skipped tests, known limitations and remaining prerequisites |

If integrated verification takes longer, reduce additional feature breadth rather than omit validation or assert unmeasured completion. The hour is not enough to manufacture physical hardware, independent labels, signing credentials or production service resources.

## Requirements and evidence audit

| Requirement / task family | Authoritative current implementation/evidence | Remaining work |
| --- | --- | --- |
| Android/Kotlin baseline, PL014/PL020 | app/build.gradle.kts uses compile SDK 37, target 36, minimum 30, version 0.2.0; Compose application exists | Supported-device qualification beyond the x86_64 emulator |
| Durable indexing, PL041/PL066 | WorkManager coordinator; durable checkpoint tests; separate process-restart and reboot evidence documented in execution-status.md | Physical key-lock/battery/storage/OS scheduling matrix |
| Cached local OCR and scenes, PL060–PL064 | LocalPhotoIndexer, SQLCipher records, CLIP query encoding, ML Kit default; encrypted reopen and metadata reuse tests | More realistic physical cold/warm latency and index footprint; quality thresholds/absent cases |
| Models decoupled from workflows, PL058/PL061/PL062 | PhotoProcessorFactory, PhotoIndexSession, IdentityModelFactory/IdentityEncoder | Query orchestration remains concentrated in LibraryViewModel; future dimension-changing models require codec compatibility |
| Named people/objects and lookalikes, PL064/PL071 | Model detections, explicit confirmed references, identity cache, suggestion review, separate Named results | Broader independent exact-instance/face cohort, difficult groups/occlusions and calibrated confidence |
| Natural compound queries, PL064/PL065 | Confirmed intersections, self aliases, nearby queries, bounded person/color slice and missing-evidence requirements | Exact clothing subtype, body/person association under difficult groups, arbitrary spatial relations and temporal/history language |
| Lifecycle, PL044/PL046/PL051/PL052/PL070 | Same-URI OCR/scene/identity/garment validation; full-file Named proofs; provider-unavailable retention; GPS EXIF invalidation | New-URI moves/copies, cross-volume identity, full provider reset/reused-ID and permission device matrix |
| Local encryption/privacy, PL025–PL036/PL068 | SQLCipher, Keystore wrapping, copied-state tests, privacy/network audit, package backup/export checks | Complete operational/key-recovery policy and physical-device threat-model qualification |
| OCR model comparison, PL071 | Frozen 300-photo comparison, 360 remembered queries; Tiny gains/losses reviewed; ML Kit remains default | Independent fresh labels and ARM qualification before replacement; multilingual and handwriting coverage |
| Person/color evidence, PL064/PL071 | 24-photo/56-person garment cohort and real clothing screen acceptance; estimated colors separated | Independent labels, broader clothing/ownership cases and generalization; candidate overlap policy remains experimental |
| Three modes and cloud crypto, PL073–PL112 | LOCAL, CLOUD_FULL, CLOUD_REDACTED review; exact rendered artifact consent; encrypted Android/dev-service exchange; authenticated owner-isolated service | Broader redaction privacy corpus and manual correction qualification; public TLS, operations and deployment acceptance |
| Required later LLM/VLM comparison, PL124–PL128 | Planned extension point and evaluation requirements | Actual comparable permitted-cohort runs, cost/privacy evaluation and evidence-based decision; fine-tuning remains conditional |
| App usability, PL065/PL129–PL133 | Gallery permission, folder management, progress/pause, naming section, three search modes, estimated evidence groups | Full user-flow acceptance, accessibility, understandable empty/error states, discoverable self setup and mode guidance |
| Release, PL134–PL144 | ARM64 APK/AAB and ten static checks in release-inspection/results.json | Production signing, physical memory/battery/thermal tests, independent tester/reviewer and final release candidate acceptance |
| Product/Play/paid service, PR001–PR040 | Development service and configuration exist | Production identity/operations/deletion/support/distribution/billing requirements from the original checklist |

The current app folder has no .git directory or .github workflow. This was verified from the directory listing. The old CueSeal publication is not publication of this app. Establishing a clean, licensed source handoff and dependency/model staging verification is a separate release prerequisite. Do not publish secrets, local.properties, downloaded corpora, model caches, user photos or build logs.

## Most useful changes after baseline

This section records the priorities selected at the start of the hour. Query execution, conditional cache invalidation, source/access validation, token-owned garment commits and per-mode guidance have since been integrated. See accelerated-implementation.md for actual source responsibilities and test outcomes; the priorities below are not a claim that those changes remain unimplemented.

1. Extract query execution and stale-result validation from LibraryViewModel while preserving its current contracts. The ViewModel owns seven job handles and several independently scheduled search paths. This is a concrete responsibility boundary already identified in next-implementation-handoff.md.
2. Correct only security/lifecycle defects supported by source and a reproducible test. Cache features already have substantial narrow coverage; another optimization is lower priority than a broken access or result-publication boundary.
3. Improve mode guidance with examples for remembered text, scene description and confirmed names. LibraryScreen currently renders three chips; only Content has an example placeholder. A unified search input is desirable, but changing routing without the query contracts and regression tests risks silently mixing suggestions with confirmed answers.
4. Finish a truthful operational handoff: installation, model staging, supported commands, exact acceptance artifacts and unresolved requirements. README already has usable commands; update inaccurate statements rather than create another competing runbook.

## Inputs that cannot be replaced with emulator success

- Physical ARM measurements for sustained indexing, peak memory, battery, thermals and scheduling are explicitly required by PL069/PL134 and AGENTS.md. Current automation is authorized for emulator-5580 only.
- Production signing requires the intended release-key owner and secret handling. The inspected APK is app-release-unsigned.apk; static checks do not sign it.
- Public TLS deployment requires a chosen service host, certificate/key and operational configuration. The local authenticated service is development integration evidence.
- Independent relevance/privacy labels and external review are required for quality and release claims. Existing single-reviewer annotations remain provisional.
- Required VLM evaluation has not been completed merely because the encoder/OCR baseline works. It remains part of the full objective.

The release agent added documentation and the small search-guidance UI/test changes under root's coordination. Root owns all builds and emulator runs. The frozen pre-integration baseline passed 63 executed Android checks plus three skips. The attempted 79-case final run lost its host process and UI-automation connection; 13 cases failed with DeadObjectException and no final green receipt was issued. A targeted 13-case recovery is running under root supervision. Build/lint and final ARM64 artifacts passed; their package inspection reports ten passing static checks. Read accelerated-implementation.md and the current transcripts before asserting final integrated acceptance.

The current inspected artifacts are app/build/outputs/apk/release/app-release-unsigned.apk (314,978,399 bytes) and app/build/outputs/bundle/release/app-release.aab (217,285,991 bytes), with hashes in app/build/release-inspection/results.json. These are package-generation and static-inspection results, not proof of production signing, physical performance or successful public deployment.
