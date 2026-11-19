# Local grouping and search feedback

## One-sentence project description

The app searches an Android photo library from one natural-language prompt, groups likely repeated people or objects for the user to review, and keeps its searchable evidence and optional ranking feedback encrypted on that phone.

## Entity grouping flow

1. The user grants gallery access and lets local indexing build the encrypted search catalog.
2. In **Sources → Name suggestions**, local face and common-object detectors find candidate regions. Candidates are partitioned by detected kind and label, then compared with the corresponding face or object descriptor.
3. A cluster is presented only as **Possible same subject**. The current proposal policy uses complete-link similarity against the group, a margin over the next group, a maximum of twelve photos, and task-specific score cutoffs. A missing feature, ambiguous score or duplicate photo remains separate.
4. The user unchecks lookalikes and names only the selected photos as one entity. The app checks every source revision and saves the confirmed region links together in one encrypted transaction.
5. Named search resolves the name to those confirmed links. Unreviewed suggestions do not become Named results.

These cutoffs are candidate-generation rules, not calibrated identity probabilities. Similarity groups can contain the wrong subject; the review and explicit confirmation are required. This is a human-reviewed local grouping feature, not a claim of perfect automatic face or instance clustering.

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
