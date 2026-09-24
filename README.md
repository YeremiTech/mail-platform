# Mail Platform

[![CI](https://github.com/YeremiTech/mail-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/YeremiTech/mail-platform/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-blue.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-multi--module-C71A36.svg)](https://maven.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791.svg)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600.svg)](https://www.rabbitmq.com/)

Reusable Spring Boot platform for transactional email, password-recovery OTP delivery, attachments and bulk mail.

Current iteration: **v0.10.6-SNAPSHOT**. Start with [the integration guide](docs/INTEGRATION_GUIDE.md) for password recovery and duplicate invoices/receipts; see [the multilingual guide](docs/INTEGRACION_MULTILENGUAJE_ES.md) for Python, Node.js and .NET examples.

## v0.10.6: default-deny API, production guard, CI evidence and resilience exercises

- All unreviewed `/api/**` operations are denied by default, including for existing wildcard and admin keys. A static route/permission check and MockMvc regressions protect future endpoints.
- The `production` profile now refuses unsafe default credentials, repeated or placeholder encryption keys, legacy API keys, public Swagger, disabled ClamAV and missing SMTP TLS guarantees. Production starts only with appropriately configured independent secrets and services.
- A weekly **disposable CI-only** workflow is configured to exercise two nodes, RabbitMQ outage/recovery, SMTP outage/retry/recovery and a bounded 200-recipient synthetic campaign. Optional large-scale exercises remain manual. A CI-verified JAR is published only when the build, real E2E evidence and the independent dependency vulnerability scan all pass.
- Prometheus includes a low-cardinality RabbitMQ availability gauge and a unit-tested alert. External DKIM, live Prometheus alerts and production failover require separate validation.
- [Certification and Windows guide v0.10.6](docs/CERTIFICACION_V0105_ES.md) · [Current audit](AUDIT_REPORT.md) · [Current validation](VALIDATION_REPORT.md). **No Java 25 Maven, database, SMTP or production tests are claimed as passed until actually executed.**

## Stack

- Java 25
- Spring Boot 4.1.1
- Maven multi-module
- PostgreSQL + Flyway
- RabbitMQ
- Thymeleaf
- SMTP provider
- Spring Security


## v0.10.4: contracts, fail-closed CI and opt-in operational verification (live certification pending)

- OpenAPI now reports the Maven artifact version dynamically; CI compares the live contract to the current POM and verifies core routes/auth headers.
- Readiness includes the database and RabbitMQ, while liveness remains a separate public probe. Live security tests verify that metrics remain inaccessible to anonymous and ordinary clients.
- Critical Java suites must actually execute: the CI guard fails on missing/skipped JUnit XML and JaCoCo reports. Invalid marketing unsubscribe links are treated as permanent SMTP validation errors.
- Optional, deliberately **disposable localhost-only** CI scenarios test 2-node concurrent idempotency, broker interruption with durable Outbox recovery, and synthetic campaigns of up to 10,000 recipients with SMTP acceptance, Mailpit capture and transactional latency measurement. **They have not been executed in this editing environment.**
- [Certification guide v0.10.4](docs/CERTIFICACION_V0104_ES.md) · [Audit](AUDIT_REPORT.md) · [Validation](VALIDATION_REPORT.md). Maven/JaCoCo, PostgreSQL, Mailpit, chaos and production DKIM **still require live execution**.

## v0.10.3: certificación ampliada (ejecución integral aún pendiente)

- [Guía de certificación v0.10.3](docs/CERTIFICACION_V0103_ES.md): matriz de migraciones V1/V12/V15/V16→V17, pruebas reales de rollback, borrado concurrente, respaldo/restauración de Large Objects, comprobación de cobertura XML y JUnit no omitido, SMTP RAW RFC 8058 y comprobación criptográfica DKIM para el relay real.
- CI con Java 25/PostgreSQL 17/RabbitMQ 4/Mailpit; `verify` debe superar JaCoCo y ejecutar integración. **Una configuración de gates no es evidencia de cobertura medida**, y Mailpit no es evidencia de firma DKIM de producción.
- V1–V17 y todos los endpoints públicos permanecen sin modificaciones. Se corrige la prueba antigua que consultaba `mail_attachment.content` después de que V17 la eliminase y se habilitan las rutas de readiness/liveness.

## v0.10.2: closure of coverage, streaming and RFC 8058 audit findings (Java 25 integration pending)

- Five source-level corrections: raise JaCoCo gates (`mail-api` 50%, `mail-application` 60%, `mail-worker` 65%) plus focused LINE/BRANCH gates for SMTP and PostgreSQL attachment storage; move attachment persistence to PostgreSQL large objects (V16–V17), migrate legacy BYTEA content in-database, use bounded streams end-to-end inside the application, and spool outgoing SMTP attachments to temporary files; enable RFC 8058 one-click marketing headers by default for valid HTTPS marketing links, retaining an explicit relay-compatibility opt-out; pin TLS webhook sockets to freshly validated public IPs to prevent DNS rebinding; protect Actuator metrics with dedicated `METRICS_READ` authority or `ROLE_ADMIN`.
- Retain V1–V16 and previous REST endpoints. V17 migrates all remaining legacy `bytea` attachments to PostgreSQL Large Objects inside the database, enforces `content_oid NOT NULL`, and removes the legacy `content` column. PostgreSQL custom-format backups include blobs explicitly. RFC 8058 one-click headers are enabled by default for valid HTTPS unsubscribe URLs. Production SMTP relays **must DKIM-sign both List-Unsubscribe headers**; `MARKETING_ONE_CLICK_ENABLED=false` is an explicit compatibility escape hatch, not the normal production setting. Network-limit management endpoints even when protected by permissions.
- Added focused JUnit/MockMvc/PostgreSQL tests. **Java 25 Maven verify, measured JaCoCo coverage, Flyway migration tests and external infrastructure E2E could not be run in the editing environment.** Configured coverage gates may initially fail and must be satisfied by implementing actual regression tests, not bypassed.
- [Mejoras v0.10.2](docs/MEJORAS_V0102_ES.md) · [Auditoría](AUDIT_REPORT.md) · [Validaciones](VALIDATION_REPORT.md).

## v0.10.0: security, evidence-driven CI and attachment retention

- Java 25/Maven reactor remains at eight modules. Existing Flyway V1–V13 remain unchanged; V14 defaults newly inserted SQL clients to `EMAIL_SEND,EMAIL_READ`, and V15 adds attachment `legal_hold` and `retention_until`.
- New persistent clients **must** be created with a named permissions set. An implicit `*` or an unknown permission is rejected. Clients that already had `*` retain it during this upgrade; progressively reassign least-privilege permissions after auditing their integrations.
- Uploads validate filename extension, MIME and content signature, use bounded input streams for signature and optional ClamAV scanning, and spool multipart input to disk rather than calling `MultipartFile.getBytes()`. The existing JDBC `bytea` storage adapter still buffers up to 15 MB during persistence: this release does **not** claim end-to-end streaming.
- New administrative `GET`/`PUT /api/v1/admin/attachments/{id}/retention` operations control retention/hold with an audit reason. Automated orphan cleanup excludes held and not-yet-expired content and still protects message/campaign references.
- Adds HTTP `X-Request-Id`, MDC correlation, explicit OpenAPI client headers, additional permissions/retention/Flyway/concurrency tests, JaCoCo reports and provisional coverage gates, OWASP Dependency-Check and improved CI. CI locates the actual executable JAR rather than assuming a previous version.
- New guarded scripts stage a **synthetic localhost-only** 10k-recipient campaign and verify restoration in an isolated PostgreSQL database. These are procedures for a controlled test environment, not claims of completed live tests.
- **Production readiness remains unverified:** use [v0.10 upgrade notes](docs/MEJORAS_V010_ES.md), [operational acceptance guide](docs/PRODUCTION_READINESS_ES.md), and [validation evidence](VALIDATION_REPORT.md) before deployment.

## v0.9.0: tenant operation permissions (full JDK 25 build validation pending)

- Flyway V13 adds per-client operation permissions without rewriting prior migrations. Existing clients receive `*` to preserve compatibility.
- Persistent API keys now obtain authorities from the authenticated client record. Supported permissions are `EMAIL_SEND`, `EMAIL_READ`, `EMAIL_CANCEL`, `ATTACHMENT_WRITE`, `BATCH_READ`, `BATCH_WRITE`, `CAMPAIGN_READ`, `CAMPAIGN_WRITE`, `TEMPLATE_READ`, `TEMPLATE_WRITE`, `SUPPRESSION_READ`, `SUPPRESSION_WRITE`, `WEBHOOK_READ`, `WEBHOOK_WRITE`, `PASSWORD_RECOVERY` and `DOCUMENT_SEND`.
- Administrative client create/update operations can assign a permission set. `*` cannot be combined with explicit permissions.
- Successful use of a persistent credential now updates `mail_api_credential.last_used_at`, improving credential lifecycle auditing.
- `SENT` remains the compatibility status persisted by the current schema and means the SMTP provider accepted the message; it is **not** proof of final recipient delivery. A future contract version should expose this transport semantic more explicitly rather than renaming the existing public status in place.
- Source/offline checks were rerun, but the full Java 25 Maven build and infrastructure integration remain unverified in the editing environment. See `VALIDATION_REPORT.md`.

## v0.8.0: secure public unsubscribe for marketing (full JDK 25 build validation pending)

- V12 stores only SHA-256 digests for short-lived, 256-bit, one-time unsubscribe links scoped to each client.
- Every **newly drained** MARKETING email receives a server-generated `unsubscribeUrl`. The worker appends a link in both HTML and text if absent from the template; no link means no SMTP handoff.
- Public `GET /api/v1/public/unsubscribe?token=...` shows a confirmation form **without changing data**; public form `POST` atomically consumes the link and registers `UNSUBSCRIBED`. The same generic result is returned for used, unknown or expired links.
- MARKETING requires `MARKETING_PUBLIC_BASE_URL=https://your-mail-domain.example` (HTTP permitted **only** on localhost for testing). Existing TRANSACTIONAL emails remain available without that setting.
- Added targeted JUnit and SMTP/HTTP upgrade tests, a full-source syntax parser and two additional Prometheus gauges. **No real Maven/JDK 25 build or integration test has been run in this editing environment.**

**Upgrade:** pre-existing v0.7 MARKETING emails that have already reached `mail_message` but lack a valid link are refused by the v0.8 worker. Drain or cancel and re-stage these messages before upgrading. Pending, undrained recipients will receive links when the v0.8 drainer runs. Take and validate a database backup before applying Flyway V12.

[Guía técnica v0.8 (español)](docs/MEJORAS_V080_ES.md) · [Verificación actual](VALIDATION_REPORT.md)

## v0.7.0 source improvements (full JDK 25 build validation pending)

- V11: tenant-owned recipient suppressions and explicit MARKETING/TRANSACTIONAL campaign purposes.
- JSON and CSV marketing consent requirements; exclusions are checked during staging, draining and immediately before SMTP.
- `POST/GET/DELETE /api/v1/suppressions`, enhanced Spring starter, and suppression metrics.
- Stronger public-IP screening for HTTPS webhook destinations (network egress policy still required).
- Additional JUnit/HTTP regressions and dependency-free JDK offline smoke script.

[Mejoras v0.7 en español](docs/MEJORAS_V070_ES.md) · [Validación actual](VALIDATION_REPORT.md) · [Operación v0.6, still applicable](docs/OPERACION_V060_ES.md)

## v0.6.0 source improvements (full JDK 25 build validation pending)

- CSV campaign import (`POST /api/v1/campaigns/import-csv`), UTF-8/RFC-4180 parser, strict bounds, duplicate rejection and explicit CSV consent for marketing requests.
- Optional ClamAV INSTREAM scanner (fail-closed when enabled); the `production` Spring profile requires antivirus and disables legacy API keys.
- V10 administrative audit table, same-transaction audit of client/credential mutations and `GET /api/v1/admin/clients/audit`.
- Additional low-cardinality service gauges, Prometheus alerts and an importable Grafana dashboard under `ops/`.
- Backup and guarded restore scripts, additional JUnit/HTTP regression tests, an architecture guard and an extended Spring Boot starter.

[Mejoras v0.6 en español](docs/MEJORAS_V060_ES.md) · [Operación v0.6](docs/OPERACION_V060_ES.md) · [Validación actual](VALIDATION_REPORT.md)

## v0.5.0 base functionality retained

- Persisted tenant accounts, expiring/rotatable API credentials, independent admin bootstrap, quotas per minute and per UTC day.
- Tenant-owned templates with draft revision, publish, preview and version pinning at queue time; dynamic rendering performs escaped placeholder substitution, not executable Thymeleaf.
- Time-based mail scheduling, cancellation of unclaimed messages/batches, durable Outbox DEAD inspection and manual retries.
- Asynchronously drained campaigns (up to 10,000 recipients per request), signed durable HTTPS webhooks and extended Spring Boot client.
- Attachment signatures, macro checks on Office ZIP files, safely tracked attachment references, orphan cleanup and operational queue gauges.

**Historical v0.5 docs:** [the Spanish upgrade report](docs/MEJORAS_V050_ES.md), [operations guide](docs/OPERACION_V050_ES.md), [Windows without Docker](docs/EJECUCION_WINDOWS_ES.md) and [current validation report](VALIDATION_REPORT.md). The complete Maven build and integration tests have not yet been executed on these modifications.

## Modules

- `mail-domain`: domain types.
- `mail-application`: use cases and ports.
- `mail-infrastructure`: PostgreSQL, Outbox, encrypted sensitive payloads, shared database attachment storage and RabbitMQ adapters.
- `mail-worker`: priority-separated delivery workers, retry/recovery and sensitive-payload cleanup.
- `mail-template-engine`: Thymeleaf rendering.
- `mail-provider-smtp`: generic SMTP provider.
- `mail-api`: REST API, security, OpenAPI and Actuator.
- `mail-client-spring-boot-starter`: auto-configured client for other Spring Boot services.

## Security model

API clients authenticate with both headers:

```text
X-Client-Id: inventory
X-Internal-Api-Key: <client-specific-secret>
```

`INTERNAL_API_KEYS` provides **temporary legacy compatibility** (disable after provisioning persistent clients):

```text
inventory=<secret>,billing=<secret>
```

The API derives `clientId` from the authenticated principal. Request bodies cannot impersonate another client.
Recovery verification and grant consumption are bound to the same authenticated client. General mail templates require an explicit `CLIENT_TEMPLATE_ACCESS` grant. A client cannot use the recovery or document-copy template through the general mail endpoint.

Password-recovery OTPs are HMAC-protected in `password_reset_challenge`. The raw OTP is never stored in `mail_message.variables_json`; it is stored separately in `mail_sensitive_payload` encrypted with AES-256-GCM, expires automatically and is purged after terminal delivery.

Generate the sensitive-payload key with:

```bash
openssl rand -base64 32
```

## Reliability model

```text
REST API
   |
   | single PostgreSQL transaction
   v
mail_message + mail_outbox_event + optional encrypted sensitive payload
   |
   v
Outbox publisher
   |
   v
RabbitMQ
   |
   +--> security workers
   +--> transactional workers
   +--> document workers
   +--> bulk workers
   |
   v
atomic mail claim -> provider -> delivery audit
```

The worker atomically claims only `QUEUED`/`RETRYING` messages, renews active claims and checks the processing token before SMTP submission and every final state change. A recovered worker cannot overwrite a newer worker's result. SMTP cannot provide a general exactly-once guarantee if a server accepts a message but the acknowledgement is lost; consuming projects should tolerate the rare possibility of a duplicate email.

## Password recovery

```text
POST /api/v1/password-recovery/challenges
POST /api/v1/password-recovery/challenges/{id}/verify
POST /api/v1/password-recovery/grants/consume
```

Challenge and grant consumption use conditional SQL updates. A challenge/grant can be consumed only once even when requests race.
The consuming project looks up the registered email and changes the user's password in its own database. Mail Platform does not manage user accounts. Expired or revoked OTP mail is not sent.

## Mail API

```text
POST   /api/v1/emails
GET    /api/v1/emails/{id}
GET    /api/v1/emails/{id}/attempts
DELETE /api/v1/emails/{id}
POST /api/v1/attachments
POST /api/v1/documents/copies
POST   /api/v1/batches
GET    /api/v1/batches/{id}
DELETE /api/v1/batches/{id}
POST   /api/v1/campaigns
POST   /api/v1/campaigns/import-csv
GET    /api/v1/campaigns/{id}
DELETE /api/v1/campaigns/{id}
```

Attachments are owned by `client_id` and stored in PostgreSQL for access by all API and worker instances. Document-copy requests require an owned PDF and a unique `Idempotency-Key`; `FACTURA` and `BOLETA` are supported.

## Spring Boot client

Add the starter and configure:

```properties
mail-platform.base-url=http://localhost:8080
mail-platform.client-id=inventory
mail-platform.api-key=${MAIL_PLATFORM_API_KEY}
mail-platform.connect-timeout=3s
mail-platform.read-timeout=15s
```

`MailPlatformClient` is auto-configured through Spring Boot `AutoConfiguration.imports`. It supports recovery request/verification/grant consumption, attachment upload, document-copy submission, mail status/attempts, batches, campaigns, versioned templates, scheduling, cancellation and webhook subscriptions.

The executable runs the API and workers together by default. Set `APP_WORKER_ENABLED=false` on API-only instances; keep at least one worker-enabled instance for Outbox publication and delivery.

## Development

Copy `.env.example` to `.env`, set real secrets, and start local services. The API loads this file from its working directory; do not commit it. The example database password matches the development Compose file.

```bash
docker compose -f compose.dev.yml up -d
```

Run in STS with JDK 25 or:

```bash
./mvnw clean verify
./mvnw -pl mail-api -am spring-boot:run
```

With the API running against the Compose services, run the HTTP → RabbitMQ → SMTP smoke test:

```bash
E2E_CLIENT_ID=inventory E2E_API_KEY='<matching development key>' python3 scripts/e2e_smoke.py
```

The smoke test checks the recovery email and one-time grant, plus a document email sent to two recipients with the exact PDF bytes. CI runs the same script with disposable PostgreSQL, RabbitMQ and Mailpit services. It is not a substitute for an acceptance test against the chosen production SMTP provider.

## Docker

```bash
docker build -t mail-platform:0.10.6 .
```

## Migrations

- `V1__baseline.sql`
- `V2__outbox_and_delivery_attempt.sql`
- `V3__security_concurrency_hardening.sql`
- `V4__shared_attachment_content.sql`
- `V5__clients_templates_and_quotas.sql`
- `V6__staged_campaigns.sql`
- `V7__signed_webhook_delivery.sql`
- `V8__daily_delivery_quotas.sql`
- `V9__campaign_quota_backoff.sql`
- `V10__admin_audit.sql`
- `V11__marketing_suppressions.sql`
- `V12__public_unsubscribe_tokens.sql`
- `V13__client_operation_permissions.sql`

## Remaining production work

The v0.9 additions are source-level improvements, **not evidence of 90% operational readiness**. This environment has JDK 21 but lacks JDK 25, Maven and PostgreSQL/RabbitMQ/SMTP. See [current validation](VALIDATION_REPORT.md) for the isolated checks actually executed and [v0.6 operational acceptance](docs/MEJORAS_V060_ES.md) for gaps in all twelve areas. Finish the full build, Flyway migrations, real antivirus and SMTP testing, load and chaos drills, backup restore tests, and external delivery/bounce monitoring before production.
