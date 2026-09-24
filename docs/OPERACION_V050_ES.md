# Operación: Mail Platform v0.5

## Variables de entorno

La aplicación continúa utilizando `application.properties` (no YAML), más `.env` local opcional. Es recomendable guardar los valores productivos en un gestor de secretos.

```properties
ADMIN_API_KEY=<secreto aleatorio independiente de más de 32 bytes>
API_KEY_PEPPER=<secreto aleatorio independiente de más de 32 bytes>
WEBHOOK_ENCRYPTION_KEY=<base64 de 32 bytes aleatorios>
ALLOW_LEGACY_KEYS=false
DOCS_PUBLIC=false
```

`ADMIN_API_KEY` es una credencial de bootstrap que debe estar disponible solo para administradores y no debe compartirse con sistemas consumidores. `API_KEY_PEPPER` es obligatoria para utilizar credenciales almacenadas en PostgreSQL; si se pierde, las claves persistentes dejan de validar. El cifrado del webhook usa una clave independiente de la de datos sensibles (`SENSITIVE_PAYLOAD_KEY`). Haga respaldos seguros y versionados de ambas claves antes de rotarlas; esta entrega no incluye rotación de datos cifrados automáticamente.

## Actualización desde v0.4.1

1. Realice y verifique un respaldo PostgreSQL (`pg_dump -Fc` y `pg_restore -l`). El servicio debe detenerse para una actualización sin eventos nuevos mientras se migran datos y credenciales.
2. Conserve V1–V4 sin editarlas. Flyway ejecutará V5–V9 en orden y agregará relaciones de adjuntos, programación, clientes, plantillas, campañas, webhook y cuotas.
3. Establezca `ADMIN_API_KEY`, `API_KEY_PEPPER` y `WEBHOOK_ENCRYPTION_KEY`. Arranque con `ALLOW_LEGACY_KEYS=true` únicamente durante la transición.
4. Cree los clientes por `POST /api/v1/admin/clients` con los encabezados `X-Client-Id: admin` y `X-Internal-Api-Key: <ADMIN_API_KEY>`. Guarde las claves devueltas en un vault. Las claves no pueden recuperarse por otro endpoint.
5. Migre uno por uno los consumidores a su clave de BD. Ajuste cuotas, configure plantillas mediante la API y, si utiliza webhooks, asigne primero un host de destino permitido con `PATCH /api/v1/admin/clients/{id}`.
6. Valide E2E con SMTP de pruebas, desactive claves legadas, retire los secretos legados y compruebe que ya no autentican.

## Webhooks

El cliente provisionado puede registrar **un** endpoint HTTPS. El administrador debe autorizar previamente un DNS exacto como `hooks.miempresa.com`; no se admiten puertos arbitrarios, IP literales ni redirecciones. El sistema verifica que el DNS no resuelva hacia rangos privados al registrar y antes de entregar; mantenga el dominio aprobado bajo su control para evitar DNS rebinding.

Cabeceras enviadas: `X-Mail-Event-Id`, `X-Mail-Timestamp` (segundos Unix) y `X-Mail-Signature: sha256=<HMAC_HEX>`. La firma se calcula como `HMAC-SHA256(secret_base64url, timestamp + "." + body_utf8)`. El secreto de suscripción se muestra solo al crear/rotar, y se almacena cifrado mediante AES-256-GCM.

El receptor debe usar comparación constante, tolerar eventos repetidos por `eventId`, validar que el timestamp no sea anterior a 5 minutos y devolver un HTTP 2xx exclusivamente después de persistir el evento. Los errores 5xx, 408 y 429 producen reintentos exponenciales (hasta ocho intentos). Otros 4xx envían el evento a `DEAD`; los errores `DELIVERING` quedan disponibles tras vencer el lease de 60 s.

Ejemplo de verificación en Python:

```python
import base64, hashlib, hmac, time

def verify(signature, timestamp, raw_body, secret_b64url):
    if abs(time.time() - int(timestamp)) > 300:
        return False
    secret = base64.urlsafe_b64decode(secret_b64url + '=' * (-len(secret_b64url) % 4))
    content = timestamp.encode('ascii') + b'.' + raw_body
    expected = 'sha256=' + hmac.new(secret, content, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature, expected)
```

## Monitoreo y recuperación

Los nuevos gauges `mail.messages.queued`, `mail.messages.retrying`, `mail.messages.processing` y `mail.outbox.dead` están disponibles en el endpoint Prometheus, que requiere autenticación interna. La cantidad de entregas de webhook pendientes/fallidas también se consulta por API. Defina alertas de crecimiento sostenido, volumen de `DEAD`, ausencia de entregas SMTP y tiempos de recuperación. Mantenga las métricas de alta cardinalidad fuera de Prometheus; utilice los identificadores de mensajes en logs de auditoría.

En caso de fallo definitivo de Outbox, revise los intentos SMTP antes de llamar `POST /api/v1/admin/outbox/{eventId}/retry`. Un evento `PUBLISHED` puede reaparecer desde el broker; el reclamo del worker reduce los duplicados, pero no puede garantizar entrega SMTP exactamente una vez.

Respaldos mínimos de PostgreSQL: copia diaria cifrada, almacenamiento externo, verificación periódica de integridad y ensayo documentado de restauración. El archivo de respaldo y las claves deben custodiarlos equipos separados. Para instalaciones de alto volumen evalúe cambiar el almacenamiento binario de adjuntos de PostgreSQL a object storage y habilitar antivirus y análisis de rebotes del proveedor.
