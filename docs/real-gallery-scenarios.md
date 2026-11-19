# Real gallery scenario and evaluation matrix

Use this matrix with PL055, PL058, PL064, PL069–PL071 and PL113–PL128. It defines test cases and required evidence. A listed case becomes supported only after its normal workflow passes.

| Scenario | Expected behavior and evidence |
| --- | --- |
| Newly authorized 5,000-photo gallery | Show thumbnails and usable completed search coverage while bounded indexing progresses. Measure time to first results and full coverage. |
| Same gallery after restart | Reuse source/model revisions and encrypted outputs. Count decodes and model calls separately. |
| New photos during indexing | Add new task records without restarting completed work. |
| Same-path edit or crop | Invalidate stale derivatives and cloud consent. Verify a late result cannot overwrite the new revision. |
| Move within gallery | Reconcile provider identity and current scope. Preserve names only with validated identity/revision. |
| Move outside permitted scope | Remove searchable derivatives and pending jobs for the inaccessible source. |
| Trash, restore, permanent deletion | Exercise all three states independently with process restart between transitions. |
| Partial media permission | Search only accessible images. Changing the selected subset updates every task. |
| Folder and gallery overlap | One searchable asset when provider identity can be established. Preserve distinct edited copies. |
| Broken URI or disconnected provider | Show an unavailable result and retry state without blocking all indexing. |
| Unknown or unreliable revision | Use bounded content validation and measure its decoding cost. |
| Duplicates and bursts | Evaluate near-duplicate result grouping and retain independent originals. |
| Corrupt, very large or animated input | Enforce decoder bounds and explicit supported-format handling. |
| Rotated image or unusual color profile | Correct displayed orientation and region mapping. |
| Small distant sign | Evaluate full-photo remembered-word retrieval and crop resolution separately. |
| Low-contrast text or reflection | Report recognition failures and missed queries with examples. |
| Curved package or perspective document | Measure text and phrase retrieval after realistic viewpoint changes. |
| Screenshot, meme or collage | Preserve multiple text regions and label evidence. |
| Hindi, Gujarati or mixed script | Qualify the selected script model. Latin-only results supply no validation here. |
| Handwriting or vertical text | Separate capability cohort with permitted ground truth. |
| Phrase wrapped across lines | Define token and layout semantics before claiming phrase support. Current matching requires one line. |
| Remembered misspelling | Evaluate optional fuzzy matching against absent-query false positives. |
| Exact personal car among same-model cars | Hold out independent viewpoints and sessions. Record false suggestions. |
| Same laptop or shoes with similar distractors | Require instance-level ground truth beyond category labels. |
| Person in a group | Match each face and retain region coordinates and ambiguous candidates. |
| Small, side-on or masked face | Expose missing evidence and evaluate reference enrollment failure. |
| Person with changed clothes | Use face evidence for identity. Measure appearance-based candidates independently. |
| Two people wearing red | Attach the garment to the correct person region before satisfying the conjunction. |
| Person hidden or seen from behind | Treat body appearance as uncertain evidence. Measure confusions between similar outfits. |
| My photo or myself | Resolve a user-confirmed self identity and aliases to a stable entity ID. |
| Me with my car | Intersect confirmed entity evidence. Keep suggestions visibly unconfirmed. |
| Me in red beside my car near Surat | Require person, garment relation, object and location evidence on the same photo. Measure each missing-evidence reason. |
| Near city versus exact venue | Define radius and boundary cases, show the chosen geographic scope. |
| Missing or wrong GPS | Avoid asserting location solely from file time or an unrelated tag. |
| Previous college and current college | Store user-confirmed history with validity dates and date provenance. |
| Saved document versus another copy | Distinguish exact-instance matches, versions and document categories. |
| Empty query and absent subject | Show explicit empty/unknown states and measure false positive rate. |
| Model update | Invalidate only dependent tasks and keep usable unaffected coverage. |
| Power saver, hot device or low storage | Pause bounded tasks with resumable state. Measure on ARM hardware. |
| Local-only operation | Verify no uploads and inspect permissions, telemetry and dependencies. |
| Wrong key or copied database | Fail without replacing existing encrypted state. |
| Redaction misses an identifier | Require reviewed pixels and report residual exposure. Presence of one mask cannot prove safety. |
| Edit after cloud approval | Invalidate old approval and reject late results. |
| Replayed or conflicting job | Bind request identity, destination, artifact digest and result context. |
| App killed during save or migration | Reopen consistently and preserve originals. |

## Evidence selection

Use HierText for scene-text layouts and full-photo remembered queries. The current 300-photo experiment supplies 180 annotated images and 120 mixed Open Images photos. Its 360 queries cover printed horizontal Latin text. Extend scripts and handwriting through separately licensed cohorts. [HierText](https://github.com/google-research-datasets/hiertext)

Objectron offers changing viewpoints of tracked objects. Split by physical track, and add independent-session exact-instance fixtures before claiming general personal-object recognition. Current Objectron evidence has correlated frames. [Objectron](https://github.com/google-research-datasets/Objectron)

Use Open Images for category and visual relationship cases with per-image attribution. Category labels cannot establish that two cars are the same physical car. [Open Images](https://storage.googleapis.com/openimages/web/download_v7.html)

Add consented group portraits and garment-to-person annotations, geographic boundary fixtures, edited duplicates and lifecycle transitions. These task-specific annotations are required to score relational queries. Existing crowd labels alone cannot verify the complete example query.

Keep tuning and evaluation identities, source scenes and object tracks disjoint. Freeze source IDs, preprocessing, queries, relevance judgments and SHA-256 before model comparison. Record enrollment failures in the denominator. Report recall@1/5, absent-query false positives, per-cohort recall, cold/warm time, model calls, index bytes and peak memory. Obtain ARM battery and thermal measurements separately from emulator timings.
