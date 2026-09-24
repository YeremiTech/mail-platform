# Architecture — v0.4

## Boundaries

`mail-domain` has no framework dependency. `mail-application` contains use cases and ports. Infrastructure implements persistence, queueing, encryption and storage. API and workers are composition/transport layers.

## Accepted mail transaction

`JdbcTransactionalMailSubmissionAdapter` commits the message, its Outbox event and optional encrypted sensitive payload in one PostgreSQL transaction. Sensitive values are never part of `variables_json`.
New attachment bytes are stored in PostgreSQL by `JdbcAttachmentStorageAdapter`, making them available to every API and worker instance. Flyway V4 adds the shared content column.

## Delivery idempotency

RabbitMQ is treated as at-least-once. `JdbcMailProcessingAdapter` conditionally claims only `QUEUED`/`RETRYING` rows. A duplicate event that cannot claim the message exits without invoking the provider.

## Recovery security

- OTP hash: HMAC-based `TokenHasher`.
- Raw OTP mail variable: AES-256-GCM encrypted in `mail_sensitive_payload`.
- Challenge use: conditional SQL update.
- Reset grant use: conditional SQL update.
- Request throttle: PostgreSQL row lock per `(client_id, subject_reference)`.
- Request orchestration: Spring transaction covering throttle, challenge, mail, Outbox and sensitive payload.

## Client isolation

API identity is established from `X-Client-Id` + a client-specific `X-Internal-Api-Key`. Controllers do not accept `clientId` from user JSON. Attachments and returned mail/batch resources are checked against the authenticated client.
Recovery challenges and grants are also scoped to the authenticated client. General template access is configured per client; recovery and document copies have dedicated endpoints. RabbitMQ publisher confirmation is awaited before an Outbox event is marked published.

## Remaining boundaries

Generic SMTP remains the only provider adapter. The consuming project owns account lookup, password mutation and issued-document generation. Provider-specific delivery/bounce webhooks and high-volume asynchronous batch expansion remain future work.
