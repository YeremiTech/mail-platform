# API examples

All requests come from a trusted backend with `X-Client-Id` and `X-Internal-Api-Key`. See [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) for the complete flows.

## Request a recovery code

```http
POST /api/v1/password-recovery/challenges
X-Client-Id: inventory
X-Internal-Api-Key: <inventory-secret>
Content-Type: application/json

{"subjectReference":"user-123","email":"registered@example.com"}
```

## Verify and consume

```http
POST /api/v1/password-recovery/challenges/{challengeId}/verify
Content-Type: application/json

{"code":"123456"}
```

```http
POST /api/v1/password-recovery/grants/consume
Content-Type: application/json

{"grantId":"UUID-from-verification","resetGrant":"token-from-verification"}
```

Include the same client credentials on both calls. The consumer project checks the returned user ID and changes its own password.

## Send a general email

The client must be granted `billing/invoice` through `CLIENT_TEMPLATE_ACCESS`.

```http
POST /api/v1/emails
Idempotency-Key: invoice-F001-123-first-send
Content-Type: application/json

{
  "subject": "Factura F001-123",
  "templateKey": "billing/invoice",
  "variables": {
    "customerName": "Cliente",
    "documentNumber": "F001-123",
    "amount": "S/ 25.50"
  },
  "recipients": [{"email":"client@example.com","type":"TO","displayName":"Cliente"}],
  "attachmentIds": [],
  "priority": "DOCUMENT"
}
```

For a duplicate invoice or boleta with a PDF, use `POST /api/v1/documents/copies` as shown in the integration guide.

## v0.10: least-privilege client provisioning

New tenant creation requires `permissions`; the implicit `*` grant is rejected. Existing tenants upgraded from V13 keep their current assignments until explicitly reviewed. Example administrative request:

```http
POST /api/v1/admin/clients
X-Client-Id: admin
X-Internal-Api-Key: ${ADMIN_API_KEY}
Content-Type: application/json

{"clientId":"accounting","displayName":"Contabilidad","requestsPerMinute":120,
 "ttlDays":30,"permissions":["EMAIL_SEND","EMAIL_READ","ATTACHMENT_WRITE"]}
```

Capture the returned `apiKey` in a secret manager. Rotate before expiry. Clients that need more permissions should receive only the additional named operations; use `PATCH /api/v1/admin/clients/{clientId}` after approval.
