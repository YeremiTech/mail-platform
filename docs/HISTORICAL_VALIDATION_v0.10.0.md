# VALIDATION REPORT — Mail Platform v0.10.0-SNAPSHOT

## Entorno y alcance

**Entorno de edición:** Linux; Java disponible OpenJDK 21.0.11, Java requerido 25; Maven global no instalado y Maven Wrapper no puede descargar dependencias por falta de resolución externa. No hay Docker, `psql` ni `promtool`. Tampoco se ejecutaron en este entorno PostgreSQL, RabbitMQ, SMTP/Mailpit ni ClamAV reales. No atribuir resultados de CI o producción a este entorno.

## Validaciones realmente ejecutadas (PASS)

| Comando | Resultado y alcance |
|---|---|
| `bash scripts/offline_feature_smoke.sh` | PASS: CSV y deduplicación, clamd INSTREAM contra servidor simulado, firmas de adjuntos por flujo, permisos de menor privilegio, filtro de IP privada, tokens de baja y footer obligatorio HTML/texto. Compila las clases compatibles con JDK local, **no** Spring Boot completo. |
| `java ... ParseAllJava` (invocado por el script anterior) | PASS: parseo sintáctico de **139** fuentes Java principales y de prueba. No resuelve dependencias ni ejecuta JUnit. |
| `python3 scripts/check_architecture.py` | PASS: guard de imports de **43** fuentes de dominio/aplicación; no prueba todas las interacciones en ejecución. |
| `python3 scripts/test_ops_scripts.py` | PASS: checksum de backup simulado, autorización de restauración y rechazo de manipulación, **sin** respaldo o restauración PostgreSQL reales. |
| `python3 scripts/test_version_contract.py` | PASS: ocho módulos POM en `0.10.0-SNAPSHOT`, migraciones V1–V15, sin ruta de JAR obsoleta en CI. |
| `python3 -m py_compile scripts/*.py`, `bash -n scripts/*.sh` | PASS: sintaxis Python y Bash. |
| Parser XML/JSON/YAML | PASS: POM raíz + ocho hijos; dashboard JSON; workflow CI y reglas/tests Prometheus YAML. YAML válido **no** es un test de promtool o GitHub Actions. |

## Pruebas implementadas pero no ejecutadas localmente

- `./mvnw -B clean verify` con JDK25: JUnit, contexto Spring Boot, compilación del reactor, JaCoCo gates y generación del artefacto ejecutable. **NO EJECUTADO**.
- `FlywayMigrationIntegrationTest`: bases aisladas V1→V15 y V12→V15, DEFAULT SQL y conservación de credenciales. **NO EJECUTADO** (requiere PostgreSQL y permiso CREATE SCHEMA).
- `ClientPermissionIntegrationTest`, `PasswordRecoveryConcurrencyIntegrationTest`, `AttachmentRetentionIntegrationTest`: autorización, consumo único concurrente y retención/limpieza real. **NO EJECUTADO**.
- `scripts/e2e_smoke.py`, `scripts/upgrade_smoke.py`, `scripts/check_openapi_contract.py`: API activa, RabbitMQ, Mailpit y base de datos. **NO EJECUTADO**.
- `ops/prometheus/mail-alerts.test.yml` con `promtool`, análisis OWASP/NVD con Maven, prueba ClamAV real, `scripts/load_smoke.py` (carga opt-in hasta 10k) y `scripts/verify_pg_restore.sh` (restauración aislada opt-in). **NO EJECUTADO**.
- Interrupciones de worker/broker/DB/SMTP; despliegue multiinstancia y recuperación/rollback; DNS SPF/DKIM/DMARC y TLS finales. **NO EJECUTADO**.

## Comandos para reproducir las pruebas pendientes

```bash
# En Java25, PostgreSQL/RabbitMQ/Mailpit de staging, NVD opcional:
export MAIL_PLATFORM_SMOKE_DB_URL='jdbc:postgresql://localhost:5432/mail_platform'
export DB_URL="$MAIL_PLATFORM_SMOKE_DB_URL"
export DB_USERNAME='<usuario-staging>'
export DB_PASSWORD='<obtener-de-gestor-de-secretos>'
# Configurar además RABBITMQ_*, MAIL_*, ADMIN_API_KEY, API_KEY_PEPPER,
# WEBHOOK_ENCRYPTION_KEY, SENSITIVE_PAYLOAD_KEY, OTP_HMAC_SECRET y cliente de prueba.
./mvnw -B clean verify
python3 scripts/check_openapi_contract.py
python3 scripts/e2e_smoke.py
python3 scripts/upgrade_smoke.py
promtool check rules ops/prometheus/mail-alerts.yml
promtool test rules ops/prometheus/mail-alerts.test.yml
# Ambas son explícitas y únicamente sobre staging aislado:
RESTORE_VERIFY_APPROVED=yes PGDATABASE=mail_platform_staging bash scripts/verify_pg_restore.sh
LOAD_SMOKE_ENABLED=yes LOAD_RECIPIENTS=10000 python3 scripts/load_smoke.py
```

El flujo de integración GitHub Actions está preparado para Java25, PostgreSQL 17, RabbitMQ y Mailpit, pero no se obtuvieron logs de una ejecución de este release. No se presume que los umbrales JaCoCo (API 10 %, aplicación 20 %, worker 15 %) estén alcanzados; son valores iniciales que deberán aumentarse después de medir cobertura real.

## Resultado técnico

**Aprobados:** únicamente los checks locales anteriores. **Pendiente de certificación:** build Java25 + integración y operación. Ningún porcentaje de madurez final >=90 % ha sido demostrado. El release debe pasar la matriz completa de `docs/PRODUCTION_READINESS_ES.md` antes de uso empresarial crítico.

