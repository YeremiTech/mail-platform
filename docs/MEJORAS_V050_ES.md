# Mail Platform 0.5.0 — Implementación y límites

Esta versión conserva los ocho módulos Maven y los endpoints anteriores. Agrega cinco migraciones Flyway **V5–V9** (V5, V6, V7, V8 y V9), nuevos controladores y un cliente Spring ampliado. **No es una certificación de producción ni garantiza un 90 % de madurez:** esa cifra requiere ejecutar la batería de pruebas, la evaluación del proveedor SMTP y las pruebas operativas especificadas más abajo.

## Mejoras incluidas

| Área | Implementado en esta entrega | Pendiente de aceptación / extensión |
|---|---|---|
| Envío | Programación hasta 30 días y cancelación de mensajes QUEUED/RETRYING; idempotencia considera `scheduledAt`. | Verificar precisión de programación bajo carga; remitentes individuales por cliente y conectores SMTP alternativos. |
| Recuperación | Se conserva OTP HMAC, cifrado AES-GCM, throttling y tokens de un uso. | Pruebas de carga/concurrencia sistemáticas y políticas de respuesta genérica del sistema consumidor. |
| Adjuntos | Firmas PDF, PNG, JPEG, WebP; inspección ZIP de DOCX/XLSX y rechazo de macros detectables; UTF-8 en texto; referencias explícitas y limpieza de huérfanos. | Integración antivirus/ClamAV, almacenamiento de objetos y streaming bajo alta carga. |
| Seguridad | API clients persistidos, claves aleatorias HMAC-SHA256 + pepper, rotación, revocación, bloqueo, cuotas por minuto y por día. Bootstrap administrativo separado. | Vault/KMS, auditoría de acciones administrativas, pentest y desactivar definitivamente claves legadas tras migrar consumidores. |
| Colas | Outbox previo, cancelación transaccional, revisión de eventos DEAD y reintento administrativo con salvaguardas. | Pruebas de interrupción durante confirmaciones SMTP, métricas RabbitMQ y políticas operativas de DLQ de broker. |
| Arquitectura | Puertos existentes, nuevas funcionalidades en servicios aislados y migraciones versionadas. | Pruebas automatizadas de reglas arquitectónicas y despliegue de API/worker por separado en el entorno real. |
| Integración | Starter de Spring con lotes, campañas, intentos, plantillas, programación, cancelación y webhooks; eventos firmados HMAC y reintentos. | SDKs específicos en C#/Node/Python; pruebas de recepción de webhooks en HTTPS real. |
| Plantillas | CRUD por inquilino, revisiones inmutables, publicación, previsualización, reemplazo seguro `{{variable}}` y versión congelada al encolar. | Diseñador visual, importación/exportación y localización. |
| Masivos | Campañas de hasta 10 000 destinatarios por petición, staging duradero y drains de 100 por transacción, pausa por cuota individual, cancelación. | API de importación CSV en streaming, consentimiento/bajas y pruebas de 10k con infraestructura real. |
| Monitoreo | Gauges de colas y DEAD Outbox; seguimiento de webhooks con códigos HTTP e intentos. | Dashboards Grafana, OpenTelemetry/tracing de extremo a extremo y reglas de alerta desplegadas. |
| Pruebas | Nuevas pruebas de validación de adjuntos y pruebas de integración condicionales para auth, plantillas, campañas, cancelación y trigger de webhook. | Ejecutar Maven y CI en JDK 25, pruebas de carga, fallos, seguridad y SMTP real. |
| Producción | Documentación de migración sin perder datos, política parcial de limpieza, separación de credenciales y checklist operativo. | Backups/restauración ensayados, HA, estrategia de secreto/KMS, SPF/DKIM/DMARC y observación real de entregabilidad/rebotes. |

## Nuevos endpoints

