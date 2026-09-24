# Operación Mail Platform v0.6.0

## Pasos para entorno de desarrollo

1. Copiar `.env.example` a `.env`, reemplazar TODOS los secretos y configurar PostgreSQL, RabbitMQ y SMTP (Mailpit en pruebas).
2. Ejecutar `docker compose -f compose.dev.yml up -d` o iniciar esos servicios manualmente en Windows según `docs/EJECUCION_WINDOWS_ES.md`.
3. Con **JDK 25** ejecutar `./mvnw -B clean verify` (`.\mvnw.cmd clean verify` en Windows), después `./mvnw -pl mail-api -am spring-boot:run`.
4. Ejecutar `python3 scripts/e2e_smoke.py` y `python3 scripts/upgrade_smoke.py` con las credenciales del cliente de prueba; ambos requieren servicios de prueba operativos.
5. Confirmar que Flyway ha aplicado V1–V10 y que Swagger/API responde de acuerdo con la seguridad configurada.

## Producción

- Activar `SPRING_PROFILES_ACTIVE=production`, configurar `API_KEY_PEPPER`, `ADMIN_API_KEY`, `OTP_HMAC_SECRET`, `SENSITIVE_PAYLOAD_KEY` y `WEBHOOK_ENCRYPTION_KEY` independientes y secretos reales fuera del repositorio.
- Configurar ClamAV accesible exclusivamente desde las instancias API. El perfil producción fuerza el antivirus y prohíbe las claves legadas.
- Publicar la API únicamente mediante HTTPS en un proxy seguro. Asegurar SMTP con STARTTLS verificando el nombre del servidor y configurar SPF/DKIM/DMARC en el dominio remitente.
- Exponer `actuator/prometheus` solo a un scraper autenticado (headers internos); no abrir métricas a Internet. Conectar `ops/prometheus/mail-alerts.yml` al Prometheus del entorno e importar `ops/grafana/mail-platform-dashboard.json` en Grafana seleccionando un datasource Prometheus.
- Al arrancar, `mail_metrics_refresh_healthy` debería alcanzar 1. Si la métrica no aparece, activar las alertas de scrape. Inspeccionar Outbox DEAD excluyendo los eventos cancelados intencionalmente.
- Configurar respaldos externos cifrados, verificación de checksum, restauración trimestral en una base desechable y alerta de RPO/RTO. Los scripts no restauran automáticamente ni crean la base de destino.
- Hacer pruebas con un SMTP real y monitorizar rebotes/entregas mediante servicios externos cuando se requiera prueba de entrega.

## Limitaciones conocidas

- La detección del antivirus aplica al momento de subida. No reemplaza el sandbox de adjuntos, la política de contenido del correo ni el análisis del receptor.
- La aprobación del host del webhook y la inspección DNS reducen el riesgo SSRF, pero sin pinning de la resolución en la conexión subsiste riesgo DNS rebinding; usar listas de salida de red o proxy controlado.
- Los scripts no sustituyen un plan HA ni replicación PostgreSQL; se necesitan procedimientos operativos y simulacros adicionales.
- Los eventos de SMTP que llegaron al servidor antes de un corte de conexión pueden producir duplicados en reintentos; no hay garantía exactly-once.
