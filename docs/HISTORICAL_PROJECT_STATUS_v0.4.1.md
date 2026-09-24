# Project status — v0.4.1-SNAPSHOT

## Implemented

- Java 25 multi-module API, worker and Spring Boot client.
- PostgreSQL/Flyway persistence, transactional Outbox, RabbitMQ priority queues, SMTP sending, retry audit and stale-processing recovery.
- Per-client API credentials and ownership checks for mail, batches, attachments, recovery challenges and reset grants.
- Six-digit recovery OTP, HMAC storage, encrypted mail payload, request throttle, one-time verification and grant consumption.
- Recovery mail delivery guard: expired, revoked or missing codes are rejected before SMTP submission.
- Factura/boleta duplicate endpoint with typed fields, owned PDF header check, idempotency key and HTML template.
- Shared PostgreSQL attachment content through Flyway V4; no local-volume dependency for new uploads.
- Explicit per-client grants for general email templates; dedicated recovery/document templates cannot be selected through generic endpoints.
- Publisher confirms and routing-return checks before Outbox events are marked published.
- Processing-token fencing and active heartbeats prevent stale workers from overwriting a newer delivery state. Outbox state updates are fenced by the publisher lease.
- Batch creation is transactional with at most 100 recipients; status summaries are derived from message states.
- Structured HTTP errors, auto-configured client methods, development Compose file, Dockerfile and unit tests.
- Optional PostgreSQL/MockMvc integration tests run in CI against a disposable database. CI also has a full HTTP/RabbitMQ/Mailpit smoke test for recovery and document copies.

## External responsibility

Each consuming project must resolve its own account and verified email, store the new password hash, and retrieve the original issued PDF. Mail Platform does not contain a user directory or fiscal document generator.

## Production acceptance still pending

The build, unit suite and PostgreSQL integration tests pass on JDK 25. The RabbitMQ/Mailpit smoke test is configured but has not run in this workspace because those services are unavailable; no GitHub Actions result was observed here. Before production, run that test and test broker/database outages, retries, SMTP uncertainty, load, backups, storage retention, secret rotation, monitoring and delivery/bounce handling with the chosen provider. Existing v0.3 local-file attachments require a separate data migration or re-upload. SMTP cannot prove inbox delivery or guarantee exactly-once delivery if provider acceptance and acknowledgement become inconsistent.
