# Certificación reproducible — Mail Platform v0.10.5

**El contenido del ZIP es código fuente y automatización, no un certificado de producción.** Hasta disponer de resultados reales de Java 25, PostgreSQL, RabbitMQ, SMTP y del relay de correo definitivo, debe conservarse el estado `NO CERTIFICADO` en `VALIDATION_REPORT.md`.

## 1. Qué cambió y qué se ejecuta automáticamente

- Cada controlador `/api/**` debe disponer de reglas por método y ruta; el fallback `denyAll()` bloquea rutas no revisadas. El guard `scripts/check_security_route_contract.py` coteja las 45 operaciones declaradas contra las reglas vigentes. Ejecutar también `ClientPermissionIntegrationTest` con PostgreSQL real.
- El perfil `production` tiene un guard de arranque que exige TLS SMTP, ClamAV habilitado con host, API keys legadas inactivas, Swagger privado, secretos independientes y cuentas de PostgreSQL/RabbitMQ distintas de las de desarrollo. Es una comprobación de configuración; no verifica la disponibilidad ni la seguridad de los servicios externos.
- GitHub Actions ejecuta automáticamente compilación Maven/JaCoCo, JUnit crítico con PostgreSQL, HTTP/SMTP con RabbitMQ y Mailpit, contrato OpenAPI, seguridad, comprobaciones de reglas Prometheus y restauración aislada. El artefacto `mail-platform-api-ci-verified` **solo** puede publicarse cuando el job principal y el análisis de vulnerabilidades finalizan satisfactoriamente.
- El ensayo semanal del workflow (domingo, 03:30 UTC) activa, **únicamente en los servicios descartables del job**, concurrencia con dos instancias, corte/recuperación de RabbitMQ, corte/recuperación de Mailpit y 200 destinatarios sintéticos. Estos ensayos también admiten activación manual. El job semanal está definido en código: no comienza a ejecutarse hasta que se publique esta versión en un repositorio con GitHub Actions habilitado.
- `scripts/generate_ci_evidence.py` rechaza cualquier certificación de CI sin reportes JUnit, cobertura JaCoCo y marcadores de los seis chequeos de infraestructura obligatorios. Distingue explícitamente los ensayos opcionales no ejecutados y nunca certifica DKIM externo ni el despliegue productivo.

## 2. Requisitos de CI / staging

Java **25**; Maven Wrapper con acceso a dependencias; Python 3.11+; PostgreSQL **17** (permiso para crear esquemas y una base aislada de restauración); RabbitMQ **4**; Mailpit (puertos SMTP 1025 / HTTP 8025); Docker opcional para CI, no obligatorio para Windows. ClamAV y un relay con DKIM para el perfil productivo.

El pipeline usa credenciales **solo de prueba**. En Windows sin Docker, instalar Java 25, PostgreSQL, RabbitMQ y Mailpit como procesos/servicios independientes y definir las variables documentadas en `.env.example` usando valores locales independientes. No reutilizar esos valores en producción. La opción `SPRING_PROFILES_ACTIVE=production` requiere TLS SMTP y ClamAV reales, por lo que no es apta para Mailpit sin TLS.

## 3. Pasos obligatorios en Java 25

En Linux, desde la raíz del proyecto:

```bash
java -version
bash ./mvnw -B clean verify
python3 scripts/check_test_results.py
python3 scripts/check_coverage_reports.py
python3 scripts/check_architecture.py
python3 scripts/check_security_route_contract.py
python3 scripts/test_version_contract.py
```

En Windows PowerShell con PostgreSQL, RabbitMQ y SMTP de pruebas ya operativos:

```powershell
java -version
.\mvnw.cmd -B clean verify
py -3 .\scripts\check_test_results.py
py -3 .\scripts\check_coverage_reports.py
py -3 .\scripts\check_security_route_contract.py
```

Las pruebas de integración usan `MAIL_PLATFORM_SMOKE_DB_URL`, `DB_USERNAME` y `DB_PASSWORD`. Las clases obligatorias comprenden Flyway V1/V12/V15/V16 → V17; rollback e integridad de PostgreSQL Large Objects; permisos/tenants; concurrente OTP; worker; SMTP; seguridad de rutas; configuración de producción y métrica RabbitMQ. No aceptar tests `skipped` ni reducir JaCoCo para aprobar el build.

