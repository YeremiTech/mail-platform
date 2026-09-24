# Mail Platform 0.7.0 — alcance y límites de la actualización

Esta entrega parte **exclusivamente** de `mail-platform-v0.6.0-mejorado.zip`. Conserva los ocho módulos y los endpoints previos. No acredita aún 90 % de madurez operativa: el entorno de esta edición no dispone de Maven/JDK 25, PostgreSQL ni RabbitMQ.

## Cambios entregados

1. **Exclusiones por cliente:** Flyway `V11__marketing_suppressions.sql` crea `mail_recipient_suppression` con clave `(client_id,email)`, motivo obligatorio y fechas. Las direcciones se almacenan normalizadas y el CRUD REST está restringido al cliente autenticado. Motivos: `UNSUBSCRIBED`, `COMPLAINT`, `BOUNCE`, `MANUAL`.
2. **Tipos de campaña:** `POST /api/v1/campaigns` admite `purpose=TRANSACTIONAL|MARKETING`. El valor omitido permanece `TRANSACTIONAL` por compatibilidad. Una campaña MARKETING requiere un cliente persistente y `consent: true` **en cada destinatario**; en CSV mantiene la columna `consent=true` obligatoria. La plataforma verifica la declaración suministrada, pero no demuestra por sí sola la obtención legal del consentimiento.
3. **Exclusión en tres puntos:** al crear una campaña comercial se marcan filas `SUPPRESSED`; el drainer vuelve a consultar la lista inmediatamente antes de encolar destinatarios pendientes; el worker consulta de nuevo antes del envío SMTP para campañas MARKETING ya encoladas. Si se detecta una baja en un correo ya reclamado, ese correo queda `FAILED` con el motivo de exclusión y no se envía. Existe una ventana residual si el destinatario se da de baja entre la última comprobación y la aceptación del SMTP: no existe una transacción distribuida con SMTP.
4. **Seguimiento:** `stagedRecipients` informa los destinatarios elegibles en la creación; `suppressedRecipients` informa los omitidos. La consulta `GET /api/v1/campaigns/{id}` devuelve la cantidad acumulada `suppressedRecipients`. El `totalRecipients` del resumen histórico de una campaña comercial representa destinatarios elegibles, no necesariamente el número de filas CSV originales. La migración permite `total_recipients=0` para campañas en las que todas las direcciones fueron excluidas.
5. **Integración:** `POST`, `GET`, `DELETE /api/v1/suppressions`, junto con `stageMarketingCampaign`, `suppressRecipient`, `getSuppressions` y `removeSuppression` en el cliente Spring Boot. El sistema consumidor es responsable de **verificar un consentimiento nuevo** antes de levantar una exclusión. No se expone un enlace público de baja con token en esta versión.
6. **Protección de webhooks:** verificaciones adicionales contra IP privadas, CGNAT, intervalos de documentación/pruebas y túneles IPv6. El destino debe seguir teniendo el host HTTPS aprobado por el administrador; se bloquean las redirecciones HTTP. Para impedir completamente SSRF vía cambios DNS entre verificación y conexión, es imprescindible restringir la salida de red desde el despliegue mediante firewall/proxy.
7. **Observabilidad:** gauges `mail_campaign_recipients_suppressed` y `mail_campaign_recipients_suppressed_last_hour`, panel Grafana y alerta opcional por volumen de exclusiones. No usar la métrica acumulada como tasa; el gauge de última hora refleja filas marcadas durante esa ventana.
8. **Regresión:** nuevas pruebas JUnit de aislamiento/consentimiento, de direcciones de webhook y de política del worker; smoke HTTP de exclusiones; smoke offline ejecutable con JDK sin servicios externos. CI ejecuta la verificación Maven, más los scripts y los smoke integrales cuando GitHub Actions se ejecute.

## Ejemplos REST

Registrar una baja desde el backend consumidor (autenticado con **sus** credenciales):

