# Changelog

## 0.10.6-SNAPSHOT (fail-closed API security and CI/staging certification)

- Replaced the generic authenticated `/api/**` fallback with `denyAll()`. Added a per-method/controller route-coverage guard, MockMvc regression and live unknown-route permission check.
- Added an explicit production startup guard for insecure database/broker defaults, missing ClamAV, disabled SMTP TLS/identity checks, legacy keys, public API documentation and reused/placeholder encryption secrets. Included JUnit and JDK-only tests.
- Added a bounded RabbitMQ availability metric, alert and JUnit checks with synthetic Prometheus alert-rule coverage.
- Configured a weekly disposable-services CI rehearsal for two instances, RabbitMQ and SMTP interruptions, and small synthetic load; manual larger tests remain opt-in. Added a separate SMTP-outage/retry/recovery exercise with localhost-only and explicit approval safeguards.
- Added fail-closed JUnit/JaCoCo plus live-E2E evidence generation and a CI distribution artifact gated on both integration tests and the independent vulnerability scan. No production DKIM certification is implied.
- Conserved all eight Maven modules and the byte-identical Flyway V1–V17 migrations; preserved all existing REST endpoints and public status semantics. Full Java 25/build/infrastructure certification is still required.

## 0.10.4-SNAPSHOT (release integrity, critical test enforcement and opt-in operational rehearsal)

- Fixed stale OpenAPI version metadata by injecting the filtered Maven artifact version without processing other Spring environment placeholders.
- Added live OpenAPI comparison, comprehensive core-route/security checks, readiness requiring PostgreSQL/RabbitMQ, and management-endpoint permission probes.
- Required JUnit evidence now covers existing critical concurrency, large-object, isolation, SSRF, recovery and SMTP suites; JaCoCo XML remains fail-closed. Malformed commercial unsubscribe links are classified as permanent validation failures by the SMTP provider.
- Expanded the bounded synthetic campaign test to verify eventual SMTP acceptance, exact Mailpit recipient capture and transactional latency; added opt-in localhost-only two-instance and broker-outage/Outbox recovery exercises. They are NOT production resiliency certification and were not run in the editing environment.
- Updated Windows no-Docker deployment, staging verification procedures and release documentation. All 8 modules, existing public routes and Flyway V1–V17 remain intact.

## 0.10.3-SNAPSHOT (reproducible coverage, migration, SMTP and restore certification)

- Kept all eight modules, Flyway V1–V17 and public REST contracts unchanged. Corrected an obsolete integration assertion against the V17-removed `mail_attachment.content` column.
- Expanded integration testing to fresh PostgreSQL and migrations from V1, V12, V15 and V16 to V17, preserving old BYTEA bytes and already-streamed Large Objects; added explicit `lo_unlink`, rollback and concurrent reader/deletion checks.
- Added fail-closed CI checks for actual JaCoCo XML, missing critical classes/counters, and absent/skipped JUnit integration tests. Coverage thresholds remain requirements, not claimed results.
- Added Mailpit RAW-message RFC 8058 verification, positive/negative one-click requests and transactional-mail header absence; separate cryptographic `dkimpy` verifier for a real post-relay delivered `.eml`.
- Extended isolated PostgreSQL restore rehearsal to compare attachment bytes, not just row counts; exposed explicit liveness/readiness probes while keeping metrics protected.
- Recorded missing JDK 25, Maven, PostgreSQL and production DNS/relay as certification blockers for the current local environment.

## 0.10.2-SNAPSHOT (coverage, attachment streaming and RFC 8058 closure)

- Raised mandatory JaCoCo bundle gates to API 50%, application 60% and worker 65%, and added focused LINE/BRANCH gates for `SmtpMailProvider` and `JdbcAttachmentStorageAdapter`.
- Added V17 to migrate every legacy BYTEA attachment to PostgreSQL Large Objects, require `content_oid`, and drop the legacy `content` column; runtime attachment reads no longer materialize database blobs into `byte[]`.
- RFC 8058 `List-Unsubscribe` and `List-Unsubscribe-Post` headers are enabled by default for valid HTTPS marketing unsubscribe URLs. Explicit disable remains available for incompatible relays; production relays must DKIM-sign both headers.
- Added regression tests for default/disabled one-click behavior, Large Object storage streaming, mismatch rollback and V15→V17 content preservation.

## 0.10.1-SNAPSHOT (five audit hardening fixes; JDK 25 full build pending)

