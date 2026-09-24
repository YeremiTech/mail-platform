# Guía de integración

Mail Platform es una API privada entre servidores. Nunca coloques `X-Internal-Api-Key` en el navegador o una aplicación móvil. Cada proyecto consumidor conserva sus usuarios, contraseñas y documentos emitidos; Mail Platform administra códigos temporales y el envío de correos.

## Servicios y configuración

- Java 25, PostgreSQL, RabbitMQ y una cuenta SMTP funcional.
- Configura `INTERNAL_API_KEYS`, `OTP_HMAC_SECRET` y `SENSITIVE_PAYLOAD_KEY` con secretos aleatorios independientes.
- Configura `CLIENT_DISPLAY_NAMES` para identificar el proyecto en el correo de recuperación. Ejemplo: `inventory=Inventario,billing=Facturación`.
- Los endpoints generales de correo y lotes se deniegan por defecto. Autoriza plantillas con `CLIENT_TEMPLATE_ACCESS`, por ejemplo `inventory=billing/invoice,billing=billing/invoice`. Las plantillas de recuperación y duplicado solo se usan mediante sus endpoints específicos.
- Configura `MAIL_HOST`, remitente, TLS y credenciales reales antes de enviar fuera de desarrollo. `compose.dev.yml` inicia dependencias y Mailpit, no el proceso de la API.

La migración V4 guarda los nuevos adjuntos en PostgreSQL para compartirlos entre instancias de API y workers. Los adjuntos de v0.3 guardados en disco local no tienen `content` en la base: vuelve a subirlos antes de reenviarlos, o migra esos archivos por separado. Dimensiona espacio y copias de seguridad para los PDF.

## Recuperación de contraseña

1. El backend consumidor recibe la solicitud. Busca al usuario y utiliza solo el correo registrado y verificado de esa cuenta. Responde igual al público exista o no la cuenta.
2. El backend llama a `POST /api/v1/password-recovery/challenges` con `subjectReference` (ID estable del usuario) y el `email` verificado. Conserva el `challengeId` durante el flujo del navegador. El código de seis dígitos se envía por correo; la API nunca lo devuelve.
3. El usuario introduce el código en su aplicación. El backend llama a `POST /api/v1/password-recovery/challenges/{id}/verify` con `{"code":"123456"}`. Un código válido devuelve `grantId`, `resetGrant` y `expiresAt`.
4. El backend recibe la contraseña nueva, llama a `POST /api/v1/password-recovery/grants/consume` con `grantId` y `resetGrant`, y comprueba que el `subjectReference` devuelto sea el usuario que va a modificar. Luego calcula y guarda el hash de la nueva contraseña en su propia base de usuarios y revoca sus sesiones. Mail Platform nunca recibe ni almacena la contraseña.

Las tres llamadas llevan las credenciales del mismo cliente. Un código o autorización de otro proyecto se rechaza. Cada autorización se consume una sola vez. Si la arquitectura del proyecto lo permite, conserva `resetGrant` solo en su backend.

El límite de solicitudes es por `(clientId, subjectReference)`. El endpoint público del login también debe limitar solicitudes por cuenta y origen según el riesgo del proyecto consumidor. El código expira a los diez minutos y una solicitud nueva invalida el anterior. Un correo que sigue en cola al vencer se rechaza en vez de enviarse sin código.

## Reenvío de factura o boleta

El proyecto consumidor recupera el PDF ya emitido desde su sistema documental. Mail Platform no genera ni modifica el comprobante.

1. Sube el PDF mediante `POST /api/v1/attachments` como multipart en el campo `file`. Conserva el `id` devuelto.
2. Llama a `POST /api/v1/documents/copies` con un `Idempotency-Key` único para este reenvío solicitado:

```json
{
  "documentType": "FACTURA",
  "documentNumber": "F001-123",
  "customerName": "Cliente",
  "issuerName": "Mi Empresa",
  "amount": 25.50,
  "currency": "PEN",
  "recipients": [{"email": "cliente@example.com", "displayName": "Cliente"}],
  "attachmentIds": ["UUID-devuelto-al-subir"]
}
```

Usa `BOLETA` para una boleta. La petición exige al menos un PDF perteneciente al cliente autenticado. La API comprueba su cabecera, mantiene los bytes del documento y pone el correo en cola con la plantilla de duplicado. Acepta hasta 100 destinatarios y 10 adjuntos; cada archivo puede ocupar hasta 15 MB y el conjunto hasta 30 MB.

La respuesta HTTP 202 incluye el `id` del correo. Consulta `GET /api/v1/emails/{id}` y, cuando sea necesario, `GET /api/v1/emails/{id}/attempts`. `SENT` significa que el servidor SMTP aceptó el mensaje; no demuestra que llegó a la bandeja. Repetir la misma clave con datos idénticos devuelve el mismo correo; con datos distintos devuelve 409. Para una segunda copia solicitada deliberadamente, usa una clave **nueva**.

## Errores y despliegue

Los errores contienen `timestamp`, `code` y `message`. Estados principales: 400 entrada inválida; 401 credenciales ausentes o inválidas; 403 plantilla u operación no autorizada; 404 correo o lote inexistente; 409 conflicto de estado o de idempotencia; 413 archivo demasiado grande; 429 límite de recuperación; 500 fallo interno. Una falla de cola o SMTP puede ocurrir después de HTTP 202; consulta el estado y los intentos del correo.

Ejecuta `mvn clean verify` con JDK 25. La prueba opcional `PostgresFlowIntegrationTest` se activa con `MAIL_PLATFORM_SMOKE_DB_URL` y usa `DB_URL` y los secretos de configuración; apúntala solo a una base desechable porque inserta datos de prueba. CI crea su propia base PostgreSQL. Aplica las migraciones Flyway sobre una base PostgreSQL respaldada. Ejecuta API y workers con RabbitMQ y SMTP; el ejecutable actual incluye ambos componentes, o puedes usar `APP_WORKER_ENABLED=false` para instancias solo API. Prueba recuperación, reenvío de PDF, reintentos y acceso entre clientes en un entorno de pruebas antes de producción. La prueba PostgreSQL no sustituye un envío real por RabbitMQ/SMTP. Define copias de seguridad, retención, monitoreo, rotación de secretos y gestión de rebotes de acuerdo con el despliegue.

Para comprobar el recorrido completo en desarrollo, inicia los servicios de `compose.dev.yml` y la API con sus trabajadores. Configura `E2E_CLIENT_ID` y `E2E_API_KEY` con una credencial de desarrollo, y ejecuta `python3 scripts/e2e_smoke.py`. La prueba consulta Mailpit para comprobar el código recibido, el uso único de la autorización, los dos destinatarios del duplicado y los bytes del PDF adjunto. CI ejecuta la misma prueba con servicios desechables; en este equipo Windows todavía no pudo ejecutarse por falta de RabbitMQ y Mailpit.

Los trabajadores renuevan su reclamación cada 30 segundos por defecto; `DELIVERY_PROCESSING_STALE_SECONDS` debe ser al menos tres veces `DELIVERY_HEARTBEAT_INTERVAL_MS`. Una reclamación anterior no puede cambiar el estado de una nueva. Aun así, SMTP no ofrece una garantía general de entrega exactamente una vez: si el servidor acepta un correo y se pierde la confirmación, un reintento puede generar otro. Para tratar rebotes y entregas finales se necesita integrar las notificaciones del proveedor elegido.
