# Research and decisions

Version 3. Reviewed: 14 November 2026, Asia/Calcutta. Documentation only.

## 1. Current decision

The user wants a complete, well-engineered multimedia project, not proof of commercial differentiation or algorithmic novelty. The adopted proposal is local photo search with an encrypted local index and three explicit modes: LOCAL, CLOUD_FULL and CLOUD_REDACTED.

This replaces the earlier draft's decision to make encrypted backup the primary use. Backup and dedicated file sharing remain optional. Android is now explicitly approved as the first platform. The plan uses a native Android client; Windows is a possible later companion. Generative LLM/VLM evaluation remains a required later stage, compared with the non-generative baseline.

## 2. Evidence and what it establishes

| Source | Verified relevance | Limit |
| --- | --- | --- |
| [Google Ask Photos](https://blog.google/products-and-platforms/products/photos/ask-photos-google-io-2024/) and [Apple Photos](https://support.apple.com/en-mide/guide/iphone/iphf7de217f0/ios) | Description-based photo retrieval is an established product use | Does not establish our accuracy or speed |
| [Ente Gallery Mode](https://ente.com/help/photos/faq/gallery-mode) | Local search without uploading the library is already practical | We must not claim the concept is new |
| [Ente architecture](https://ente.com/architecture/) | Client-side keys and encrypted private media storage are established | Backup encryption does not let a conventional cloud model process ciphertext |
| [Apple Private Cloud Compute](https://security.apple.com/blog/private-cloud-compute/) | Existing systems escalate local AI tasks to stronger infrastructure with a designed cryptographic trust boundary | PCC relies on specialized systems; merely encrypting a request does not reproduce it |
| [PCC request flow](https://security.apple.com/documentation/private-cloud-compute/requestflow) | Attestation and approved worker measurements govern key release | Not a capability our planned ordinary worker automatically possesses |
| [RouteLLM paper](https://arxiv.org/abs/2406.18665) | Routing between weaker and stronger models can be learned from outcome/preference data | The paper studies LLM routing; it does not validate our image-difficulty features, redaction or sample sizes |
| [Presidio image redactor](https://github.com/data-privacy-stack/presidio/blob/main/docs/image-redactor/index.md) | Local OCR plus sensitive-text recognition can support image redaction | Text redaction alone does not cover all faces or scene context |
| [Presidio FAQ](https://github.com/data-privacy-stack/presidio/blob/main/docs/faq.md) | Its maintainers explicitly describe false negatives and the absence of guaranteed complete sensitive-data detection | A clean detector output must not be labelled privacy-safe with certainty |

Product examples establish real implementations and useful categories. The project's evaluation still needs to establish its own functionality. No invented customer story is required.

## 3. Privacy architecture choices

| Choice | Who can see the inference input? | Recommendation |
| --- | --- | --- |
| Stronger model on the same device | The local app/OS | Always evaluate this option because the user accepts additional compute/time |
| Trusted personal workstation | The workstation/its operator | Useful optional destination; does not require a commercial cloud |
| Ordinary cloud using approved full image | The worker/operator can see the approved visible content and output | Separate explicit CLOUD_FULL mode; metadata removal does not conceal visible secrets |
| Ordinary cloud using redacted image | The worker/operator can see remaining content and output | Separate CLOUD_REDACTED mode with local preparation, review and no silent fallback |
| Attested confidential-computing worker | Plaintext exists inside the claimed trusted execution boundary | Later work requiring actual hardware, attestation and key-release verification |
| FHE/MPC inference | Depending on construction, compute can avoid exposing plaintext to the server | Separate constrained experiment; not a drop-in replacement for AES plus a VLM |

[Concrete ML documentation](https://docs.zama.org/concrete-ml/get-started/getting-started.md) describes encrypted computation and model conversion, including quantization constraints. It does not prove practical full-image VLM performance for this project. We will not promise it without a separate benchmark.

Ordinary request/result encryption remains useful for networks, queues, storage and result isolation. Its boundary ends at the authorized processing worker. Calling that worker unable to see its input would be false.

## 4. Model sources

| Role | Candidate and source | Current evidence |
| --- | --- | --- |
| Image-text retrieval | [CLIP implementation](https://github.com/openai/CLIP), [original paper](https://proceedings.mlr.press/v139/radford21a.html) | Established paired image/text representation baseline; repository MIT license |
| Alternative retrieval encoder | [Google SigLIP2 base](https://huggingface.co/google/siglip2-base-patch16-224/tree/main) | Artifact repository labels Apache-2.0 |
| Later small local generative evaluation | [SmolVLM2 500M](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct) | Accepts images/text and produces text; model card labels Apache-2.0 |
| Later larger local generative candidate | [Qwen3-VL 2B Instruct](https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct) | Model card labels Apache-2.0; actual local memory/quality unmeasured |
| Later generative worker candidate | [Qwen3-VL 8B Instruct](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct) | Model card labels Apache-2.0; candidate, not ground truth or a proven winner |
| OCR baseline | [Tesseract](https://github.com/tesseract-ocr/tesseract) | Official project Apache-2.0; qualify exact language packs separately |
| Alternative OCR | [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) | Official repository Apache-2.0; pin chosen model and artifact notices |
| Fine-tuning implementation reference | [Qwen official training framework](https://github.com/QwenLM/Qwen3-VL/blob/main/qwen-vl-finetune/README.md) | Provides training/data-format guidance, not a universal required dataset size |

The SmolVLM2 model card describes approximately 3.3 million training samples from multiple collections, including LLaVA OneVision, M4-Instruct and video datasets. That is upstream pretraining/instruction data, not what this project needs to collect. Its reported inference-memory example is a specific configuration, not a device guarantee.

[MobileCLIP software license](https://github.com/apple-aiml-research/ml-mobileclip/blob/main/LICENSE) and [model terms](https://github.com/apple-aiml-research/ml-mobileclip/blob/main/LICENSE_MODELS) differ. The reviewed model terms restrict research use. Do not infer unrestricted weight redistribution from the software's MIT license.

Every selected artifact still requires pinned revision, checksum, tokenizer/preprocessing version, redistribution review and target-device validation.

## 5. Storage, cryptography and platform sources

- [SQLCipher design](https://www.zetetic.net/sqlcipher/design/): encrypted database pages and integrity checks; journal/WAL/temp configuration matters.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): first-client key integration. Non-exportability and authentication restrictions do not protect an already compromised running app from use of its keys.
- [Tink AEAD](https://developers.google.com/tink/aead): authenticated encryption and context binding.
- [Tink hybrid encryption](https://developers.google.com/tink/exchange-data): standard public-key envelope candidate; encryption alone does not establish sender authenticity.
- [Tink supported key types](https://developers.google.com/tink/supported-key-types): check real client/worker interoperability.
- [Android architecture guide](https://developer.android.com/topic/architecture): lifecycle-aware UI/data separation supports the native Kotlin/Compose proposal. Exact project boundaries remain engineering choices.
- [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android): maintained Android integration candidate; qualify current Room/runtime/native-library compatibility before implementation.
- [WorkManager long-running work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running): foreground workers still face scheduling limits; Android 16 job quotas make resumable bounded work important.
- [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/): candidate first-client mobile inference backend, not proof that every chosen model exports or fits.
- [ML Kit terms](https://developers.google.com/ml-kit/terms): on-device input processing is distinct from SDK metrics/network contact. Do not make a zero-network promise without auditing the selected stack.

Do not implement encryption algorithms, password KDFs or nonce management from scratch.

## 6. File lifecycle evidence

For a possible future desktop companion, [Windows ReadDirectoryChangesW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-readdirectorychangesw) documents notification-buffer overflow and the need to enumerate to recover changes. Therefore filesystem watchers are hints, not a complete durable history.

[Windows FILE_ID_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_id_info) supplies file identity together with the volume identity. Paths alone cannot reliably track renames/moves. Cross-volume moves and file-ID reuse still require careful reconciliation.

[Android MediaStore columns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns) document generation and trash/expiry fields. Access remains limited by [photo permissions](https://developer.android.com/about/versions/14/changes/partial-photo-video-access). Neither platform justifies scanning outside an approved source to follow a moved file.

## 7. Data rights and evaluation

Use owned or explicitly permitted photos and synthetic sensitive identifiers. No dataset was downloaded for this plan.

[COCO terms](https://cocodataset.org/dataset/termsofuse.htm) distinguish annotation licensing from rights to underlying images. Dataset-mirror licenses do not automatically confer media rights.

The proposed counts in the model plan are staged collection budgets chosen for this project. They are not literature-derived minimums and cannot guarantee accuracy or privacy. Learning curves and category coverage decide whether more labels are needed.

## 8. Class overlap

The supplied CSV's populated titles do not describe this particular local library and selective-redacted-assistance workflow. Related teams cover authenticity, medical privacy, leak tracing and face masking as their primary objectives.

Two teams have no title and one says only alert system. Full non-overlap remains unverified for those entries. Keep the objective centred on private photo retrieval and controlled assistance rather than claiming to solve their primary tasks.

## 9. Decisions and remaining uncertainty

- Approved direction: photo-library project, local processing, encrypted derived data and investigation of controlled stronger processing.
- Product novelty is not required.
- Backup/file sharing is optional, not forced into the core.
- Android is fixed; exact supported versions/devices, model/runtime and OCR/redactor artifacts still need qualification.
- User-visible cloud review is mandatory in the first remote version.
- Learned routing/fine-tuning is conditional on measured improvement.
- The ordinary worker sees the approved full or redacted input, according to the explicit mode.
- Later non-generative versus generative evaluation is required. Deployment and fine-tuning are conditional on evidence.
- No model, routing, redaction, tamper, performance or user-flow tests have run for the new app.

All new work currently consists of planning documents.

## 10. Product claims and current integration limits

[Google Photos search documentation](https://support.google.com/photos/answer/15235862?co=GENIE.Platform%3DAndroid&hl=en) describes search for people, objects, places and text, plus Ask Photos where available. The earlier suggestion that Google Photos recognizes objects but lacks search is incorrect. Our project need not claim novelty; it implements and evaluates a useful private-library workflow.

[Google Photos API changes](https://developers.google.com/photos/support/updates) restrict Library API access to app-created content and direct user selection toward the Picker API. Do not plan an unrestricted plug-in that silently indexes a user's entire Google Photos cloud library. The first client indexes accessible Android media; cloud-only provider items require explicit access and availability handling.

## 11. Android distribution and service sources

| Source | Planning implication | Limit |
| --- | --- | --- |
| [Selected photo access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access) | Support full, selected-only and denied/revoked states; selected access does not imply whole-library coverage | Device/target behaviour requires instrumented tests |
| [Restricted photo/video permissions](https://support.google.com/googleplay/android-developer/answer/16935362) | Justify broad access for core library functionality; provide a limited-scope workflow | Play approval is not guaranteed by writing a rationale |
| [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) | Account-creating apps need applicable in-app and web deletion paths | Declare retention exceptions accurately and verify actual deletion |
| [Play payments policy](https://support.google.com/googleplay/android-developer/answer/9858738) | Paid digital cloud features need an applicable compliant billing route | Recheck current country/program exceptions at launch |
| [Billing security](https://developer.android.com/google/play/billing/security) | Verify purchase ownership and entitlements on the backend | Client UI state cannot prove purchase validity |

These official sources were checked for this planning revision. Requirements can change before publication. PR017, PR024, PR026 and PR027 recheck applicable release policies instead of fixing a permanent claim about approval, target SDK or eligibility.

The proposed stack, task order, data budgets and model selection criteria are engineering recommendations. Sources establish capabilities and constraints; they do not establish our app's performance, user demand or test results.