- `POST /api/v1/admin/clients`, `GET /api/v1/admin/clients`: alta de clientes y consulta administrativa.
- `PATCH /api/v1/admin/clients/{id}`: habilitación, cuotas y host HTTPS autorizado para webhooks.
- `POST /api/v1/admin/clients/{id}/credentials`, `GET .../credentials`, `DELETE .../credentials/{keyId}`: emisión/rotación y revocación. La API devuelve la clave **solo una vez** al crearla.
- `GET /api/v1/admin/outbox/dead`, `POST /api/v1/admin/outbox/{eventId}/retry`: inspección y reintento controlado del Outbox DEAD.
- `GET /api/v1/templates`, `POST /api/v1/templates/{slug}`, `POST /api/v1/templates/{slug}/versions`, `GET .../versions`, `POST .../versions/{version}/publish`, `POST .../versions/{version}/preview`, `DELETE .../publication`.
- `DELETE /api/v1/emails/{id}` y `DELETE /api/v1/batches/{id}`; `POST /api/v1/emails` acepta `scheduledAt` ISO-8601.
- `POST /api/v1/campaigns`, `GET /api/v1/campaigns/{id}`, `DELETE /api/v1/campaigns/{id}`.
- `PUT /api/v1/webhooks/subscription`, `GET .../subscription`, `DELETE .../subscription`, `GET /api/v1/webhooks/deliveries`.

## Política de compatibilidad

`INTERNAL_API_KEYS` se mantiene mediante `ALLOW_LEGACY_KEYS=true` durante la migración y **solo** en modo de compatibilidad. Las credenciales en BD se autentican como `X-Client-Id` más `X-Internal-Api-Key` sin cambiar el contrato de los clientes existentes. Para producción, después de migrar las aplicaciones consumidoras, establecer `ALLOW_LEGACY_KEYS=false` y eliminar `INTERNAL_API_KEYS`. El ID `admin` está reservado exclusivamente a `ADMIN_API_KEY`, que no puede invocar las rutas de negocio normales.

## Límites intencionados

- `SENT` significa **aceptación por SMTP**, no entrega en inbox. Los webhooks `mail.sent`, `mail.failed` y `mail.cancelled` reflejan estados de la plataforma, no aperturas, clics ni rebotes del proveedor.
- Los webhooks son **at least once**: el receptor debe deduplicar por `eventId` y verificar la firma y una ventana temporal para rechazar replay. Las redirecciones HTTP no se siguen. El dominio receptor debe ser autorizado individualmente por el administrador y mantener DNS seguro.
- `DELETE` cancela mensajes que aún estén `QUEUED` o `RETRYING`. Un envío `PROCESSING` o aceptado por SMTP no puede recuperarse.
- Las cuotas por minuto y día se aplican a clientes provisionados en BD; **no** se aplican a clientes legados en `.env`. Los lotes usan cuota `BULK`, los envíos individuales/seguridad/documentos usan `STANDARD`. Campañas con cuota agotada se difieren al próximo día UTC individualmente.
- Se valida la estructura de archivos, pero una firma válida **no demuestra ausencia de malware**. Instalar un motor antivirus antes de admitir archivos no confiables en producción.
- La limpieza elimina exclusivamente archivos huérfanos, de al menos 24 h (48 h por defecto); los adjuntos referenciados por mensajes o campañas se conservan mientras dichas referencias existan. Se requiere política de retención integral y derecho de supresión por jurisdicción.

## Antes de declarar >=90 %

1. Ejecutar `./mvnw clean verify` con JDK 25 (y credenciales de prueba sólo para integración) y `scripts/e2e_smoke.py`, `scripts/upgrade_smoke.py` con PostgreSQL, RabbitMQ y Mailpit.
2. Ejercitar acceso entre inquilinos, key rotation, cuotas bajo concurrencia, webhook replay, DNS y TLS no confiables, contenido malicioso, condiciones de carrera y carga real de 10k destinatarios.
3. Validar recuperación desde una copia PostgreSQL y caída de cada dependencia; medir RPO, RTO y profundidad de colas; instalar alertas y dashboards.
4. Evaluar entregabilidad, configuración SPF/DKIM/DMARC, rebotes y política de suscripciones; ejecutar prueba de aceptación con el servidor SMTP real.
5. Establecer para **cada una de las 12 áreas** evidencia de criterios de aceptación; el promedio global no reemplaza áreas individuales por debajo del objetivo.
