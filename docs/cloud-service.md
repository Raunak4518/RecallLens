# Authenticated OCR job service

This implementation advances PL091–PL112 with a bounded HTTP job API, durable encrypted payloads, caller isolation, leases, expiry and deletion. The Android upload workflow remains a separate integration step.

The service wraps the existing v1 encrypted OCR protocol. It validates the worker identifier, HPKE authentication, rendered PNG bounds, image digest, mode, source identifier, task and policy before accepting a job. SQLite stores the encrypted request envelope and encrypted result. Job identifiers, caller IDs, state, digests and expiry times remain visible as operational metadata. The worker decrypts approved pixels in memory for inference. Its private key and bearer credentials are operational secrets stored outside the repository.

## API contract

Every route requires `Authorization: Bearer <token>`.

| Method and path | Behavior |
| --- | --- |
| GET /v1/worker-key | Return v1 worker identity, base64 X25519 public key, task and policy. Clients pin the approved worker identity through their configuration. |
| POST /v1/jobs | Submit the existing encrypted JSON envelope as the raw body. Return 202 with job ID, state, expiry and attempt count. Exact repeated bytes reuse the same job. A different request under the same ID returns 409. |
| GET /v1/jobs/{id} | Return the authenticated caller's status. On success, `result` contains the existing encrypted v1 response envelope. A foreign job returns 404. |
| DELETE /v1/jobs/{id} | Revoke the caller's job and erase queued/result ciphertext and receipt. Retain a bounded metadata tombstone until expiry, preventing accidental replay during that period. |

States are queued, running, succeeded, failed and deleted. Deleted jobs return 410 through GET and POST. Expired jobs return 404 and are purged by the worker/sweeper. A fresh submission after the retention window creates a new job, so clients must use fresh job IDs and obtain fresh approval after expiry rather than treating an old approved request as indefinitely reusable.

The service returns generic processing failures. It automatically retries a claimed job up to three times. A failed job remains inspectable until expiry. Creating another job requires a new ID. The current protocol supports OCR_ENGLISH with photo-export-v1. New workers include engine name, engine version, model-data SHA-256 and preprocessing policy inside the authenticated encrypted result. The Android client validates this bounded object and saves it with the encrypted OCR evidence. Older results lacking the object remain readable with unknown model provenance. A worker's report identifies what the configured worker says it executed. It does not establish attested execution.

## Bounds and concurrency

Defaults permit eight pending jobs and 64 retained jobs per caller, with 256 retained jobs total. The encrypted request body is at most 24 MiB, and the rendered PNG is at most 12 MiB and 1,600 × 1,600 pixels. Uploads acquire one of a bounded number of slots before body accumulation, have a 30-second body timeout, and return 429 when slots are occupied. A single process starts two engine workers. Each invocation opens an independent recognizer context.

Jobs and worker receipts use short SQLite transactions to claim work and conditionally commit results. Inference runs outside the write transaction. A lease owner fences stale completions after recovery. Deletion and expiry are checked inside the same transaction that would write the encrypted receipt. These checks also protect against deletion after a queue claim but before OCR begins. A sweep checks expiry once per second while the service runs. Expiry cannot stop an already executing native inference, but prevents its result from being persisted or served. [SQLite transaction behavior](https://www.sqlite.org/lang_transaction.html)

The lease defaults to 900 seconds without heartbeat. Work exceeding its lease fails completion and may be retried. A process crash leaves a recoverable durable queue claim. Recovery happens after lease expiry. Run one service process for the configured worker and upload bounds. Multiple processes would multiply those resource limits.

The database uses secure_delete for deleted records. The payloads are ciphertext throughout their disk lifetime. File system backups and snapshots require separate operational retention settings. SQLite metadata and a copied database alone do not reveal pixels or OCR text. The service key plus a queued envelope can decrypt its image.

## Setup and run

Use Python 3.13 with the pinned requirements. Generate a worker key using the existing CLI and keep its private-key path outside the repository.

```powershell
python -m pip install -r worker/requirements.txt
python -m worker.ocr_worker --private-key D:/private-photo-service/worker.key generate-key --public-key D:/private-photo-service/worker.pub
```

Provision random bearer tokens through a secure channel. Configure their hashes using PHOTO_LIBRARY_CALLER_HASHES, a JSON object mapping caller IDs to lowercase SHA-256 token hashes. The service accepts bearer tokens between 32 and 512 characters and stores only their hashes in configuration. Caller IDs contain 1–64 ASCII letters, digits, underscores, periods or hyphens. Each caller needs a distinct token. Token removal or rotation requires restarting this single-process service.

For an explicitly local development server, bind to loopback and pass the development flag.

```powershell
python -m worker.service --private-key D:/private-photo-service/worker.key --database D:/private-photo-service/jobs.sqlite3 --tessdata D:/private-photo-service/tessdata --host 127.0.0.1 --port 8088 --allow-local-http
```

External bindings require a TLS certificate and key.

```powershell
python -m worker.service --private-key D:/private-photo-service/worker.key --database D:/private-photo-service/jobs.sqlite3 --tessdata D:/private-photo-service/tessdata --host 0.0.0.0 --port 8443 --tls-cert D:/private-photo-service/server.crt --tls-key D:/private-photo-service/server.key
```

The CLI rejects local HTTP on an external interface. The application also checks the request scheme and immediate client address. Proxy headers are disabled. TLS termination at a reverse proxy needs a separately configured trusted deployment path. Access logging is disabled to reduce diagnostic exposure. FastAPI lifespan starts and stops the worker pool. [FastAPI lifespan documentation](https://fastapi.tiangolo.com/advanced/events/)

CLI options also configure worker count, TTL and retained/pending quotas. Dataset/model selection remains independent of the transport through the recognizer factory.

## Verification

```powershell
python -m pip install -r worker/requirements-test.txt
python -m unittest worker.test_ocr_worker worker.test_service -v
```

The host suite verifies parallel inference, duplicate requests, changed replay, legacy receipts, HPKE tampering, caller ownership, HTTPS enforcement and explicit loopback development, durable queued input across reconstruction, encrypted output, request size and job quotas, retry exhaustion, expiry, and deletion during inference. Root coordinates the Android end-to-end checks. Operational certificate provisioning, paid-service account flows, external deployment load tests and a model-provenance protocol update remain outside this host verification.