## 4. Smoke HTTP, SMTP y recuperación en staging

Con API en `http://127.0.0.1:8080` y los servicios locales accesibles:

```bash
python3 scripts/check_operational_health.py
python3 scripts/check_openapi_contract.py
python3 scripts/e2e_smoke.py
python3 scripts/upgrade_smoke.py
```

Se exige liveness y readiness correctos, métrica protegida (401 sin credenciales, 403 cliente ordinario, 200 administrador), rutas desconocidas denegadas incluso con `*`, OpenAPI versión Maven, envío SMTP real con adjunto capturado y baja RFC 8058 mediante mensajes RAW en Mailpit. `SENT` significa aceptado por SMTP, **no** entregado finalmente al buzón.

Para ensayar cortes solo en un entorno descartable, las herramientas ejecutan tres fases y **no detienen servicios por sí solas**:

```bash
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py setup
# Detener exclusivamente RabbitMQ de ensayo
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py submit
# Reiniciar RabbitMQ
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py recover --timeout 300

MAIL_CHAOS_APPROVED=yes SMTP_OUTAGE_APPROVED=yes python3 scripts/smtp_recovery_smoke.py setup
# Detener exclusivamente Mailpit de ensayo
MAIL_CHAOS_APPROVED=yes SMTP_OUTAGE_APPROVED=yes python3 scripts/smtp_recovery_smoke.py submit
# Reiniciar Mailpit
MAIL_CHAOS_APPROVED=yes SMTP_OUTAGE_APPROVED=yes python3 scripts/smtp_recovery_smoke.py recover --timeout 400
```

La nueva prueba SMTP requiere un intento fallido transitorio mientras Mailpit está detenido, un único registro de aceptación tras la recuperación y una captura del destinatario sintético. Los ensayos de caos están restringidos explícitamente a API y Mailpit en `localhost`; nunca se ejecutan sobre servicios productivos.

Para las pruebas de dos nodos y carga controlada, consultar `scripts/multi_instance_smoke.py`, `scripts/load_smoke.py` y `docs/CERTIFICACION_V0104_ES.md`. El ensayo semanal es de **200 destinatarios**; un ejercicio manual de 10 000 requiere autorización, cuotas y recursos suficientes.

## 5. Backup, migraciones y alertas

- No editar ni reordenar migraciones V1–V17. Ensayar en bases aisladas la creación en limpio y las actualizaciones desde V1/V12/V15/V16. Comparar tamaño, checksum y contenido de cada adjunto histórico.
- Ejecutar `RESTORE_VERIFY_APPROVED=yes REQUIRE_BLOB_FIXTURE=yes bash scripts/verify_pg_restore.sh` con PostgreSQL real; el script emplea `pg_dump --blobs`, restaura en otra base, verifica filas y los bytes de los Large Objects y elimina la base temporal al terminar.
- Ejecutar `promtool check rules ops/prometheus/mail-alerts.yml` y `promtool test rules ops/prometheus/mail-alerts.test.yml`. La métrica `mail_rabbitmq_available` y su nueva alerta tienen un ensayo sintético, **no** una alerta real validada ante caída de producción. Para validación operativa, configurar Prometheus con credenciales `METRICS_READ`, red privada, Alertmanager y registrar una alerta observada con su duración y destino.
- El relay definitivo debe aportar un `.eml` de un mensaje de prueba realmente firmado: verificar que DKIM incluya `List-Unsubscribe` y `List-Unsubscribe-Post`, y configurar SPF/DMARC. Mailpit no firma DKIM en nombre del dominio de producción.

## 6. Criterio de entrega

Conservar XML Surefire/JaCoCo, logs depurados de credenciales, informe OWASP, mediciones de carga y latencia, salida de restauración, resultado de HTTP/SMTP, evidencias Prometheus y reporte `verification-summary.json`. Distinguir **aplicado en código**, **comprobado localmente**, **superado en CI** y **validado en el despliegue productivo**. No emitir porcentajes ≥90 % sin pruebas y umbrales medidos.
