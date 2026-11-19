# Android product roadmap

Version 3. Planned on 14 November 2026. Status: documentation only.

The intended product is a working private photo-search app, with optional user-approved cloud processing and a later public/paid release. This roadmap distinguishes useful working versions from production readiness. Tasks and direct prerequisites are in the [complete checklist](implementation-todo.md); architecture is in the [blueprint](blueprint.md).

## 1. What the user can do

Install the Android app, grant access to chosen media, let local indexing progress, and find photos with ordinary descriptions or visible text. Originals stay in their existing gallery. The app stores encrypted derived information rather than making another mandatory cloud copy of every photo.

For selected tasks, choose:
- LOCAL: keep all image/search processing on the phone.
- CLOUD_FULL: approve an unmasked rendered image for stronger processing.
- CLOUD_REDACTED: locally mask/crop, inspect and approve a redacted image.

No account is needed for local search. Cloud authentication uses a normal app sign-in flow, not a manually pasted access token. A paid cloud entitlement, if introduced, does not make local searching upload anything.

Ordinary cloud inference sees the approved image. Redaction reduces disclosure; it does not establish anonymity or make a breached server harmless. No full-image retry follows failed redaction without a new explicit user choice and approval.

## 2. Staged delivery

| Stage | Tasks/gate | User-visible outcome | Must prove before moving on |
| --- | --- | --- | --- |
| Android feasibility | PL001-PL019 / G0 | No production app claimed yet | Real phone can run baseline encoder/OCR and encrypted storage; permissions, keys, formats and licenses are qualified |
| First local working release | Through PL072 / G1 | Search a real permitted gallery offline with encrypted derived data | Correct results/coverage, lock, interruption, edits, trash, delete and permission changes |
| Disclosure preparation | Through PL090 / G2 | Inspect full or redacted export and keep local/cancel | Exact byte preview, metadata removal, no downgrade, explicit mode-bound consent |
| Working hybrid version | Through PL112 / G3 | All three modes complete real tasks | Authenticated encrypted jobs/results, bounded service, cleanup, quota and stale-result checks |
| Required later AI comparison | Through PL128 / G4 | Measured decision on useful generative enrichment | Compare non-generative and LLM/VLM approaches across all modes; report Android feasibility, quality, privacy and cost |
| Android release candidate | Through PL144 / G5 | Installable documented app another person can use | Clean-install acceptance, security/lifecycle review, measured performance and truthful limitations |
| Production and paid readiness | PR001-PR040 / G6 | Operable public product with verified purchases where enabled | Release policies, account deletion, service operations, costs, billing and staged rollout evidence |

The checklist is a dependency graph, not a demand to complete every lower ID before starting an unrelated task. G1 is independent of generative evaluation and the production service. The first app must already be useful; later stages add capabilities, not replace a fake demo.

## 3. Minimal working app acceptance

A fresh install with permitted fixtures must:
1. Explain permission scope and work with selected-only access.
2. Acquire verified model assets and disclose any SDK/network requirements.
3. Index incrementally without uploading the library.
4. Find supported objects/scenes/text with real evidence and report absent or unindexed content honestly.
5. Open the currently accessible original and show its actual availability.
6. Remain responsive and resume after cancellation, restart or OS interruption.
7. Keep derived data encrypted on disk and pause key-dependent work when locked.
8. Handle edits, moves, trash, restoration, deletion, revoked access and source disconnection without stale results.
9. Rebuild the index from accessible originals after loss while explaining which annotations cannot be recovered.
10. Provide understandable failures and progress without requiring developer commands.

G1 does not include cloud billing, backup, generative chat or a second platform. It is an Android application, not a browser-only interface or a Python service the phone must connect to.

## 4. Making the hybrid version useful

Do not claim extra cloud compute automatically creates useful information. G3 measures whether a stronger OCR/vision pipeline helps selected tasks. G4 tests generated descriptions and query assistance against the baseline.

The request interface carries only the approved artifact and bounded task inputs. In redacted mode, private local OCR, filenames, location data and earlier model text cannot bypass the pixel mask. A response carries provenance and may enrich the local index only while the content revision, consent, mode and access are still valid.