---

## Histórico: validación de la iteración v0.9.0

# VALIDATION REPORT — Mail Platform v0.9.0-SNAPSHOT

## Entorno disponible

- Sistema de edición Linux.
- Java disponible: OpenJDK 21.0.11.
- Java requerido por el proyecto: 25.
- Maven global: no instalado.
- Maven Wrapper presente en el proyecto, pero la descarga de Maven no pudo realizarse por falta de resolución de red hacia `repo.maven.apache.org`.
- PostgreSQL, RabbitMQ, Mailpit/SMTP y ClamAV reales no se iniciaron en este entorno.

## Validaciones ejecutadas y resultado

### 1. Smoke offline de funcionalidades

Comando:

```bash
bash scripts/offline_feature_smoke.sh
```

Resultado: **PASS**.

Evidencia reportada por el script:

- CSV y deduplicación.
- protocolo ClamAV contra servidor simulado.
- política de IP privadas para webhooks.
- token opaco de baja.
- origen HTTPS.
- pie obligatorio HTML/texto para marketing.
- parseo sintáctico de fuentes Java y pruebas.

Salida relevante:

```text
PASS: CSV, clamd, private-IP policy, opaque opt-out token, HTTPS origin, mandatory HTML/text footer
PASS: syntax parsed 129 Java source/test files
```

### 2. Regla de arquitectura

Comando:

```bash
python3 scripts/check_architecture.py
```

Resultado: **PASS**.

```text
PASS: checked 43 domain/application sources against module dependency rules
```

### 3. Scripts operativos

Comando:

```bash
python3 scripts/test_ops_scripts.py
```

Resultado: **PASS**, con herramientas PostgreSQL simuladas.

```text
PASS: offline backup checksum, restore authorization and tamper rejection
```

Este resultado no equivale a una restauración de PostgreSQL real.

### 4. Integridad de POM, scripts, dashboards y migraciones

Se validó:

- XML de `pom.xml` raíz y de los 8 módulos.
- bytecode sintáctico de scripts Python mediante `compileall`.
- sintaxis shell mediante `bash -n`.
- JSON de `ops/`.
- secuencia de migraciones Flyway V1..V13.

Resultado: **PASS**.

### 5. Inventario estático

- Fuentes Java principales: 114.
- Archivos de pruebas Java: 13.
- Controladores REST: 13.
- Migraciones Flyway: 13.
- Pruebas con `@Disabled`: 0.

### 6. Intento de compilación Maven

Primer intento:

```bash
./mvnw -q -DskipTests compile
```

Resultado: **NO EJECUTADO** por permiso de ejecución del wrapper extraído en Linux (`Permission denied`). Esto es una característica del modo de extracción/permiso del entorno y no prueba un defecto del proyecto en Windows.

Segundo intento:

```bash
bash mvnw -q -DskipTests compile
```

Resultado: **BLOQUEADO POR ENTORNO**.

```text
curl: (6) Could not resolve host: repo.maven.apache.org
```

Además, el Java disponible es 21 mientras el reactor requiere Java 25. Por estas razones no se presenta la compilación Spring Boot como superada.

## Validaciones no ejecutadas

- `./mvnw -B clean verify` con JDK 25.
- JUnit/Spring Boot/MockMvc reales.
- JaCoCo real.
- Flyway contra PostgreSQL real.
- Upgrade V12 -> V13 en una base existente.
- RabbitMQ real.
- Mailpit y SMTP real.
- ClamAV real.
- prueba de 10 000 destinatarios.
- interrupción y recuperación de PostgreSQL/RabbitMQ/SMTP/workers.
- restauración PostgreSQL real.
- TLS externo y validación de dominios remitentes.
- comportamiento real de SPF/DKIM/DMARC, que depende del dominio/proveedor.
- alertas Prometheus/Grafana provocadas contra servicios en ejecución.

## Validación específica de los cambios v0.9

### Permisos por operación

Verificación realizada:

- `V13__client_operation_permissions.sql` existe y la secuencia V1..V13 es consecutiva.
- El parser Java acepta los cambios de `ApiKeyFilter`, `SecurityConfiguration`, `PersistentClientCredentials` y `ClientAdminController`.
- La arquitectura de dominio/aplicación sigue pasando el guard existente.
- No se modificaron V1–V12.

Pendiente:

- Aplicar V13 en PostgreSQL real.
- Ejecutar pruebas MockMvc de 200/403 por cada permiso.
- Verificar actualización concurrente de `last_used_at` y cuotas con múltiples instancias.

## Comandos recomendados para certificación en Windows/JDK 25

```powershell
java -version
.\mvnw.cmd -B clean verify
.\mvnw.cmd -pl mail-api -am spring-boot:run
```

Con PostgreSQL, RabbitMQ y Mailpit configurados:

```powershell
python scripts\e2e_smoke.py
```

Después de V13, comprobar al menos:

1. cliente con `EMAIL_SEND` puede hacer `POST /api/v1/emails`;
2. el mismo cliente recibe 403 al administrar templates si no tiene `TEMPLATE_WRITE`;
3. cliente con `*` conserva el comportamiento v0.8;
4. cliente deshabilitado no autentica;
5. credencial revocada o expirada no autentica;
6. `last_used_at` cambia tras autenticación válida;
7. aislamiento por `client_id` sigue impidiendo leer/cancelar recursos ajenos.

## Estado de validación

La fuente modificada supera las validaciones offline disponibles. El build completo y la certificación operativa permanecen pendientes por restricciones concretas del entorno descritas arriba. No se ocultan ni se contabilizan como superadas.