- Raised the API/application/worker JaCoCo minimum gates to 40%/55%/60%. These are provisional enforcement targets, **not measured coverage**; Java 25 Maven verification remains pending.
- Flyway V16 introduces PostgreSQL large-object-backed, chunked **new** attachments. Legacy `bytea` attachments stay readable and can be migrated in guarded batches; outgoing SMTP attachments now use verified disk spools and are removed after sending. Backup scripts explicitly include large objects.
- Marketing SMTP optionally advertises DKIM-dependent RFC 8058 `List-Unsubscribe` and `List-Unsubscribe-Post`; new public one-click POST accepts the mandatory form marker and GET stays read-only.
- HTTPS webhook transport pins a public IP validated immediately before connection, retains original-host SNI/certificate verification and prevents redirects and HTTP downgrade.
- Actuator metrics/prometheus now require dedicated `METRICS_READ` or bootstrap `ROLE_ADMIN`; existing `PERM_ALL` does not grant metrics access. Added focused unit/integration tests and offline transport tests.
- Historical v0.10 audit and validation reports retained in `docs/`. Full Java 25/PG/RabbitMQ/SMTP E2E and DKIM header verification remain outstanding.

## 0.10.0-SNAPSHOT (advanced hardening; full Java 25/infrastructure validation pending)

- Flyway V14 replaces the SQL default `*` with restricted `EMAIL_SEND,EMAIL_READ` for new clients only; previously assigned authorities are preserved. Admin client creation requires an explicit, allowlisted operation-permission set.
- Flyway V15 adds `mail_attachment.legal_hold`, `mail_attachment.retention_until`, cleanup index and administrator GET/PUT retention API. Orphan cleanup now respects holds, time-based retention and existing message/campaign references.
- Attachment signature and optional ClamAV scanning accept bounded streams; disk-backed multipart input reduces upload heap pressure. Database `bytea` persistence remains buffered (maximum 15 MB), a documented limitation.
- Request correlation IDs in responses and MDC; explicit OpenAPI authentication schemes and a contract smoke script.
- JaCoCo instrumentation and provisional module-specific coverage gates; dependency-security scan added to GitHub Actions. CI now uses Java 25, PostgreSQL, RabbitMQ, Mailpit and a version-independent packaged JAR name; includes a Prometheus alert-test definition.
- Adds authorization, migration, retention, request-ID and password-grant concurrency tests (infrastructure-backed suites await execution).
- Adds opt-in localhost-only campaign-load exercise, isolated PostgreSQL restore verification script and release consistency guard. Updated README, production/Windows instructions and audit/validation reports.
- Offline Java smoke and parser, architecture guard and simulated operational checks pass. Java 25 Maven build, JaCoCo, real PostgreSQL/RabbitMQ/SMTP, ClamAV, Prometheus alert tests and production validation **not executed** in the editing environment.

## 0.9.0-SNAPSHOT (operation-scoped clients; full build/integration unverified)

- Flyway V13 adds backward-compatible per-client operation permissions; existing clients retain `*`.
- Persistent API keys receive operation authorities and can be restricted independently for email, attachments, batches, campaigns, templates, suppressions, webhooks, recovery and document-copy operations.
- Client administration accepts permission sets and records permission changes in the existing administrative audit trail.
- Persistent credential authentication records `last_used_at` after a successful match.
- Offline source, feature and architecture checks pass after the changes; Java 25 Maven, PostgreSQL/Flyway, RabbitMQ and SMTP validation remain pending.

## 0.8.0-SNAPSHOT (public unsubscribe links; full build/integration unverified)

- Flyway V12: opaque, one-use, expiring public opt-out tokens; only SHA-256 digests are persisted.
- Marketing drainer injects a server-generated unsubscribe URL; HTML and plaintext delivery always include it.
- Public scanner-safe GET confirmation and transactional POST; tenant-scoped suppression and single-use semantics.
- Existing complaint/bounce reasons cannot be downgraded by a public unsubscribe.
- Expired-token cleanup, extra operational metrics/dashboard/alert, offline token tests, JUnit and upgrade HTTP/SMTP smoke scenarios.
- New marketing origin is mandatory for MARKETING only; full Java 25 build, migration and SMTP E2E are unverified.

## 0.7.0-SNAPSHOT (marketing consent and suppression; JDK 25 integration unverified)

