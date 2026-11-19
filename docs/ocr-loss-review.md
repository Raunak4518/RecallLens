# OCR loss-cohort review

PL058 and PL071: the frozen paired300-photo run found143/360 targets with ML Kit and160/360 with Tiny. Tiny gained44 and lost27 queries. This review inspects all27 losses using their original attributed images and saved model outputs. It does not rerun inference, alter ground truth, remove cases or establish performance on unseen data.

Run python tools/create_ocr_loss_review.py after report_large_ocr.py. It verifies the corpus manifest, checks each source image hash and verifies that each CSV loss agrees with the raw query result. It writes seven contact sheets and a JSON containing attribution, all recognized lines and the selected display lines under app/build/large-ocr-comparison/loss-review. The Tiny display line is selected by string similarity and is not a claim about the detector's internal cause. Source images are resized only for the contact sheet.

## Observed failures

| Case | Saved evidence | Consequence |
| --- | --- | --- |
| q023 athletes; q025 macmillan; q107 nokia; q221 utility | ML Kit has a matching line; Tiny's saved output has no query match. The source has small print or logos. | An empty whole-image OCR result cannot detect these losses: other text was recognized. These are candidates for text-region/scale evaluation. |
| q181 jesus and q182 de jesus | ML Kit returns DE JESUS; Tiny returns DEJESUS. | The whole-word/within-line search rule loses both queries despite similar recognized letters. These two queries share one source image and are correlated. |
| q326 build it | ML Kit returns am it. Build it. Live; Tiny returns amit.Buildit. Live. | Token boundaries affect retrieval. Unrestricted substring matching could recover this case while increasing unrelated matches. Evaluate a separate approximate-results tier against absent queries before changing search semantics. |
| q188 falvey hall; q239 welsh; q186 carlos sobera | Tiny returns Falvey Hal, Weish Guards and Carlas Sobera in the relevant lines. | Recognition errors remain after text detection. The outputs do not isolate the cause to detection or recognition without intermediate model evidence. |
| q130 apply see; q204 pcm recorder; q330 new stanton; q338 with tray | Portions of the requested phrase are absent from matching Tiny lines. | Phrase retrieval is sensitive to incomplete recognition and line grouping. Concatenating every line risks joining unrelated columns or signs. |
| q247/q248 on a historical bird plate | Small scientific labels have differing recognized spellings. | This is a demanding valid scene-text cohort, with related queries sharing one image. It is not representative of every phone document. |

The visual review also flags q069 ewing, q098 coll se, q131 newfp, q276 o later and q325 crosoft for a separate query-quality audit. They contain fragments, occlusion or naming/context ambiguity. A visible fragment can be a legitimate OCR annotation while being a weak remembered-photo-search task. This is a warning about interpretation, not permission to exclude inconvenient results retrospectively. Review the underlying full-resolution annotations before labeling any of them annotation errors.

## Model decision

Keep ML Kit as the installed baseline for now. Tiny's aggregate gain and lower emulator latency justify retaining it as a serious candidate. The27 losses demonstrate that it does not dominate ML Kit on this corpus. Running both engines on every photo has a measured component cost; the previous retrospective routing analysis also shows that an empty-output fallback does not recover the held-out losses. It must not be presented as a universal solution.

Before replacing the default, freeze a new image-grouped tuning/evaluation set with manually reviewed remembered-text tasks, including absent queries, multilingual documents and screenshots. Report exact and approximate retrieval separately. Compare region/scale and token-boundary candidates with fixed false-match limits, plus physical ARM memory, latency and energy measurements. The current contact sheets support failure analysis only. [Paired benchmark and routing evidence](large-ocr-comparison.md)
