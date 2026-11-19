# Fast local semantic reranking pilot

PL064 evaluation, 18 November 2026. Compared two strategies using the existing quantized CLIP models, with no added image model or query-time image decoding. The experiment used the frozen 24-query review and the exact eight Android candidates per query from the 2,000-photo benchmark. The pools contain 192 slots and 150 unique photos. The relevance labels are provisional visual judgments from two AI reviewers. This evaluates reranking within the eight candidates, leaving recall elsewhere in the gallery unmeasured.

The host pilot verified the corpus manifest, Android rankings, reviewed judgments and source JPEG hashes. It used the bundled ONNX models and tokenizer with ONNX Runtime 1.26.0, two intra-op threads and one inter-op thread. Pillow 12.1.1 applied EXIF orientation, sRGB conversion, a maximum 896-pixel decode size, central crop, bilinear resizing to 224 pixels and the bundled channel normalization. Android decodes through ImageDecoder and uses Skia scaling, so host pixel and score parity is unproven. The two plain baselines agreed on the top candidate for 13 of 24 queries and on the full order for one. Across the 192 matched scores, Pearson correlation was 0.888 and mean absolute difference was 0.0063.

I wrote the contrast and component prompts using the query texts before generating scores or inspecting candidate judgments. The input file and a 0.25 weight for each variant were fixed before inference. The contrast score adds one quarter of the difference between full-query similarity and a plausible confounder prompt. The conjunction score adds one quarter of the lower of two component similarities to favor images visually consistent with both. These hand-authored prompts enable a diagnostic comparison. Automatic decomposition of arbitrary user requests remains separate work.

| Method | Relevant top 1 | Any relevant in top 5 | Relevant / irrelevant / unjudgeable top-5 slots | Median warm query work |
| --- | ---: | ---: | ---: | ---: |
| Plain CLIP on Android emulator | 10/20 | 16/20 | 40 / 43 / 17 | 12 ms encode, 25 ms full-gallery rank |
| Plain CLIP on Windows host | 13/20 | 15/20 | 41 / 42 / 17 | 9.16 ms |
| Contrast on Windows host | 13/20 | 15/20 | 42 / 42 / 16 | 19.22 ms |
| Conjunction on Windows host | 13/20 | 15/20 | 40 / 42 / 18 | 23.63 ms |

The host timings cover text encoding and scoring eight cached vectors. The Android times cover query encoding and full-gallery ranking as separate medians. The four combination probes had eight irrelevant reviewed candidates each. All compared methods rank those candidates without deciding that a match exists. The absence probes therefore provide no result about a threshold or an abstention policy.

Keep plain CLIP as the default. Neither variant improved the host top-one or top-five query success count. Contrast recovered one relevant slot at top five, without reducing the number of irrelevant slots. Both variants increased text encoding work. This small provisional pilot leaves room for a better model or later independent evaluation of a general decomposition method.

The tested Android baseline took 3,409 ms to encode its first query, then 12 ms median after session initialization. Its first full-gallery rank took 78 ms versus 25 ms median later. Cache restoration for 2,000 vectors took 1,753 ms and decoded zero photos. Foreground warmup of the existing text encoder targets the measured first-search delay without changing model or index format. Measure typing-to-result on a restored emulator gallery with and without warmup and report app-start-to-result separately. The evidence above does not cover phone hardware or total rendered UI latency.

The runner, prompt list and detailed per-image scores are under `.agent-staging/fast-semantic`. The prompt SHA-256 is `7a746d04bc6bf9a6194351050f8dd8b6e0ab8c83d1e6a97382823480c8f36d2c`. The frozen judgment SHA-256 is `7cca008cdad301018412d2e97a83f0bd8a8763a497e857fc0e0f477894b37317`. The complete Android baseline remains in `app/build/relation-vlm-pilot/baseline/emulator.json`.
