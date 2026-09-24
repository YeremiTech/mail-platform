# Certificación reproducible — Mail Platform v0.10.3-SNAPSHOT

## Qué incorpora esta revisión

No se agregaron migraciones nuevas: **V1–V17 están intactas**. Se reforzaron las pruebas que antes tenían falsos positivos o eran solo comprobaciones estáticas:

1. **JaCoCo:** `scripts/check_coverage_reports.py` requiere el XML generado por Maven, valida el bundle de `mail-api` (LINE 50 %), `mail-application` (LINE 60 %) y `mail-worker` (LINE 65 %), y verifica cobertura focalizada de `SmtpMailProvider` (LINE 70 % / BRANCH 55 %) y `JdbcAttachmentStorageAdapter` (LINE 65 % / BRANCH 50 %). Los mínimos existentes del POM no se alteraron. Si faltan el XML, la clase, el contador o la cobertura, el proceso falla. `scripts/check_test_results.py` también rechaza pruebas de integración omitidas o ausentes. Esto **no significa que la cobertura ya se haya alcanzado**.
2. **Migraciones PostgreSQL:** `FlywayMigrationIntegrationTest` ejecuta base nueva y actualizaciones V1→V17, V12→V17, V15→V17 y V16→V17 en esquemas aislados; introduce bytes reales en `bytea`, preserva un Large Object de V16, comprueba `content_oid NOT NULL`, bytes y `lo_unlink` al borrar. La prueba de almacenamiento comprueba tenant, rollback sin objeto huérfano y lectura simultánea frente a borrado.
3. **Respaldo:** el ensayo `scripts/verify_pg_restore.sh` crea una base descartable, restaura con `pg_dump --blobs`, compara conteos, versión Flyway y huellas del contenido de cada adjunto. `REQUIRE_BLOB_FIXTURE=yes` obliga a tener al menos un archivo real en la fuente de prueba.
4. **SMTP:** con `MARKETING_PUBLIC_BASE_URL=https://mail.example.test`, `upgrade_smoke.py` verifica sobre el mensaje RAW recibido por Mailpit ambas cabeceras RFC 8058 y prueba GET sin efectos, POST inválido y POST válido/repetido. `e2e_smoke.py` exige que el correo transaccional no anuncie baja comercial. Un hostname de pruebas HTTPS solo se usa para **generar la URL**: el cliente de pruebas dirige las solicitudes de endpoint al API local, nunca intenta resolver el hostname ficticio.
5. **Relay DKIM:** `scripts/verify_dkim_relay.py` acepta el archivo `.eml` de un mensaje **recibido después de pasar por el relay real**. Exige una firma `d=` del dominio especificado, que el `h=` incluya ambas cabeceras y usa `dkimpy` para verificar criptográficamente la firma consultando DNS. Mailpit por sí solo no puede certificar esto porque captura correos antes de la firma DKIM del relay.
6. **Sondas de salud:** se autorizan únicamente `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness`; los otros endpoints de métricas conservan `METRICS_READ`/`ADMIN`.

## Ejecutar CI con JDK 25

El workflow `.github/workflows/ci.yml` instala Java 25 y levanta PostgreSQL 17, RabbitMQ 4 y Mailpit 1.31.1. Su orden es: pruebas offline, reglas Prometheus, `mvnw -B clean verify`, comprobación de pruebas y JaCoCo, arranque del JAR ejecutable, OpenAPI, SMTP transaccional, campañas con baja en un clic y restauración aislada de PostgreSQL mediante el cliente PG17 del contenedor oficial.

Los resultados reales quedarán en los artefactos de la ejecución GitHub Actions: `**/target/surefire-reports/**`, `**/target/site/jacoco/**`, `target/coverage-evidence.json` y `api.log`. **Si falla `mvn verify` no habrá certificación**, aunque los scripts de guardia y análisis sintáctico hayan pasado.

## Windows sin Docker

Instalar JDK 25, PostgreSQL 17, RabbitMQ 4 y Mailpit, además de Python 3.11+ y una conexión Maven capaz de descargar dependencias. Configurar las variables documentadas en `.env.example` con valores **de prueba** desde PowerShell; utilizar una base aislada con privilegio `CREATE SCHEMA`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/mail_platform_test'
$env:MAIL_PLATFORM_SMOKE_DB_URL = $env:DB_URL
$env:DB_USERNAME = 'mail_platform_test'
$env:DB_PASSWORD = '<test-password>'
$env:RABBITMQ_HOST = 'localhost'
$env:MAIL_HOST = 'localhost'
$env:MAIL_PORT = '1025'
$env:MARKETING_PUBLIC_BASE_URL = 'https://mail.example.test'
.\mvnw.cmd -B clean verify
py -3 scripts\check_test_results.py
py -3 scripts\check_coverage_reports.py
```

En otra consola iniciar el JAR generado, esperar a `/actuator/health` y ejecutar `py -3 scripts\e2e_smoke.py` y `py -3 scripts\upgrade_smoke.py`. Completar las variables de credenciales **solo de pruebas** indicadas en `.env.example`; no reutilizar claves de producción. Para restauración aislada usar `bash scripts/verify_pg_restore.sh` mediante Git Bash/WSL con el cliente PostgreSQL 17 y `RESTORE_VERIFY_APPROVED=yes`, `PGDATABASE`, `PGUSER`, `PGHOST`, `PGPASSWORD` y opcionalmente `REQUIRE_BLOB_FIXTURE=yes`. El script crea y elimina una base de ensayo, exige privilegio `CREATEDB` y realiza una copia física temporal con datos; no ejecutar contra producción sin autorización explícita.

## Verificar DKIM en un dominio y relay reales

1. Configurar el relay para firmar `List-Unsubscribe` y `List-Unsubscribe-Post` junto con los encabezados habituales de correo. Publicar la clave DKIM correcta del dominio remitente en DNS.
2. Enviar una campaña de prueba a un buzón controlado **externo al relay**, descargar el mensaje original recibido como `.eml` y conservarlo de forma privada. Mailpit no reemplaza esta prueba.
3. Instalar el verificador con `py -3 -m pip install dkimpy` (la verificación necesita resolver el TXT DNS del selector), y ejecutar:

```powershell
py -3 scripts\verify_dkim_relay.py C:\private\marketing-received.eml --expected-origin https://mail.example.com --expected-dkim-domain example.com
```

La salida `PASS` solo se emite si ambas cabeceras están presentes y una **firma DKIM criptográficamente válida** de `example.com` cubre ambas. Si el relay quita las cabeceras, cambia el contenido firmado, usa una clave DNS inexistente o firma un `h=` incompleto, falla. El `.eml` puede incluir tokens de baja y direcciones de correo: **no adjuntarlo a tickets públicos, CI ni repositorios**.

## Límites pendientes

No se ejecutaron aquí Maven 25 ni PostgreSQL/RabbitMQ/Mailpit: solo se agregaron sus pruebas reproducibles. El umbral JaCoCo puede fallar hasta ampliar las pruebas de los módulos que lo necesiten. La prueba DKIM final depende del dominio/relay del responsable del despliegue. Para sistemas empresariales críticos aún deben completarse las pruebas de carga, alta disponibilidad y fallos de infraestructura del informe general.
