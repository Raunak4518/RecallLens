# Android UI improvement plan

The main flow is **grant access → see searchable photos → type one prompt → open a result**. Keep the gray-and-white Compose theme and a single search field. Keep processing settings and naming separate from entering a search.

## Current flaws

| Priority | Finding | User impact | Source |
| --- | --- | --- | --- |
| P0 | Before the first photo is ready, the same generic waiting message can cover paused, deferred, or failed work. | A user cannot tell whether to wait, resume, or retry. | [Library screen](../app/src/main/kotlin/org/photolibrary/app/library/LibraryScreen.kt) |
| P0 | Visual indexing can reach the full processed count before OCR has caught up. Only marksheet queries have a dedicated OCR-pending state. | Other text queries can appear to have no matches while text evidence is incomplete. | [Library screen](../app/src/main/kotlin/org/photolibrary/app/library/LibraryScreen.kt) · [Processing evaluation](search-processing-evaluation.md) |
| P1 | The prompt has no clear action and uses a long single-line example. | Restarting a search requires editing the prompt, and narrow screens may truncate the example. | [Library screen](../app/src/main/kotlin/org/photolibrary/app/library/LibraryScreen.kt) |
| P1 | A complex prompt can stack multiple warnings, date text, and location messages before the photo grid. | Feedback can take up the space needed to inspect results on a small screen with the keyboard open. | [Library screen](../app/src/main/kotlin/org/photolibrary/app/library/LibraryScreen.kt) |
| P1 | Sources includes permissions, folder management, naming, clothing analysis, indexing controls, and notices. | A user who needs to link their identity or resume indexing has to discover an unrelated menu first. | [Sources menu](../app/src/main/kotlin/org/photolibrary/app/library/LibrarySourcesMenu.kt) |
| P1 | The grid shows evidence labels and filenames, but opening a result gives little explanation of which words or fields matched. The detail screen also has a weighted image and a fixed action row. | It takes more work to distinguish similar documents, and actions risk crowding at large font sizes. | [Library screen](../app/src/main/kotlin/org/photolibrary/app/library/LibraryScreen.kt) |
| P2 | The development cloud path exposes transport configuration. | A cloud setup designed for a developer will confuse someone who only wants to search the phone. | [Cloud service](cloud-service.md) |

## Implementation order and acceptance

1. **Search and empty states.** Add Clear beside the single prompt, collapse multiple missing-evidence explanations into Match details, and give paused or failed first-time indexing a recovery action. Verify that clearing restores only indexed photos, keeps the same processed count, and leaves confirmed-only name handling in place. Verify the no-access, selected-access, empty, paused, searching, and no-match states on the emulator.
2. **Evidence and OCR coverage.** Distinguish photos ready for visual search from an incomplete text search without adding mode chips or multiple persistent counters. Preserve the distinction between confirmed matches and model suggestions. Show evidence on photo detail where the app can accurately associate a snippet or named region with that result. Depends on trustworthy evidence provenance and per-search coverage.
3. **Sources and detail navigation.** Keep gallery permissions and folders together. Give naming, identity, and analysis a clearly labeled management destination. Keep common detail actions within reach on narrow screens and allow wrapping or scrolling when text is large. Verify that Back returns to the originating search. Depends on the current search-state contract.
4. **Privacy and recovery.** Keep local search account-free. Review the exact approved artifact and explain full versus redacted disclosure in plain language. Show actionable errors and keep the normal library usable after cloud failure. Add lock and rebuild controls once the key and worker lifecycle support them. Public cloud sign-in and hosted availability are separate release dependencies.
5. **Usability and release.** Run the functional flows on normal and narrow portrait displays, large font, TalkBack, dark mode, and keyboard-visible layouts. Run retrieval, naming, permission recovery, cancellation, and restart with public or generated fixtures. Confirm that the app remains usable with indexing in progress, then qualify the supported physical phones and record what remains open in the [release checklist](implementation-todo.md).

The first pass implements Clear, Match details, and paused or failed indexing feedback. It passed the clear-and-indexed-only gallery, nearby search, and marksheet workflows on emulator-5580. All 25 host tests, build, and lint passed. See `app/build/ui-search-acceptance/receipt.json`. The wider permissions, recovery, and accessibility matrix still needs direct execution.

The follow-up distinguishes incomplete OCR from a completed empty search for ordinary text-capable prompts. It keeps one processed-photo count. The empty state shows pending work, an Android scheduling delay, a paused task with Resume, or failed processing with Retry when those states apply. The focused run passed 3 Android and 25 host checks, build, and lint. See `app/build/ocr-coverage-ui-acceptance/receipt.json`.

Photo detail now puts saved entities and the action to add a name in their own section. Its actions are stacked in a scrollable column. See the [entity discovery and fine-detail plan](entity-and-fine-detail-plan.md) for grouping, recognition, and privacy requirements.

The focused emulator build passed four Android checks and 25 host checks, build and lint. The two search and detail checks also passed at 320 dp wide with 150% text. The detail workflow activates the naming action through Android accessibility. A manual TalkBack session and physical-device UI review remain open. See `app/build/entity-detail-acceptance`.
