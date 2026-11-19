# Photo-library planning archive

## Active Android scope

The current Android app is phone-local and offline. Its canonical design and task order are in the [final Android search plan](final-android-search-plan.md), with current implementation evidence in the [Android build handoff](android-search-build-handoff.md). The app has no cloud upload, service dependency or remote inference fallback.

This document and the hybrid blueprint below are historical planning materials for a separate cloud system. They do not define the Android app's active scope. Keep the phone and cloud systems separate. CueSeal remains a different project at `D:/secure audio`; its source and test results are not evidence for the photo app.

## Historical hybrid plan snapshot

Version 3 planning snapshot, dated 14 November 2026. The [project README](../README.md) tracks implementation evidence. The [hybrid implementation record](hybrid-implementation.md) tracks the separate service integration work.

The archived hybrid proposal covered local search plus explicitly approved full-image and locally redacted cloud processing. Consult it only for the separate service system, not for Android app requirements.

Start from the [project README](../README.md) for workspace status and [development setup](development-setup.md) for framework and build instructions.

## Read in this order

For current execution, also read the [implementation handoff](next-implementation-handoff.md), [current architecture review](current-architecture-review.md) and [real gallery scenario matrix](real-gallery-scenarios.md). These supplement the original dependency checklist with measured results and remaining gaps.

1. [Blueprint](blueprint.md): objective, Android architecture, three modes, cryptography, security and lifecycle behaviour.
2. [Complete dependency checklist](implementation-todo.md): 184 planned core/product tasks and 8 optional extension-entry tasks, with direct prerequisites and completion evidence.
3. [Model and evaluation plan](model-and-evaluation-plan.md): baseline models, required later LLM/VLM comparison, data budgets and measured selection criteria.
4. [GPU model-quality evaluation results](model-quality-evaluation-results.md): full model comparison, CLIP text-graph precision sweep and phone-runtime boundaries.
5. [Product roadmap](product-roadmap.md): staged working versions through production, Play distribution and paid-service readiness.
6. [Research and decisions](research-and-decisions.md): primary sources, existing systems, corrections, constraints and unresolved evidence.

## Fixed decisions

- Android first; native Kotlin/Compose is the recommended implementation.
- Existing photos stay in the gallery. Local indexing does not require uploading them or creating an account.
- Derived metadata, OCR, embeddings and caches require encrypted storage with protected keys.
- Three explicit modes: LOCAL, CLOUD_FULL and CLOUD_REDACTED.
- Embeddings, OCR and metadata form the first working baseline.
- Later LLM/VLM evaluation against that baseline is required, not an optional extension. Whether a specific generative model ships or is fine-tuned depends on results.
- Novelty is not a requirement. Existing photo search is acknowledged; usefulness and correctness must be measured.
- Backup, file sharing and other clients are separately scoped extensions.

## What each mode discloses

| Mode | Processing input | Disclosure |
| --- | --- | --- |
| LOCAL | Accessible original pixels on the phone | No photo, OCR, embedding or query is sent for cloud processing |
| CLOUD_FULL | Approved unmasked rendered copy | Worker sees approved visible content; unnecessary container metadata is removed |
| CLOUD_REDACTED | Approved locally masked/cropped rendered copy | Worker sees remaining content; redaction can miss details and reduce task quality |

Encryption protects local stored data and cloud transit/queued storage. Ordinary cloud inference decrypts the approved input. It is not server-blind encrypted computation. Failed redaction never triggers an unredacted retry.

## Planned workflow

~~~mermaid
flowchart TD
    A["Permitted Android gallery"] --> B["Local encoder, OCR and metadata"]
    B --> C["Encrypted local index"]
    C --> D["Local search while unlocked"]
    D --> E{"User chooses processing for selected items"}
    E -->|LOCAL| C
    E -->|CLOUD_FULL| F["Render full image and strip metadata"]
    E -->|CLOUD_REDACTED| G["Local masks or crop; render and strip metadata"]
    F --> H["Review exact bytes, mode, task and destination"]
    G --> H
    H -->|Decline| C
    H -->|Approve| I["Authenticated encrypted job"]
    I --> J["Worker decrypts approved input and processes it"]
    J --> K["Authenticated encrypted result"]
    K --> L{"Revision, access and consent still valid?"}
    L -->|Yes| C
    L -->|No| M["Reject stale result and clean up"]
~~~

This is a design diagram, not a screenshot of implemented functionality.

## Delivery sequence

| Gate | Task | Outcome |
| --- | --- | --- |
| G0 | PL019 | Qualified Android baseline dependencies and support matrix |
| G1 | PL072 | First useful local encrypted photo-search app |
| G2 | PL090 | Full/redacted export preparation and approval |
| G3 | PL112 | Three working modes using the non-generative baseline |
| G4 | PL128 | Required non-generative versus LLM/VLM evaluation and routing evidence |
| G5 | PL144 | Independently tested Android release candidate |
| G6 | PR040 | Production, Play and paid-service readiness |

Use the checklist and the project README together. The delivery gates remain the completion criteria. Local features have implementation evidence, while physical-device qualification and full hybrid release still have outstanding dependencies.

## Planning verification and handoff

This revision:
- Replaces the Windows-first proposal with Android-first architecture and lifecycle requirements.
- Adds full-image cloud as an explicit mode alongside local and redacted cloud.
- Binds approval and cryptographic context to mode as well as the exact artifact, task, destination and current source revision.
- Defers generative work until after the baseline system, while retaining its required evaluation.
- Adds production operations, account deletion, billing and Play release tasks.
- Keeps prior CueSeal source and documentation intact.

The documentation audit checks unique IDs, known dependencies, absence of cycles, coverage of core tasks by the final gate, independence of G1 from later generative tasks, local links and stale platform claims. It does not substitute for application tests.

This section records the original planning deliverable. Subsequent evidence is recorded in the project README and the linked implementation records.


Current execution additions: [partial Content search and coverage](partial-content-search.md), [real2,000-photo evaluation](large-gallery-evaluation.md), [independent partial-search review](partial-content-review.md), [model distribution audit](model-distribution-audit.md), [local signing](local-release-signing.md) and [publisher privacy draft](privacy-policy-draft.md). Consult execution-status.md for current receipts; historical planning checkboxes do not by themselves establish acceptance.