Retain the local result when a cloud request fails, becomes stale, exceeds budget or adds no supported fact. Show pending, cancelled, failed and applied states distinctly. A larger model is never the authoritative answer merely because it runs on a server.

## 5. Production architecture and operations

Start with one API service, relational job metadata, encrypted payload/result storage, bounded worker capacity and a cleanup scheduler. Scale measured bottlenecks; do not prebuild a microservice fleet.

PR001-PR016 cover:
- Data inventory, retention and a documented deletion deadline.
- Reproducible environments, TLS, service identity and separate secrets/key roles.
- Job authorization, bounded queues, backpressure, quotas and cost accounting.
- Content-free operational logs, cleanup monitoring and usable alerts.
- Account recovery, session revocation and deletion.
- Operational backups and restore drills with explicit payload exclusions/expiry.
- Failure recovery without resurrecting deleted content or losing local usability.

Application-layer encryption protects asynchronous payload storage. A worker still needs plaintext to infer. Restrict operator access and content persistence accordingly. A confidential-computing design would be a separately evaluated extension.

## 6. Commercial service scope

Paid service is a planned later phase, not an assumption that users will pay. Measure per-workflow cost before selecting prices or promising unlimited processing. Subscription versus credits remains a product decision informed by usage and applicable store rules.

PR017-PR022 require current billing-policy review, verified purchases, a server-authoritative usage/entitlement ledger, restore/account linking, callback reconciliation, refunds/revocations and abuse tests. A free closed beta can precede paid launch; it must not be labelled completed paid-service readiness.

Local mode remains available independently of cloud account or subscription state. Do not pressure a user to send sensitive photos by making an ordinary local search appear broken.

## 7. Android and Play release work

PR023-PR033 cover:
- Audit of the exact shipped SDKs and their telemetry.
- Gallery permission rationale with a selected-only fallback; no all-files permission shortcut.
- Accurate privacy policy, Data safety information and deletion paths.
- Applicable generative-feature policy review for features actually shipped.
- Current target API, native library compatibility, ABIs and device support.
- Release signing, AAB preparation and safe credential recovery.
- Licensed model delivery, integrity verification and interrupted-update handling.
- Physical-device, accessibility, beta and applicable Play testing requirements.
- Store screenshots, notices and support links matching the actual app.

The [research register](research-and-decisions.md) links official policy sources. Recheck requirements when releasing; this plan is not a guarantee of acceptance.

PR034-PR040 then require load/failure checks, security review, incident response, cost limits, a concrete release candidate and an authorized staged rollout. This planning task does not deploy, create paid infrastructure or submit an app.

## 8. Keep the design maintainable

Use real boundaries: source access, encrypted catalog, inference, search, consent, key storage, remote transport and result integration. Keep policies and state transitions testable without a UI. UI state holders coordinate use cases rather than implementing encryption or model inference.

Version external schemas and artifacts. Prefer constructor injection, small adapters and explicit state machines. Avoid global mutable services, redundant wrappers and deeply layered abstractions without actual need. Diagnostics should identify a failed stage while excluding photo content and keys.

Every implementation handoff includes the task IDs, changed files, dependencies, actual commands/devices/model versions, results and unresolved limits. Documentation must describe what exists, not what the roadmap hopes to deliver.

## 9. Deferred extensions

PX01-PX08 scope other clients, annotation recovery, backup, sharing, attested compute, limited encrypted-computation research, formats and languages. They are entry tasks, not complete implementation plans. Expand a chosen extension before coding it.

Generative LLM/VLM evaluation is not in this deferred optional list: it is committed in G4. Fine-tuning and production deployment of a particular model still need evidence.

## 10. Remaining decisions and external dependencies

Before coding, qualify actual phones, support versions, pretrained artifacts, mobile runtimes and database/native dependencies. Obtain rights for realistic evaluation data and an independent assessor. Keep the class-project overlap question provisional where descriptions remain missing.

Before cloud acceptance, obtain an actual worker environment and test accounts. Before public release, obtain developer-account eligibility, signing custody, accurate support/deletion URLs, operational ownership and a reviewed release budget.

Do not invent task duration, scale capacity, accuracy or revenue. Use measured evidence to revise the plan while preserving the required local, three-mode, cryptographic and security behaviour.
