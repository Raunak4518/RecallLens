# Agent handoff

Start with [README.md](README.md) and the [active Android search plan](docs/final-android-search-plan.md). Use A01–A13 and O01–O12 from that plan for prerequisites and completion evidence. Check current code, build output and test results before declaring work complete.

This checkout's active product is a standalone, phone-local Android photo search app. The cloud design in [blueprint.md](docs/blueprint.md), its service code and hybrid checklist describe a separate system and do not define Android app requirements. Do not add cloud transport, account requirements, remote inference or upload behavior to the Android app. Preserve imported legacy evidence only with its original provenance and never label it as locally produced.

## Active Android requirements

- Kotlin and Jetpack Compose.
- The user searches accessible phone photos with a prompt. Indexing, inference, personalization and retrieval work offline without an account.
- Keep photos on-device. The app has no cloud upload, service dependency or remote fallback.
- Encrypt sensitive derived index data and protect keys with Android Keystore.
- Preserve correct access and revision handling for edit, move, trash, deletion, permission changes, interruption and restart.
- Keep model implementations replaceable through task-specific interfaces. Compare models on common fixtures before promoting one. LLM/VLM candidates remain in the plan for later evaluation.

## Engineering

Follow the boundaries in the active plan. Keep UI, media access, storage, inference and search responsibilities separate without unnecessary layers. Use standard maintained cryptographic libraries.

Do not add source-code comments. Put rationale in documentation. Use licensed or owned fixtures. Keep private photos, OCR, embeddings, keys, tokens, signing credentials, local SDK paths, downloaded corpora and raw logs out of commits.

Use the Windows build commands in [README.md](README.md). Complete the planned implementation pass before executing the full runtime and instrumentation test suites. For physical-device memory, battery, thermal and inference claims, record device and model details. Record task IDs, changed paths, build/test commands, results and outstanding dependencies in the handoff.
