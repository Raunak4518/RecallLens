# Partial Content search and coverage correctness

Implementation pass for PL064/PL065 and the PL066/PL070 publication boundary, 18 November 2026. Tests are restricted to emulator-5580. No phone, account or public host was used.

## User behavior

Content mode keeps useful visual suggestions when known query conditions cannot be fully checked. Saved person/object/document/clothing/group/meme labels in affirmative requests restrict the embedding candidates to their confirmed photo intersection before top-k ranking. Results are labeled “Possible match · some conditions unverified.” Missing self, clothing, spatial and place evidence remains visible. Named mode retains its strict confirmed-only boundary and does not gain this fallback.

The parser is deliberately bounded. Explicit exclusions/alternatives (not, without, except, or, neither, nor, excluding, instead of, apart from, rather than) disable affirmative intersection and receive unverified-logic feedback. This is not general language understanding or verified negative filtering. Generic recognized spatial requests also receive the partial label. A recognized affirmative near/around/in suffix naming a saved PLACE now applies the real30km GPS filter before scene ranking, including unsupported complex expressions. Permission failure, absent anchors or stale coordinates cannot fall back to ungeofiltered candidates. Unknown locations, temporal predicates and spatial relationships remain unverified; CLIP does not prove them. Places/organizations are not treated as exact-instance appearance filters. See self-and-complex-location-plan.md for acceptance.

The real workflow explicitly names an existing astronaut fixture, indexes actual photos with CLIP, searches for that person behind a red car, and checks that only the confirmed anchor can appear as partial. The photo does not prove a red car or the spatial relation; the test validates the disclosure and confirmed-person scope. Unconfirmed same-person/lookalike photos remain excluded. Switching to Named removes partial results, and the exact saved name returns only its confirmed reference. A generic spatial query also displays its limitation. This is functional acceptance, not a relevance benchmark of complex prompts.

## Cache publication and the displayed count

Two distinct defects were identified. Independent source review reproduced a stale async cache-publication path: an old larger gallery read could complete after a reload and reinsert old records. PhotoSourceSnapshot/PhotoCacheRestoration now bind awaited cache results to current source generation, URI/revisions, access, folders and volume availability. Cancellation, changed/loading sources, callbacks and late error notices cannot update a newer source snapshot. Reload resets coverage counts. Four deterministic asynchronous tests exercise shrink, edit/scope changes, loading and cancellation-resistant reads.

Separately, the screenshot displayed seven cached photos against four “photos” because the UI used the previous worker batch's checkpoint total. That denominator is now the current accessible gallery size. The rendered-text regression deliberately keeps the checkpoint total different from the current source count, so an assertion about in-memory counts alone cannot hide this UI mistake. Indexing-batch progress retains its own separate checkpoint values.

## Boundaries and evidence

The non-UI logic snapshot passed28 focused Android checks with zero skips and all25 core host checks. Its exact source/model/APK hashes and per-class transcripts are in app/build/partial-content-logic-verification/receipt.json. Build and lint passed. This includes query planning, partial behavior, source-cache races, real-photo workflow, query-publication races, search guidance, Named lifecycle and active source observation.

The subsequent denominator change passed both focused Android UI checks with zero skips, plus25 host checks and build/lint. The real workflow passed in36.749 seconds and search guidance in36.590 seconds. app/build/partial-content-delivery.json links those snapshots and package evidence. The saved app/build/partial-content-search.png shows the corrected rendered count. The later explicit-self/nearby pass reran this workflow, guidance, query and persistence cases as part of58 Android passes and one opt-in skip; its distinct receipt is app/build/self-nearby-verification/receipt.json. Do not merge snapshots into a fictional single full-suite run. The earlier clean80-pass/3-skip core baseline is preserved separately.

The encoder and model assets are unchanged. The frozen2,000-photo/60-query benchmark therefore remains an encoder baseline; it is not a measurement of this new full UI or complete relation engine. See large-gallery-evaluation.md for label incompleteness and generated-query limits.

ARM64 unsigned APK/AAB builds and static inspection are separate from emulator runtime checks. A refresh signing command was rejected before execution by automatic approval review (“blocked by policy”). Earlier signed artifacts remain unchanged and do not contain this pass. app/build/signed-release-receipt.json distinguishes their hashes from the new unsigned artifacts. No signing workaround or public upload was attempted.
