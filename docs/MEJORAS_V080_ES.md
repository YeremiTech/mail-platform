# Mail Platform v0.8 — mejoras de la siguiente fase

## Implementado en el código

1. **Baja pública segura.** `GET /api/v1/public/unsubscribe?token=<token>` devuelve únicamente un formulario de confirmación. La visita o el escaneo del enlace no anulan la suscripción. `POST` en formato `application/x-www-form-urlencoded` con `token=<token>` registra la baja. El resultado es genérico para tokens válidos, vencidos, usados o inexistentes: evita confirmar quién es un suscriptor. Ninguna de estas rutas expone datos del cliente ni direcciones de correo.
2. **Tokens opacos.** Se generan 32 bytes aleatorios criptográficamente seguros (256 bits), codificados en Base64 URL sin padding. Flyway V12 guarda únicamente el digest SHA-256, el cliente, la dirección normalizada y las fechas. Un `UPDATE ... RETURNING` consume el token de forma atómica antes de registrar `UNSUBSCRIBED` **en la misma transacción**. El token vence después de 90 días por defecto; configurable de 1 a 365 días. La tarea de mantenimiento elimina hasta 200 tokens antiguos por pasada, 30 días después de su uso o vencimiento.
3. **Integración automática.** En el drainer de campañas `MARKETING`, se genera un enlace por destinatario elegible. Se guarda en la variable reservada `unsubscribeUrl` **sobrescribiendo cualquier valor enviado por el cliente**. El worker incorpora el enlace en HTML y texto, sin duplicarlo cuando la plantilla ya lo contiene; si el correo carece de un enlace válido, falla antes de SMTP. Las campañas `TRANSACTIONAL`, los envíos individuales y la recuperación de contraseñas no cambian.
4. **Protección de exclusiones.** Una baja pública se aplica solo al cliente propietario del token. Las exclusiones existentes con motivo `COMPLAINT` o `BOUNCE` no se sobrescriben con `UNSUBSCRIBED`. Se mantienen las comprobaciones en staging, drainer y justo antes del envío SMTP.
5. **Monitoreo.** Métricas `mail_marketing_unsubscribe_tokens_active` y `mail_marketing_unsubscribe_requests_last_hour` (normalizadas por Prometheus), dos paneles Grafana y una alerta por volumen inusual. Sin etiquetas con direcciones ni identificadores de cliente.
6. **Pruebas.** Smoke sin dependencias externas para tokens, origen HTTPS, CSV, ClamAV simulado, footer e IP de webhook; analizador de sintaxis sobre todas las fuentes Java. Tests de integración con PostgreSQL y MockMvc para GET/POST, uso único, vencimiento, aislamiento entre clientes y conservación de denuncias; pruebas unitarias del worker para que no envíe campañas sin enlace; smoke de Mailpit ampliado.

## Configuración y despliegue

Variables nuevas en `.env`:

```properties
MARKETING_PUBLIC_BASE_URL=https://mail.tu-dominio.example
MARKETING_UNSUBSCRIBE_TOKEN_DAYS=90
```

El origen debe ser la URL pública HTTPS de Mail Platform; no se aceptan rutas arbitrarias, credenciales en URL, consultas ni fragmentos. Solo se permite HTTP en `localhost` o loopback en entornos de ensayo. Sin origen configurado, la API sigue atendiendo los correos transaccionales, pero rechaza la creación de campañas `MARKETING`. La ruta pública de baja debe quedar disponible mediante HTTPS en el balanceador o proxy y contar con un límite de solicitudes por IP en el borde de red.

**Actualizar desde v0.7:** primero detener y vaciar o cancelar los correos `MARKETING` que ya hayan sido encolados como `mail_message` y todavía no tengan `unsubscribeUrl`; el worker nuevo los rechazará si intentan salir sin enlace. Las filas `mail_campaign_recipient` todavía `PENDING` podrán recibir el enlace al ser procesadas por el drainer nuevo. Respaldar PostgreSQL y verificar la restauración en un entorno descartable; aplicar Flyway V12. No editar migraciones previas.

La plataforma no incorpora todavía headers `List-Unsubscribe` / `List-Unsubscribe-Post` para cancelación en un clic de los proveedores, ni procesamiento automático de rebotes, ni mecanismos de consentimiento fuera del registro que proporciona el sistema consumidor. El enlace no prueba por sí mismo consentimiento legítimo. `SENT` sigue indicando aceptación del proveedor SMTP, no llegada a bandeja.

## Verificación obligatoria

- Sin servicios: `bash scripts/offline_feature_smoke.sh`, `python3 scripts/check_architecture.py`, `python3 scripts/test_ops_scripts.py`.
- Con JDK 25 y Maven disponibles: `./mvnw -B clean verify` (Windows: `mvnw.cmd -B clean verify`).
- Con PostgreSQL 17, RabbitMQ y Mailpit: exportar `MAIL_PLATFORM_SMOKE_DB_URL`, `API_KEY_PEPPER`, `ADMIN_API_KEY` y `MARKETING_PUBLIC_BASE_URL=http://127.0.0.1:8080` **solo en pruebas locales**; ejecutar `python3 scripts/e2e_smoke.py` y `python3 scripts/upgrade_smoke.py` con el API activo.
- Comprobar Flyway V12 en una copia real de la base existente, `ddl-auto=validate`, descarga de plantilla SMTP, enlace de baja, GET sin cambios, POST con baja única y bloqueo de futuras campañas.
- Aún pendientes para producción: pruebas reales de carga (10 000 destinatarios), restauración real, alta disponibilidad, aislamiento de red de webhooks, proveedores y dominio autenticado mediante SPF/DKIM/DMARC, alertas desplegadas.

## Impacto por áreas (sin porcentajes no verificados)

| Área | Mejora de v0.8 | Pruebas que faltan |
|---|---|---|
| Envío | Footer de baja en HTML y texto para marketing | SMTP real y compatibilidad HTML de múltiples clientes |
| Seguridad | Token de 256 bits, uso único, no enumeración, validación de origen | Pentest y rate limit en proxy público |
| Arquitectura | Objeto puro de dominio para tokens y utilidad de aplicación para pie de correo; servicio transaccional | Maven completo con JDK 25 |
| Integración | Ruta pública para destinatarios, sin exponer claves API | Pruebas HTTP reales fuera de localhost |
| Masivos | Enlace por destinatario elegible sin salto de consentimiento | 10 000 envíos de carga; headers de baja en un clic |
| Monitoreo | Métricas y paneles de baja | Despliegue Grafana/Prometheus y verificación de alertas |
| Pruebas | JUnit, MockMvc, Mailpit y parseo de fuentes | Ejecutar suites integrales con infraestructura |
| Producción | Limpieza incremental de tokens y upgrade seguro | Ensayo de migración/backup y failover |
| Recuperación, adjuntos, plantillas y colas | Conservar comportamiento de v0.7 y añadir pruebas de regresión de integración | Validar operaciones y errores con servicios reales |
