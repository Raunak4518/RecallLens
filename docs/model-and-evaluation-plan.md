# Android models and evaluation plan

Version 3. Planning date: 14 November 2026. No photo-app model, training, download, conversion or benchmark has run as part of this planning update.

Read the [blueprint](blueprint.md), [dependency checklist](implementation-todo.md) and [source register](research-and-decisions.md).

## 1. Evaluation order

First build a useful Android baseline from image-text embeddings, local OCR and metadata. This supplies semantic photo search without captions or a cloud account.

Then complete the three-mode pipeline. After that, PL124-PL128 must compare the non-generative baseline with generative LLM/VLM approaches. The comparison is required scope; it is not an optional extension. Whether generation ships, runs on every photo or receives a fine-tune depends on measured benefit.

A text-only LLM can interpret a query or summarize permitted extracted text. A VLM can process an image and generate text. Neither is synonymous with an image-text embedding encoder, and none is ground truth.

## 2. Candidate register

| Stage | Candidates to qualify | When | Decision evidence |
| --- | --- | --- | --- |
| Local retrieval | CLIP ViT-B/32 and a qualified SigLIP2 base variant | G0-G1 | Android conversion/runtime, licenses, relevance, memory and latency |
| Local OCR | ML Kit Android text recognition and a qualified native OCR alternative | G0-G1 | Script/layout coverage, text/region error, binary/model size, SDK network behaviour |
| Cloud baseline | Qualified stronger OCR/vision extraction with bounded output | G3 | Useful incremental information on the same tasks; do not assume a server automatically improves quality |
| Local generative enrichment | SmolVLM2 500M, then Qwen3-VL 2B only where resources and runtime permit | G4 required evaluation | Physical-phone feasibility, factual errors, search benefit, battery and memory |
| Cloud generative enrichment | Qwen3-VL 8B or another licensed qualified candidate | G4 required evaluation | Full and redacted input quality, hallucination, latency and cost |
| Text-only query assistance | A separately qualified small LLM | G4 evaluation design | Query reformulation improves frozen retrieval tasks without changing user intent or disclosing local OCR |
| Assistance routing | Explicit user choice and rules, compared with a small logistic/tree router when labels support it | G4 | Missed helpful escalations, unnecessary uploads and end-to-end utility |
| Redaction | Local OCR/identifier rules/entity models and a separate face-region detector | G2 | Category-specific misses, over-redaction and manual review effectiveness |

These are candidates, not selected production weights. Source links and licensing cautions are in the research register. Pin artifact revision, checksum, tokenizer, preprocessing, runtime, quantization and redistribution terms before use. Desktop PyTorch support is not evidence of Android deployment support.

MobileCLIP is not automatically a commercial choice: inspect exact model terms separately from software licensing. An SDK may process image inputs locally yet send operational metrics; audit actual behaviour before promising zero network traffic.

## 3. The required comparison matrix

Use the same tasks, owned/consented images, grouped splits and relevance judgments.

| Processing mode | Non-generative approach | Later generative approach |
| --- | --- | --- |
| LOCAL | Local encoder, OCR and metadata | Local baseline plus feasible on-device VLM enrichment; separately evaluate LLM query assistance |
| CLOUD_FULL | Approved unmasked rendered input to a stronger OCR/vision pipeline | Approved unmasked rendered input to stronger VLM |
| CLOUD_REDACTED | Approved locally redacted input to the baseline cloud pipeline | The same approved redacted input to stronger VLM |

Evaluate both final retrieval and extraction, not merely how fluent the descriptions sound. Where a model cannot run on a phone, report unsupported/failed feasibility and test a viable candidate; do not fabricate a local result from a remote run. Cloud-only generation can still be evaluated while the local baseline remains functional.

No fixed ordering is assumed. Local can beat cloud, redaction can remove required information, and generated descriptions can make search worse. A cloud model's ability to see more pixels is not automatically a privacy-acceptable improvement.

Include artifact preparation as an ablation: original local input versus full rendered copy versus automatically redacted copy versus reviewed redacted copy. This separates effects of model choice from image resizing, masking and user edits.

## 4. Task definitions

Primary task: find a known photo or a relevant group of photos. Supporting tasks: extract visible text and represent useful visible facts.

Difficulty is task-specific. An image may be easy to retrieve and hard to transcribe. Blur, low resolution and redaction can remove information that no larger model can recover. Low query similarity may mean the requested photo does not exist.

Prepare independently authored queries covering ordinary objects/scenes, text screenshots, relationships, dates/metadata, multilingual supported text and absent-item queries. Do not let the model author both test questions and their ground truth without human review.

## 5. Proposed data budgets

These are collection budgets for planning, not literature-derived minimums or accuracy guarantees.

