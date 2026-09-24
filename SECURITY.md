# Security

Do not commit `.env` or production credentials.

Required secrets:

- `INTERNAL_API_KEYS`: comma-separated `clientId=secret` credentials; each secret at least 32 bytes.
- `OTP_HMAC_SECRET`: independent high-entropy HMAC secret.
- `SENSITIVE_PAYLOAD_KEY`: Base64 encoding of exactly 32 random bytes for AES-256-GCM.

Rotate client API keys and encryption/HMAC keys through your deployment secret manager. The current schema stores one encryption-key generation; rotation of `SENSITIVE_PAYLOAD_KEY` requires draining or re-encrypting pending payloads. Multi-key rotation with `key_id` remains future work.

Swagger should use `DOCS_PUBLIC=false` in production.

Client secrets belong only in consuming backends. Recovery requests must use the account's registered, verified email and the consuming backend must check the `subjectReference` returned when it consumes a reset grant. Configure `CLIENT_TEMPLATE_ACCESS` explicitly for general mail; recovery and document-copy templates use dedicated endpoints. New attachments are stored in PostgreSQL and must be included in database backup and retention plans.

The generic SMTP adapter provides transport submission, not proof of inbox delivery. Treat `SENT` as provider acceptance; provider-specific delivery/bounce webhooks are planned.
