# Validation report — v0.4.1-SNAPSHOT

- Java 25 and Maven 3.9.14 were available in the review environment.
- `mvn -B -q clean verify` completed successfully after the implementation changes: 14 tests, 0 failures, 0 errors, 0 skipped.
- Unit tests cover sensitive OTP variable separation, idempotency-key collision, duplicate broker delivery, missing recovery-code rejection, document-copy PDF checks, tenant recovery isolation and general-template authorization.
- A temporary PostgreSQL 18 database passed the optional integration tests: Flyway V1–V4, shared PDF storage, mail/Outbox persistence and claims, attempt audit, retry scheduling, encrypted OTP storage, challenge verification, one-time grant consumption and cross-client rejection. A stale worker's token could not complete, fail or retry a newer claim; a stale Outbox lease could not mark an event published or failed. Terminal Outbox failure marked the queued mail failed.
- MockMvc tests passed HTTP authentication, multipart upload, document-copy submission and idempotency-conflict responses without opening a network port.
- The build packages the `mail-api` executable JAR and compiles the Spring Boot client starter.
- The new `scripts/e2e_smoke.py` passed Python syntax checking and is configured in CI with RabbitMQ and Mailpit. Docker and RabbitMQ are not available in this workspace, so the full script was not executed here. The Windows host could not open Tomcat's loopback selector for a real listening port; the Spring context and HTTP handlers were tested through MockMvc. This report does not claim an end-to-end broker/SMTP delivery or a successful GitHub Actions run.

See `docs/INTEGRATION_GUIDE.md` for staging acceptance steps.