```http
POST /api/v1/suppressions
X-Client-Id: tu_cliente
X-Internal-Api-Key: <secreto_del_backend>
Content-Type: application/json

{"email":"ana@example.org","reason":"UNSUBSCRIBED"}
```

Crear una campaña comercial JSON:

```http
POST /api/v1/campaigns
X-Client-Id: tu_cliente
X-Internal-Api-Key: <secreto_del_backend>
Content-Type: application/json

{
  "subject":"Novedades de septiembre",
  "templateKey":"custom/boletin",
  "purpose":"MARKETING",
  "recipients":[
    {"email":"ana@example.org","displayName":"Ana","variables":{"name":"Ana"},"consent":true}
  ]
}
```

Si Ana está en la lista de exclusión, la API acepta la campaña pero notifica `stagedRecipients: 0` y `suppressedRecipients: 1`. Los mensajes transaccionales no se ven afectados. El cliente persistente y la plantilla publicada deben existir previamente.

Consultar exclusiones de **tu** cliente: `GET /api/v1/suppressions?limit=50`. Tras confirmar y registrar un nuevo consentimiento desde tu aplicación, se puede retirar: `DELETE /api/v1/suppressions?email=ana%40example.org`.

## Requisitos de validación por área (sin porcentajes inventados)

| Área | Mejoras de código en esta fase | Aún por comprobar/implementar |
|---|---|---|
| Correo | Bloqueo al enviar campañas comerciales ya encoladas | SMTP real y remitentes por cliente |
| Recuperación | No cambia la lógica OTP/HMAC ya presente | Pruebas concurrentes extremo a extremo con el sistema consumidor |
| Adjuntos | Se mantiene ClamAV, firmas y cuotas de v0.6 | Escáner ClamAV real, retención y streaming |
| Seguridad | Aislamiento de listas por cliente; rangos IP de webhooks restringidos | Pentest, secretos externos y egress de red para evitar DNS rebinding |
| Colas | Supresión al encolar y antes de la entrega | Fallos simulados y coherencia de campañas bajo carga |
| Arquitectura | Nuevo puerto `DeliveryPolicyPort` y adaptador JDBC | Certificar el build íntegro JDK 25 y el despliegue separado |
| Integración | API REST y cliente Spring Boot para consentimiento/exclusiones | Verificar SDKs no Java y contrato completo |
| Plantillas | Se mantienen versiones y permisos | Localización, estrés de variables y revisión de contenido comercial |
| Masivos | Exclusión durante creación, drainer y worker | 10 000 destinatarios bajo carga y enlace público de baja seguro |
| Monitoreo | Gauges, alertas y panel sobre exclusiones | Desplegar Prometheus/Grafana y probar alertas |
| Pruebas | Tests aislados y suites JUnit/HTTP adicionales | Ejecución Maven real con PostgreSQL, RabbitMQ y SMTP |
| Producción | Migración reversible solo mediante plan de restauración; controles conservadores | Simulacros de restauración, backups externos y redundancia |

## Verificación

- Sin infraestructura: `bash scripts/offline_feature_smoke.sh`, `python3 scripts/check_architecture.py`, `python3 scripts/test_ops_scripts.py`.
- Con **JDK 25** y dependencias accesibles: `./mvnw -B clean verify` (Windows `mvnw.cmd -B clean verify`).
- Con PostgreSQL 17, RabbitMQ y Mailpit de ensayo: `python3 scripts/e2e_smoke.py` y `python3 scripts/upgrade_smoke.py`.
- La primera ejecución de v0.7 aplica la migración V11; conservar un backup verificado y probar una migración sobre una copia de la base antes de actualizar producción.

**Semántica:** `SENT` indica aceptación SMTP, no entrega en bandeja de entrada; los webhooks siguen siendo `at least once`. Las exclusiones deben respetarse también en cualquier servicio externo que envíe campañas fuera de Mail Platform.
