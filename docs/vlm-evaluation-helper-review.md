# VLM evaluation helper review

Read-only source inspection of tools/vlm_eval/decision.py, test_decision.py and prepare_review.py while the emulator baseline runs. No model inference or additional tests were executed by this reviewer.

The decision parser accepts only complete yes/no/uncertain responses after ANSI cleanup. Additional explanation, conflicting answers and malformed JSON do not become support. Candidate metrics use the actual displayed top five and keep unjudgeable rows separate; all-unjudged precision is null. Reranking is stable within each response tier and candidate/decision/judgment sets must match. These avoid the earlier full-ranking-before-top-five metric error.

Two focused hardening issues were identified and are now corrected in the inspected preparer:

- Existing-judgment protection originally used check-then-replace. The final code instead writes a unique temporary file in the output directory and publishes with os.link, which fails if judgments.json appeared concurrently; the temporary file is removed in a finally block. The new test creates a reviewer-owned judgment file during contact-sheet rendering and asserts its exact bytes survive and no temporary files remain. This addresses the reported overwrite race.
- Query IDs now must be unique lowercase safe basenames matching a bounded regular expression before any output is written. Duplicate, nested, absolute and traversal IDs are rejected. The new tests exercise those invalid values and duplicate IDs, preventing contact-sheet overwrite or path escape through query IDs.

The root reports eight passing decision/preparation helper tests. This reviewer inspected their final source and assertions without rerunning them during the emulator timing experiment. Changed corpus hashes and altered candidate image bytes are rejected, prepared judgments remain explicitly pending/null, and candidate source/license/hash metadata is retained. No further consequential issue was found in this focused rereview.

Metrics should continue to describe reranked displayed candidates separately from supported=yes decisions. An uncertain or malformed row can remain in a top-five related list because that is the proposed partial-result behavior; its relevance does not prove model support. Absent-case judgments concern the reviewed candidate pools and cannot establish that a requested image is absent from all 2,000 unreviewed originals.

The next-local-evaluation-plan remains a staged local pilot, not a completed model selection. Exact corpus/baseline/image hashes and author/license metadata are retained, image orientation is handled when rendering, scores must be finite and descending, and raw baseline rankings must cover the exact image IDs. Explicit pending review status correctly avoids manufacturing labels from available positives or model output. Future reporting must reject pending labels and distinguish AI-only provisional review from independent human judgment; no final reviewed status should be inferred merely from having rendered contact sheets.
