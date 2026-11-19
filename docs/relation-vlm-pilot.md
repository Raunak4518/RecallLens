# Local relationship reranking pilot

PL124/PL125 experiment, 18 November 2026. This follows the accepted self/nearby implementation; it is not an app model replacement or a completed production gate. Only emulator-5580 is authorized. No cloud hosting or phone access is required.

## Frozen input

The final pre-inference manifest is .benchmark-data/relations-v2/manifest.json, SHA-256 9082dc2aa880186850253141ae226308516c37972b9652234c18b858d8c9909e. It reuses the same 2,000 Open Images originals and their recorded hashes, attribution and publisher license metadata. Hardlinks add no duplicate image bytes. The 24 query texts were frozen before their CLIP rankings or any VLM answers: 20 scene/relationship requests and four difficult combination probes. The latter are not known absent from the entire gallery.

The initial unexecuted proposal is retained as initial-proposal-not-inferred.json. A pre-inference visual annotation audit found a banjo labeled guitar, uncertain beverage contents and redundant age/gender distinctions. The final proposal increases scene diversity. Official positive relationships remain provisional guides; annotation absence never becomes a negative. Candidate judgments must be completed before VLM outputs are inspected. Neither an AI review nor two agreeing AI agents constitute independently verified human truth.

## Equal inputs and judgments

The installed Android CLIP test ranks the entire 2,000-photo gallery for every query. Its baseline output is app/build/relation-vlm-pilot/baseline. The optional --output argument to tools/large-gallery/run_large_gallery.py keeps the original benchmark intact. Baseline inference must not overlap native compilation or VLM inference, to avoid deliberate CPU contention.

Prepare the fixed review pools only after the baseline completes:

```powershell
python tools/vlm_eval/prepare_review.py --corpus .benchmark-data/relations-v2 --baseline app/build/relation-vlm-pilot/baseline/emulator.json --output app/build/relation-vlm-pilot/review
```

This verifies the exact manifest/result pair and descending complete rankings, checks each candidate's source bytes, and creates eight-photo sheets plus pending judgments. Candidate presentation is a deterministic hash-based shuffle using corpus hash, query ID and image ID, independently of baseline rank and score. Neither the judgment records nor the sheets disclose baseline scores/ranks; the unchanged baseline file retains those. Metrics must reconstruct baseline ordering by image ID, not use review order. Unique safe query IDs and atomic no-replace publication protect review files. Every candidate receives relevant, irrelevant or unjudgeable plus a visual reason. Record limitations rather than inferring hidden drink contents, identity, age or location. An absence probe is an absent-candidate case only when every candidate has been reviewed as irrelevant; otherwise it remains a combination probe.

### Visual relevance rules fixed before candidate review

- Judge the full visible query, rather than accepting a provisional official-positive ID automatically. A contradictory visible predicate makes the candidate irrelevant; a required predicate obscured or not visually establishable makes it unjudgeable. An ordinary query with no relevant candidate still remains in the evaluation.
- “A person sitting at a table” requires visible seating posture associated with a table. A person merely near, behind or standing at a table is irrelevant. If the lower body and seat are hidden so posture cannot be established, use unjudgeable.
- “Chairs around a table” requires at least two visible chairs distributed at different sides or around the perimeter of the same table. One chair at a table, or multiple chairs in an unrelated row, is irrelevant. Occlusion preventing this association is unjudgeable.
- “Grapes hanging from a vine” requires grapes visibly attached to a vine/stem with surrounding vine structure. Detached grapes in a bowl or on a table are irrelevant. A close crop hiding attachment is unjudgeable. The official “hang Tree” relation is a candidate guide, not proof of a vine.
- “A glass containing wine” cannot be established from a glass shape, drink color or drinking context alone. If wine identity is not directly established by unambiguous visible information, use unjudgeable. A clearly empty glass or clearly contradictory content is irrelevant. Such ambiguity is a property of the requested visual task and must not be silently resolved using publisher labels.
- Glasses, sunglasses and goggles may overlap in appearance. Judge visible eyewear for the requested category; do not infer a category from the person's activity alone. Mark genuinely ambiguous or too-small eyewear unjudgeable.
- Combination probes require every requested visible component and its relationship in the same image. Mere co-occurrence or satisfying only some clauses is irrelevant; hidden/indeterminate required facts are unjudgeable. Do not infer identities, age, ownership, exact location or gender beyond what the task's visible evidence can support.

