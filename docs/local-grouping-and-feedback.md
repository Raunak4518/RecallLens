# Local grouping and search feedback

## One-sentence project description

The app searches an Android photo library from one natural-language prompt, groups likely repeated people or objects for the user to review, and keeps its searchable evidence and optional ranking feedback encrypted on that phone.

## Entity grouping flow

1. The user grants gallery access and lets local indexing build the encrypted search catalog.
2. In **Sources → Name suggestions**, local face and common-object detectors find candidate regions. Candidates are partitioned by detected kind and label, then compared with the corresponding face or object descriptor.
3. A cluster appears as **Possible same subject**. For faces, the closest group member must reach a similarity of 0.55 and every member must clear 0.50. For objects, every member must reach 0.90. Both use a 0.04 gap over the next eligible group and propose at most twelve distinct photos per group. The face scores use aligned crops, so the surrounding scene does not determine a match.
4. The user unchecks lookalikes and names only the selected photos as one entity. The app checks every source revision and saves the confirmed region links together in one encrypted transaction.
5. Named search resolves the name to those confirmed links. Unreviewed suggestions do not become Named results.

These cutoffs are candidate-generation rules, not calibrated identity probabilities. Similarity groups can contain the wrong subject; the review and explicit confirmation are required. This is a human-reviewed local grouping feature, not a claim of perfect automatic face or instance clustering.

Opening Name suggestions scans permitted photos independently of OCR and scene indexing. The encrypted index keeps each completed photo's regions, descriptors, and skipped regions. Closing review stops its active scan. Reopening restores valid records and resumes missing work. The app checks source and model revisions before reusing records.

The phone build uses the Q4 CLIP text encoder for visual prompts. This uses the same image encoder and existing cached image vectors. Q4 improved known-target Hit@5 from 30.5% to 74.5% on the existing 200-prompt cached-vector test. That measure covers visual retrieval. [Model evaluation results](model-quality-evaluation-results.md)

## Phone update

Photo detail opens a larger viewer with pinch and double-tap zoom. Share and Open with hand the original URI to the Android chooser with read access for the recipient selected by the user. The library shows a separate text-index count while OCR coverage is incomplete.

The phone build uses `-PphotolibraryClipTextPrecision=q4 -PphotolibraryFairIndex=true`. The scheduler alternates visual and OCR work so OCR can start before visual indexing finishes. Each indexing batch can decode the next photo during inference and keep at most one additional bitmap. The visual encoder chooses one to six inference threads when a batch opens its model session, depending on the available processors, memory, power mode and thermal status. Release qualification still needs sustained temperature, memory and paired thumbnail/search timing on physical phones.

## Two-icon feedback flow

Each ordinary prompt result has an accessible thumbs-up and thumbs-down control. Explicit named-entity queries do not show these controls, so a generic relevance rating cannot silently remove or redefine a confirmed identity link.

Ratings affect the current search immediately: positive results can move upward and negative results can be hidden for that exact query and source revision. With **Search learning** off, the feedback is held only for the current search session. Persistent learning requires the user to enable it. Ratings, their search association and compact ranking features are stored in the encrypted index on this phone; no photo pixels or image embeddings are saved as feedback examples.

The learned component is a small local ranking head. It can learn from enough separate positive and negative examples and only changes result order. It does not update CLIP, OCR or detector weights. Turning learning off deletes persisted ratings. The feedback path has no upload or server dependency. The separate ML Kit diagnostic disclosure still applies to its OCR SDK.

## Presentation route

1. Show the main screen: one prompt, processed-photo count and indexed results.
2. Enter a scene or remembered-text prompt and show matching photos.
3. Tap thumbs-down on a clear wrong result; show that it is hidden for this prompt and that the choice can be undone.
4. Open **Search learning** to show the explicit local-learning control and encrypted on-device storage disclosure.
5. Open **Sources → Name suggestions**. Show a **Possible same subject** group, uncheck a lookalike, and name the remaining selected photos once.
6. Search for that name. Show confirmed photos and explain that unconfirmed suggestions are excluded from Named results.
7. Show the launcher icon and explain that no account or cloud upload is used for local search, grouping or feedback.

## Evidence and limits

`ReviewGroupsTest` exercises same-subject grouping, lookalikes, ambiguity, same-photo separation, category separation and the group-size limit. `FeedbackRankingTest` checks prompt-scoped ratings and source-revision invalidation. Both classes passed on the connected I2221 Android 16 phone: OK (6 tests). The debug build installed and launched on that phone. The actual Name suggestions review screen and feedback tap flow were not confirmed in the phone UI during this run; treat those as not yet device-verified. Background indexing evidence is recorded in [durable indexing](durable-indexing.md).
