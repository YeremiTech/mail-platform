# Integración de Mail Platform desde cualquier backend

La interfaz pública es HTTP/JSON. **No envíes `X-Internal-Api-Key` desde Angular, React, una aplicación móvil ni JavaScript ejecutado en el navegador.** El backend consumidor debe mantenerla en su gestor de secretos y exponer sus propias operaciones autorizadas al usuario final.

## 1. Aprovisionamiento (administrador)

Con `ADMIN_API_KEY` configurada, un administrador registra al consumidor una sola vez. La respuesta incluye la nueva clave **una sola vez**; guárdala antes de cerrar la sesión:

```bash
curl -X POST http://localhost:8080/api/v1/admin/clients \
  -H 'X-Client-Id: admin' \
  -H "X-Internal-Api-Key: $ADMIN_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"erp","displayName":"ERP","requestsPerMinute":120,"ttlDays":90}'
```

Al rotar claves, solicita una nueva, actualiza el secreto en el consumidor y revoca la anterior. Un `clientId` diferente no puede consultar mensajes, plantillas ni archivos de otro cliente.

## 2. Petición HTTP común

El cliente `erp` debe tener publicada una plantilla propia llamada `welcome`. Todas las muestras siguientes muestran una petición transaccional, con idempotencia y variables por mensaje:

```http
POST /api/v1/emails HTTP/1.1
X-Client-Id: erp
X-Internal-Api-Key: <API_KEY_DEL_ERP>
Idempotency-Key: order-9e4e8d1a
Content-Type: application/json

{"subject":"Pedido recibido","templateKey":"custom/welcome","variables":{"name":"Ana"},"recipients":[{"email":"ana@example.test","type":"TO"}],"priority":"TRANSACTIONAL"}
```

HTTP `202` indica *solicitud aceptada*, no entrega al destinatario. Consulta `GET /api/v1/emails/{id}` o registra un webhook HTTPS con firma HMAC. El estado `SENT` indica aceptación del servidor SMTP.

## 3. Ejemplo Python (3.11+)

```python
import json
import os
from urllib.request import Request, urlopen

base = os.environ["MAIL_PLATFORM_URL"].rstrip("/")
body = {"subject": "Pedido recibido", "templateKey": "custom/welcome",
        "variables": {"name": "Ana"},
        "recipients": [{"email": "ana@example.test", "type": "TO"}]}
request = Request(base + "/api/v1/emails", data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json",
             "X-Client-Id": os.environ["MAIL_PLATFORM_CLIENT_ID"],
             "X-Internal-Api-Key": os.environ["MAIL_PLATFORM_API_KEY"],
             "Idempotency-Key": "pedido-9e4e8d1a"}, method="POST")
with urlopen(request, timeout=15) as response:
    print(response.status, response.read().decode())
```

## 4. Ejemplo Node.js (18+, backend)

```js
const response = await fetch(`${process.env.MAIL_PLATFORM_URL}/api/v1/emails`, {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-Client-Id": process.env.MAIL_PLATFORM_CLIENT_ID,
    "X-Internal-Api-Key": process.env.MAIL_PLATFORM_API_KEY,
    "Idempotency-Key": "pedido-9e4e8d1a",
  },
  body: JSON.stringify({
    subject: "Pedido recibido", templateKey: "custom/welcome",
    variables: { name: "Ana" },
    recipients: [{ email: "ana@example.test", type: "TO" }],
  }),
});
if (!response.ok) throw new Error(`Mail Platform HTTP ${response.status}`);
console.log(await response.json());
```

## 5. Ejemplo C# (.NET 8+)

```csharp
using System.Net.Http.Json;

using var http = new HttpClient { BaseAddress = new Uri(
    Environment.GetEnvironmentVariable("MAIL_PLATFORM_URL")!) };
using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/emails") {
    Content = JsonContent.Create(new {
        subject = "Pedido recibido", templateKey = "custom/welcome",
        variables = new { name = "Ana" },
        recipients = new[] { new { email = "ana@example.test", type = "TO" } }
    })
};
request.Headers.Add("X-Client-Id", Environment.GetEnvironmentVariable("MAIL_PLATFORM_CLIENT_ID"));
request.Headers.Add("X-Internal-Api-Key", Environment.GetEnvironmentVariable("MAIL_PLATFORM_API_KEY"));
request.Headers.Add("Idempotency-Key", "pedido-9e4e8d1a");
using var result = await http.SendAsync(request);
result.EnsureSuccessStatusCode();
Console.WriteLine(await result.Content.ReadAsStringAsync());
```

## 6. Operaciones adicionales

- Crear/publicar plantillas: `POST /api/v1/templates/{slug}` y `POST /api/v1/templates/{slug}/versions/{version}/publish`.
- Programar mensajes: `POST /api/v1/emails` con `scheduledAt` ISO-8601, en una ventana de 30 días.
- Cancelar lo aún no procesado: `DELETE /api/v1/emails/{id}`.
- Campañas: `POST /api/v1/campaigns` con hasta 10 000 destinatarios y `GET /api/v1/campaigns/{id}` para el progreso.
- Webhooks: el administrador asigna `webhookAllowedHost`, el cliente registra `PUT /api/v1/webhooks/subscription` con su URL HTTPS y guarda `signingSecret`. Debe comparar `X-Mail-Signature` calculado como HMAC-SHA256 sobre `X-Mail-Timestamp + "." + raw_body`, verificar una ventana temporal y deduplicar `eventId`.

El contrato OpenAPI en tiempo de ejecución se publica en `/v3/api-docs`; su acceso está restringido salvo que `DOCS_PUBLIC=true`. Los ejemplos aquí son guías de uso, no SDKs publicados ni evidencia de pruebas sobre cada runtime.
