# Mail Platform v0.10.6 — Colección Bruno completa

Colección generada directamente a partir de los controladores de `mail-platform-v0.10.6-mejorado`.

## Cobertura

- 45/45 operaciones REST declaradas por los controladores de Mail Platform.
- Health, liveness, readiness y OpenAPI.
- Métricas Actuator/Prometheus.
- Flujo automático de aprovisionamiento de cliente y API key.
- Plantillas dinámicas y versionado.
- Adjuntos PDF y retención.
- Correos TO/CC/BCC, idempotencia, programación y cancelación.
- Copia de documento con PDF.
- Batches y campañas JSON/CSV.
- Recuperación de contraseña completa usando Mailpit para obtener el OTP.
- Suppressions.
- Webhooks.
- Endpoints públicos de unsubscribe y RFC 8058.
- Outbox administrativo, incluido el contrato negativo del retry cuando no existe un evento DEAD.

## Requisitos del backend

Antes de ejecutar la colección deben estar activos:

1. Mail Platform en `http://localhost:8080`.
2. PostgreSQL y RabbitMQ.
3. Worker habilitado (`APP_WORKER_ENABLED=true`).
4. Mailpit en `http://localhost:8025`.
5. `ADMIN_API_KEY` de al menos 32 bytes.
6. `API_KEY_PEPPER`, `OTP_HMAC_SECRET`, `SENSITIVE_PAYLOAD_KEY` y `WEBHOOK_ENCRYPTION_KEY` configurados.

Para el flujo de webhooks, el host configurado debe resolver a una IP pública. La colección usa `example.com` solo como valor inicial de demostración. Cambia `webhookAllowedHost` y `webhookEndpoint` por un endpoint HTTPS de prueba que controles si quieres validar entregas reales.

## Configuración en Bruno

1. Abre/importa esta carpeta como colección Bruno.
2. Selecciona el environment `Local`.
3. Cambia `adminApiKey` en `environments/Local.bru` por el mismo valor de `ADMIN_API_KEY` usado por Mail Platform.
4. Si la API o Mailpit usan otros puertos, modifica `baseUrl` y `mailpitUrl`.
5. Ejecuta **Run Collection**.

La primera petición administrativa genera un `testClientId` único usando la hora actual y guarda automáticamente `clientApiKey`, IDs y tokens mediante `bru.setVar()`. Por eso las peticiones posteriores se encadenan sin editar manualmente los identificadores.

## Orden de ejecución

1. Health y contrato.
2. Aprovisionamiento administrativo.
3. Plantillas.
4. Adjuntos.
5. Emails.
6. Copias de documentos.
7. Batches.
8. Campañas.
9. Password recovery + Mailpit.
10. Suppressions.
11. Webhooks.
12. Unsubscribe público.
13. Outbox administrativo.
14. Monitoring.
15. Cleanup.

## Consideraciones importantes

- La aplicación no expone un endpoint para eliminar un cliente; cada ejecución completa crea un cliente de prueba nuevo (`bruno-<timestamp>`). Esto evita colisiones en ejecuciones consecutivas, pero deja datos de prueba en la BD.
- `Retry missing dead event` espera HTTP 404 deliberadamente. Sirve para ejecutar y validar el contrato del endpoint sin fabricar un fallo real del Outbox.
- El flujo de password recovery requiere que RabbitMQ, worker y Mailpit estén funcionando. La colección espera 2.5 s antes de buscar el OTP. Si tu equipo es más lento, aumenta esa espera en `08 - Password Recovery/02 - Mailpit find recovery message.bru`.
- El upload y el import CSV usan `multipart-form`. En Bruno Desktop funcionan con `@file(...)`. Versiones concretas del CLI han tenido regresiones de multipart; para la ejecución visual consecutiva recomendada usa Bruno Desktop actualizado.
- Los endpoints públicos de unsubscribe se prueban con un token deliberadamente inválido porque la API responde de forma genérica y sin filtrar información. Una prueba real con token válido requiere una campaña MARKETING y `MARKETING_PUBLIC_BASE_URL` HTTPS configurado.

## Datos de prueba incluidos

- `files/sample.pdf`
- `files/campaign.csv`

Todos los correos de prueba usan el dominio `example.test` para evitar envíos accidentales a destinatarios reales cuando se trabaja con Mailpit.