These are fixed pilot criteria rather than a new claim of independent human ground truth. Any later criterion correction must be versioned as a new evaluation, retaining the original judgments; it cannot be chosen after seeing VLM mistakes. AI-only judgments remain provisional even when several agents agree. The evaluator preparing visual judgments must not inspect VLM predictions before freezing the completed judgment file and its hash.

The reranker receives exactly the same eight candidates for each query, at most 192 decisions. Strict whole-output YES, NO or UNCERTAIN parsing prevents a verbose or malformed answer from becoming positive support. Failed and malformed responses abstain. Stable tiers put YES first, uncertainty next and NO last, preserving baseline order within a tier. Report both this ranking and the separate set of explicit YES suggestions. Reranking cannot recover relevant photos outside the first-stage pool.

## Decision criteria before VLM answers

Treat this as a provisional pilot rather than final model selection. A promising result requires at least three additional top-one relevant hits across the 20 ordinary requests, no reduction in top-five relevant-query hits, and no new positive support on fully irrelevant reviewed combination pools. Unknown labels must remain separate from errors. A failure or uncertain pool cannot be omitted from denominators to improve the score.

The top-one and top-five relevant-query hit denominators remain all 20 ordinary requests, including failures and pools with no known relevant item. Unjudgeable top results do not become hits and are reported separately. Judged precision uses only relevant/irrelevant judgments and must be labeled conditional rather than overall precision. Count and list the fully irrelevant reviewed combination pools. If none qualify, their safety criterion is unproven and cannot be recorded as passed. A pool containing any unjudgeable item does not qualify as fully irrelevant.

CLIP supplies an ordered candidate pool without a frozen positive-support/abstention threshold. Compare its ranking with the VLM ranking on equal pools. Separately report the VLM's explicit YES support on irrelevant and unjudgeable images; this is an absolute support-error measure, not evidence of a false-alert reduction against unthresholded CLIP. A thresholded comparison needs its own predeclared validation policy. Report overlapping query families and reused images, particularly glasses/sunglasses/goggles, rather than interpreting 24 correlated queries as independent trials or a population-level confidence bound.

For interactive deployment, use a provisional maximum of five seconds additional resident-model wall time for all eight candidates on the emulator. A fresh CLI process per image measures cold-process cost and cannot establish that resident budget. No warm claim is allowed until an actual resident session executes. Record initialization, preprocessing, vision and generation where the runtime exposes them, plus peak sampled RSS/VmHWM, failures and cold wall time. Emulator evidence cannot qualify ARM battery, thermals or low-end phones.

The existing model/projector pair is about 520 MiB; it remains external evaluation data and is not automatically added to the roughly 300 MiB app package. There is no caption index or generated confirmation in this pass. Any later app adapter needs cancellation, resource ownership, bounded input/output, encrypted/versioned records if persisted, and the existing confirmed identity/GPS/source guards.

The native runtime is cross-compiled from the same pinned llama.cpp source as the host smoke. Android's official NDK and the runtime's documented cross-compilation path provide the toolchain; successful installation or compilation is not inference evidence. Sources: [Android NDK downloads](https://developer.android.com/ndk/downloads), [pinned native Android build instructions](https://github.com/ggml-org/llama.cpp/blob/9048bad8d6e3a0f9f1726e238e451181c7e02613/docs/android.md).

The review-preparation helper's generated-fixture tests passed after the score-blinding change: six preparation checks plus four strict-decision checks, ten total. They cover deterministic ordering independent of baseline rank, absent score/rank fields and rendered labels, per-ID provenance, source/manifest tamper rejection, safe IDs and concurrent no-overwrite behavior. This is helper validation only; no final-baseline candidates or VLM answers were inspected for that change.
