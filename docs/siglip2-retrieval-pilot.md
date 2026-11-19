# SigLIP 2 retrieval pilot

The Windows INT8 SigLIP 2 conversion moved a relevant image into first place for 12 of 20 reviewed relationship queries. The existing CLIP ordering did so for 10. Combining the two rankings with fixed equal-weight reciprocal ranks reached 13. All three methods returned at least one relevant image in the first five for 16 of 20 queries. The candidate improves some first results. Its larger model and unchanged top-five coverage do not justify replacing the Android default from this pilot.

| Method | Relevant at rank 1 | Unjudgeable at rank 1 | Queries with a relevant top-five result | Relevant / irrelevant / unjudgeable slots in top five |
| --- | ---: | ---: | ---: | ---: |
| Android CLIP ordering | 10/20 | 3/20 | 16/20 | 40 / 43 / 17 |
| SigLIP 2 INT8 reranking | 12/20 | 5/20 | 16/20 | 45 / 38 / 17 |
| CLIP and SigLIP 2 reciprocal rank fusion | 13/20 | 5/20 | 16/20 | 41 / 44 / 15 |

Both SigLIP 2 and fusion moved a relevant image to first place for “a person wearing glasses” and “a dog inside a car.” SigLIP 2 also improved “chairs around a table.” It moved a relevant first result for “a person sitting at a table” to second place or lower and put an unjudgeable image first. Fusion preserved the relevant first result on that query. Fusion improved first place for “a person wearing sunglasses,” where each individual ordering put an irrelevant image first.

## Evaluation

Each method orders the same eight candidates that Android CLIP retrieved from the fixed 2,000-photo corpus. The complete pilot includes 192 slots, 150 distinct images, 20 relationship queries and four absence probes. The probes contain no judged relevant image in their respective pools. They remain separate from the 20-query denominators. Query wording, candidate IDs and provisional AI visual judgments were frozen before SigLIP 2 inference. The pilot measures fixed-pool ranking with provisional judgments. Full-gallery SigLIP 2 retrieval, human relevance judgments and a false-positive threshold remain unevaluated.

The scoring run cached each source image's 768-dimensional normalized feature once and ranked by cosine similarity. The text tower received the original query with maximum length 64. The vision tower used EXIF orientation and the distributed image processor. Equal-weight fusion used reciprocal ranks with k=60 fixed before inference. Exact score ties preserve baseline order.

- Corpus manifest SHA-256 `9082dc2aa880186850253141ae226308516c37972b9652234c18b858d8c9909e`
- Existing Android baseline SHA-256 `080ba853db6ab36ef1e14d16b36ab878ec0880b75bac37d18d01d7fe8a34d5c6`
- Frozen judgments SHA-256 `7cca008cdad301018412d2e97a83f0bd8a8763a497e857fc0e0f477894b37317`
- Comparison JSON `app/build/relation-vlm-pilot/siglip2-int8-comparison.json` with SHA-256 `38135c4ee8552d59eca09e3b173aadcff2fc1eeb06baa5b4c6a2618d6362f4f5`

## Timing and model provenance

The Windows run used ONNX Runtime CPUExecutionProvider on a Ryzen 7 7435HS with two inference threads and image batches of up to four. Combined model, tokenizer and processor loading took 4.097 seconds. Mean image encoding was 245.8 ms per source across the batches. Image reading, orientation and preprocessing added 24.7 ms per source on average. Median text encoding took 67.0 ms, and median ranking of eight cached vectors took 0.084 ms. These host component timings leave Android loading, battery consumption and end-to-end interaction timing open.

The evaluated artifacts came from the `onnx-community` conversion at revision `ba1f3b0843f24bc5417d38e19c37b287d719b2f4`. The text tower is 283,438,275 bytes and the vision tower is 94,553,333 bytes. Combined weights total 377,991,608 bytes, about 360.5 MiB. Each full weight hash matched the conversion publisher's LFS metadata. The conversion card names Google's base checkpoint. It does not attest to an exact Google source commit or declare a separate license. Google's upstream model card declares Apache-2.0. This project evaluated the conversion locally. The official 1.50 GB FP32 checkpoint was abandoned after interrupted transfers and was not scored. [Google model card](https://huggingface.co/google/siglip2-base-patch16-224) · [Pinned INT8 conversion](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/tree/ba1f3b0843f24bc5417d38e19c37b287d719b2f4)

Transformers 5.9.0 logged vocabulary warnings for generic SigLIP configuration defaults of 32,000 entries with BOS/EOS IDs 49,406 and 49,407. The conversion text config declares 256,000 entries and the ONNX text tower takes explicit `input_ids`. The distributed `tokenizer.json` and SentencePiece file match Google's upstream tokenizer hashes. A direct replay of the frozen queries against that JSON produced 64 IDs per query, all in the 256,000-entry vocabulary. The warning remains recorded in the host log. The run establishes conversion behavior with the evaluated preprocessing settings. Bit-for-bit parity against the official FP32 model remains untested.

Keep encrypted CLIP embeddings and the warm cached search path as the current default. An Android follow-up should compare independently judged retrieval gains, full package size and cold query latency before adding another tower to the application.
