# Agent handoff

Start with [README.md](README.md), then [planning overview](docs/README.md). This is the Android photo-library project, not CueSeal.

## Task control

- Use IDs and dependencies from [implementation checklist](docs/implementation-todo.md).
- Check actual files, device results and test evidence before declaring a task complete. The launch scaffold is the first Android toolchain check for PL014.
- Preserve user changes. The old project at `D:/secure audio` is separate; do not modify or import it as part of routine photo-app work.
- Record task IDs, changed paths, tested device/model/build versions, checks/results and remaining dependencies in each handoff.

## Fixed product requirements

- Android first. The client uses Kotlin and Jetpack Compose.
- Account-free local indexing/search works without uploading photos, OCR, embeddings or queries.
- Support LOCAL, CLOUD_FULL and CLOUD_REDACTED with explicit exact-artifact consent. Redaction failure must never trigger full-image upload.
- Ordinary cloud workers decrypt approved inputs. Do not claim server-blind computation or perfect redaction.
- Encrypt sensitive derived state, protect keys and handle edit/move/trash/delete/revoked access plus late-result races.
- Build the encoder/OCR baseline first. LLM/VLM comparison remains a required later phase; deployment and fine-tuning depend on evidence.

## Engineering

Follow [blueprint](docs/blueprint.md) boundaries. Keep Android UI, media access, storage, inference, key handling and transport responsibilities separate without unnecessary abstraction layers. Use standard maintained cryptographic libraries.

Do not add source-code comments. Put rationale in documentation. Use licensed/owned fixtures; keep private photos, OCR, embeddings, keys, tokens, signing credentials, local SDK paths, downloaded corpora and raw logs out of commits.

Use the Windows build and device commands in [README.md](README.md). Run meaningful tests appropriate to each change and validate on physical Android hardware for memory, battery and inference claims.
