# Clothing result publication review

Reviewed current source on 18 November 2026. This is a source-level design review, not new device acceptance. The preceding accelerated split acceptance covers its recorded executable cases; it does not exercise the races proposed here.

## Exact stale-result path

ClothingSearch.search derives revision strings from the caller's photos at line 29, reads colorFractions at line 30, and returns those fractions as matches/related estimates at lines 37–50. It does not read current source state before returning. A pixel edit, trash, deletion or access change after that snapshot can therefore leave a result based on stale garment and Named evidence. The nearby branch suspends inside PhotoLocations.search, enlarging this window even though PhotoLocations independently validates its own coordinates.

CachedGarmentAnalysis validates each restored or newly processed photo before adding it to a growing evidence map. Its progress callback runs after each photo. A later callback can edit/delete an earlier photo, leaving the earlier evidence in the final summary. A per-photo commit check does not prove the entire batch is current when returned.

## Required final invariant

For every URI published in a matched or related clothing group, the accessible current source revision must equal the revision used for the garment record and its confirmed person reference. The model and ownership/color dependencies must still match. A nearby query additionally requires all place anchors used for its coordinate result to remain current. The same final validation must cover both exact color matches and related estimated colors; hiding only the main match list leaves a disclosure path through related results.

Take one final gallery revision map for the batch rather than querying every MediaStore photo separately. For known-revision gallery photos, compare the expected source: revision with that map and check the effective GalleryAccess again before publication. For non-gallery or unversioned sources, validate only the selected URIs used by evidence through PhotoReferenceSource.referenceRevision. Known revision and null/unknown revision must not be silently treated as equal. Recheck cancellation before publishing.

ClothingSearch currently omits every photo with sourceRevision null. That leaves unversioned folder photos unsearchable even though VerifiedGarmentEvidence now persists their pixels: records. The result revision map should be based on the narrow set of relevant confirmed references plus current source validation, rather than scanning and decoding all unrelated photos. Original-file Named proofs and decoded garment fingerprints have different purposes; a garment hash cannot restore a user confirmation.

The caller must also reject a query result if its Named snapshot changes while it runs. PhotoQueryExecution already carries the NamedLibrary snapshot and generation. If ClothingSearch is called outside that coordinator, its contract must state that it validates source lifecycle but cannot independently observe a user removing a name unless supplied current Named state or a Named-store revision.

## Scoped cleanup

Do not call GarmentEvidenceStore.reconcile with a map containing only matches or only stale URIs: it is a full-scope operation and removes every other accessible row omitted from the map. For stale records found during final validation, use a conditional deletion targeting URI and expected source/dependency identity; a write token additionally protects a newer same-revision writer. Existing removeWrite(uri, token) is safe for the writer that owns that token but the search reader does not currently receive tokens.

A retained authorized-but-unavailable source must be hidden while respecting the documented retention policy. Definitive access removal/deletion should prune its record. A stale verification task must not delete a newer valid row, reintroduce a removed row, create a Named confirmation or transfer cloud consent.

## Suggested deterministic tests

1. Seed confirmed person regions and encrypted garment evidence on at least two owned MediaStore photos. Trigger edit/delete/trash of an earlier photo during a later progress callback; final summary must exclude the earlier evidence and reconcile counts.
2. Add a suspendable source-validation seam to the search workflow. Delete/edit/deny scope after color fractions are read but before final publication; assert absence from both matched and related color groups.
3. Use an actual EXIF-only location change while a clothing-nearby query is pending; preserve pixels and require current anchors/coordinates.
4. Confirm an unversioned provider photo; verify cached clothing search works without processing unrelated gallery photos.
5. Replace a stale garment row with a newer source/model/write-token row before cleanup; final cleanup must preserve that newer row and all unrelated rows.

## Priorities after this fix

Finish one stable combined local flow and measured physical-device acceptance before repeatedly expanding lifecycle slices. The original G1 gate still requires PL069 physical latency, memory, battery and thermal evidence; model selection still needs independent retrieval/error labels rather than repeated correlated smoke fixtures. The required later VLM comparison is PL124–PL128 and has not been replaced by the encoder work. Production G6 additionally requires signing, Play/product policy and operational cloud qualification. Those requirements cannot be inferred from green emulator tests. Query/scheduling extraction and clear coverage/status UI directly improve maintainability and the user's ability to understand the app. New-URI/cross-volume continuity should be a bounded subsequent lifecycle feature with explicit identity rules, rather than broad hash reuse across privacy or consent boundaries.