| Work | Initial budget | Constraint |
| --- | --- | --- |
| Use pretrained models | Zero newly labelled training examples | Still requires local evaluation, licensing and device qualification |
| Retrieval/extraction evaluation | 500-1,000 permitted images and 150-300 independently authored tasks | Start with smaller functional smoke fixtures; separate these from final held-out evaluation |
| Redaction evaluation | 200-500 annotated examples with multiple sensitive regions | Include text/face size, scripts, rotations, blur, reflections and missed categories |
| Learned router if justified | Approximately 1,000-3,000 reviewed task-image pairs | Labels distinguish full and redacted outcomes; count source groups, not only augmented pairs |
| Narrow VLM adapter if justified | Pilot approximately 1,000-5,000 permitted task-response pairs | Require a repeated specific failure and compare against the pretrained model |
| Redactor tuning | Decide from missed categories | No universal count; review remains necessary |
| Foundation-model training | Excluded | Not required for a working project |

Keep all variants, captions and tasks from one source image in one split. Separate person/event/document-template groups and near duplicates. Freeze final tests before threshold or model selection. Tune only on training/validation data.

Permission to evaluate is not permission to upload, train or redistribute. Record each right separately. Keep raw private media and annotations out of source control. Synthetic identifiers are useful for security tests but cannot alone establish real-photo performance.

## 6. Required measurements

Retrieval: known-item success at 1/5/10, relevant-set precision/recall at stated cutoffs, nDCG for graded judgments, absent-item behaviour and actual time to locate an image.

Extraction: OCR character/word error, supported-language/category coverage, visible-fact precision/recall, unsupported assertions, hallucinations and abstentions. String overlap alone does not establish factual correctness.

Redaction: sensitive-region recall by category, remaining readable secrets, over-redacted useful content, missed metadata/frames and manual correction time. Zero misses in a finite sample is not a guarantee of safe future input.

Android: model download and install size, cold/warm latency, indexing throughput, peak memory, battery, thermal effects, low-storage behaviour and process-death recovery. Report device, OS, runtime, power state, model versions and library size.

Cloud: preparation, user review, upload, queue, inference, download and integration times separately; request bytes, operational metadata disclosed, cost, expiry and cancellation outcomes. Do not report inference-only latency as the user's wait time.

Security: separate tamper/replay/cross-account results from model accuracy; verify no upload in LOCAL and no full-image fallback on redaction failure.

Report failure counts, category breakdowns and group-aware uncertainty where justified. Do not publish invented improvement percentages or treat another project's model-card result as ours.

## 7. Routing and learning

For each permitted task, record local, full-cloud and redacted-cloud outputs with exact artifacts, model versions and resource costs. Independent reviewers grade correctness and usefulness. Mark privacy-ineligible modes separately instead of treating them as allowed oracle choices.

Useful routing features may include task type, OCR coverage/uncertainty, unsupported local scripts, image quality, resource constraints and local-method disagreement. Features must exist before remote execution. Never use future cloud output as a predictor or a model's uncalibrated confidence as the sole signal.

Compare manual selection, explicit rules and a learned baseline where sufficient labels exist. An evaluation-only oracle estimates an upper bound under the same privacy restrictions. Report missed beneficial requests and unnecessary disclosures at matched request/cost budgets.

Hard permission rules run independently of the router. No suggestion changes the selected mode, authorizes an upload or bypasses exact-artifact approval.

PL126 reviews learning curves using staged amounts such as 250/500/1,000/2,000 pairs, where meaningful. Fine-tune only if repeated failures, rights and coverage justify it. Revalidate whenever model, task or redaction policy changes.

## 8. Resource and privacy decisions

Qualify mobile runtimes before designing around a model. Compare quantized/exported artifacts against the reference pipeline for quality and preprocessing parity. Include the vision encoder, tokenizer, KV cache and temporary tensors in peak memory; weight size alone is insufficient.

Bound image resolution, prompts and output length. Do not allocate the entire gallery into memory. Resume work in chunks and explain pauses caused by lock, OS scheduling, heat or battery policy.

Cloud-redacted requests must not include hidden secrets through side channels such as local OCR strings, captions, filenames, GPS, embeddings or earlier prompts. Review the entire request, not just the displayed raster.

For generative output, use fixed extraction tasks and bounded schema. Image text is data, including malicious instructions. No tools, execution, automatic sharing or changes to permissions.

## 9. Decision artifacts

G4 requires:
- Corpus rights/consent manifest and grouped split definitions.
- Model/runtime/license/hash manifest.
- Non-generative versus generative results across three modes.
- Physical-device feasibility, quality, privacy, latency and cost report.
- Redaction and router comparisons with failures and uncertainty.
- A deployment decision: which capabilities help, which do not, and why.
- A tuning decision supported by learning curves or a documented reason to keep pretrained models.

Rejecting generative deployment after completing a fair comparison is a valid outcome. Skipping the comparison is not completion of this plan. No inference or training result is claimed here.