- Flyway V11: isolated recipient opt-outs, marketing purpose and suppressed campaign states.
- Enforced explicit consent for marketing JSON and CSV, with suppression checks at stage/drain/SMTP.
- CRUD REST for tenant-specific suppressions, Spring starter additions and observability metrics.
- Conservative webhook IPv4/IPv6 public-address policy (deployment egress firewall still required).
- New JUnit and HTTP checks; offline JDK feature smoke added to CI.

## 0.6.0-SNAPSHOT (source improvements; full JDK 25 verification pending)

- CSV marketing/transactional campaign import, bounded parser, per-recipient consent checks and duplicate prevention.
- Optional fail-closed ClamAV attachment scanning, production profile and improved operational metrics.
- Admin audit V10 for client/key lifecycle changes and fix for Spring AOP-proxied credentials.
- Prometheus alert rules, Grafana dashboard, guarded PostgreSQL backup/restore scripts.
- Additional JUnit/CI checks, Spring starter CSV import and architecture import guard.

## 0.5.0-SNAPSHOT (source implementation; Java 25 build not yet verified)

- V5–V9: persistent clients, version-pinned templates, scheduled messages, staged campaigns, signed webhooks and daily quotas.
- New management and callback endpoints, stronger attachment validation, cancellation and operational metrics.
- Extended Spring Boot client, optional integration tests and upgrade documentation.
- Keeps v0.4.1 validation reports as historical records; see current VALIDATION_REPORT.md for test limitations.

## 0.4.1-SNAPSHOT

- Added processing-token checks to SMTP delivery completion, failure and retry transitions. Active workers renew their claims, and a recovered worker cannot overwrite a newer result.
- Fenced Outbox publication and failure updates by the publisher lease; terminal Outbox failure now updates the message in one transaction.
- Added PostgreSQL regression coverage for stale claims and Outbox leases.
- Added an HTTP → RabbitMQ → Mailpit smoke script and CI services for the full recovery and document-copy flows.

## 0.4.0-SNAPSHOT

- Corrected Spring Boot 4 entity-scan import so the complete reactor compiles.
- Bound recovery verification and grant consumption to the authenticated client.
- Stopped expired, revoked or missing OTP mail before SMTP submission and added project display names to the recovery template.
- Added a typed factura/boleta document-copy endpoint, PDF checks, shared PostgreSQL attachment content and a duplicate-document template.
- Added explicit client grants for general templates, consistent HTTP errors and more complete Spring Boot client methods.
- Confirmed RabbitMQ publications before marking Outbox events published, rejected idempotency-key content collisions and made batch enqueue atomic with a 100-recipient limit.
- Expanded unit tests and integration documentation.
- Added Spring Boot 4 Flyway auto-configuration, JDBC timestamp conversion and transaction-proxy fixes discovered by running against PostgreSQL.
- Added an optional PostgreSQL and MockMvc integration suite; the executable can run API-only instances with `APP_WORKER_ENABLED=false`.

## 0.3.0-SNAPSHOT

### Security
- Removed raw OTP from normal message variables.
- Added AES-256-GCM encrypted sensitive payload storage with expiry and purge.
- Bound API authentication to a client-specific credential and derived client identity from the authenticated principal.
- Added client ownership to attachments and cross-client checks.
- Restricted Swagger by configuration and protected monitoring endpoints.

### Reliability
- Added atomic worker claims to prevent concurrent duplicate SMTP sends from duplicated broker deliveries.
- Added atomic challenge/grant consumption.
- Made challenge consumption and grant creation transactional.
- Added priority ordering in the Outbox claim.
- Added separate worker pools for security/transactional/document/bulk traffic.
- Terminal Outbox publication failure now marks queued/retrying messages as failed.

### Maintainability
- Added real Spring Boot starter auto-configuration and typed client DTOs.
- Added regression tests, CI workflow and production Dockerfile.
- Added Flyway V3 security/concurrency migration.

## 0.2.0-SNAPSHOT
- Transactional Outbox.
- Persistent delivery-attempt audit and retry state.
- Stale processing recovery.

## 0.10.6-SNAPSHOT

- El contenedor de distribución activa `production` por defecto.
- El contrato OpenAPI se compara contra todas las operaciones REST descubiertas en los controladores, no contra una lista parcial.
- Se añadió `preflight_attachment_migration.py` para convertir adjuntos BYTEA a Large Objects en lotes antes de V17 en instalaciones grandes.
- El ensayo semanal de capacidad se elevó a 10 000 destinatarios sintéticos e incorpora límites de duración y heap JVM.
- Se añadieron pruebas de calibración de release para impedir regresiones en esos controles.
