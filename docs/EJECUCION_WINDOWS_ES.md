# Mail Platform v0.10.4: ejecutar en Windows sin Docker

## Requisitos

- JDK 25 en `JAVA_HOME`; verifica `java -version` y `javac -version` desde PowerShell.
- PostgreSQL con base `mail_platform` y usuario con permiso para ejecutar las migraciones de Flyway.
- RabbitMQ accesible (AMQP, normalmente puerto 5672).
- Servidor SMTP de prueba como Mailpit o un proveedor SMTP real autorizado.
- Python 3.11+ si deseas ejecutar los scripts de humo.

## 1. Base de datos

Crea la base de datos vacía con PostgreSQL. Si actualizas desde cualquier versión anterior, **no borres la base**: realiza un respaldo con Large Objects, prueba una restauración aislada y deja que Flyway aplique las migraciones faltantes hasta V17. Nunca edites las migraciones ya aplicadas.

## 2. Variables de entorno

En PowerShell, configura como mínimo las variables necesarias para tu infraestructura:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/mail_platform'
$env:DB_USERNAME = 'mail_platform'
$env:DB_PASSWORD = '<CONTRASEÑA_POSTGRESQL>'
$env:RABBITMQ_HOST = 'localhost'
$env:MAIL_HOST = 'localhost'
$env:MAIL_PORT = '1025'
$env:MAIL_FROM = 'no-reply@example.test'
$env:ADMIN_API_KEY = '<SECRETO_ALEATORIO_DE_32_BYTES_O_MAS>'
$env:API_KEY_PEPPER = '<OTRO_SECRETO_ALEATORIO_INDEPENDIENTE_DE_32_BYTES_O_MAS>'
$env:OTP_HMAC_SECRET = '<OTRO_SECRETO_ALEATORIO_DE_32_BYTES_O_MAS>'
$env:ALLOW_LEGACY_KEYS = 'false'
$env:DOCS_PUBLIC = 'false'
```

Genera dos claves AES-GCM independientes de 32 bytes (Base64). Por ejemplo, en PowerShell:

```powershell
$bytes1 = New-Object byte[] 32
$bytes2 = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes1)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes2)
$env:SENSITIVE_PAYLOAD_KEY = [Convert]::ToBase64String($bytes1)
$env:WEBHOOK_ENCRYPTION_KEY = [Convert]::ToBase64String($bytes2)
```

Para actualizar desde una versión anterior con mensajes cifrados existentes, **debes conservar la misma `SENSITIVE_PAYLOAD_KEY` original**. No generes otra clave para una base de datos que ya contiene mensajes sensibles sin realizar antes una migración criptográfica compatible.

## 3. Compilar y ejecutar

Desde la carpeta que contiene `pom.xml`:

```powershell
.\mvnw.cmd -B clean verify
java -jar .\mail-api\target\mail-api-0.10.4-SNAPSHOT.jar
```

Flyway aplicará automáticamente las migraciones pendientes al iniciarse. Si falla el arranque, comprueba las credenciales, la disponibilidad de PostgreSQL/RabbitMQ y la configuración SMTP en `mail-api/src/main/resources/application.properties`.

El arranque de API y workers se realiza en el mismo proceso de forma predeterminada. Para desplegarlos por separado, configura `APP_WORKER_ENABLED=false` únicamente en las instancias dedicadas a API. Mantén workers activos en al menos una instancia.

## 4. Verificación

Abre otra consola PowerShell con las variables de entorno y las credenciales correspondientes:

```powershell
$env:E2E_CLIENT_ID = 'inventory'
$env:E2E_API_KEY = '<CLAVE_LEGADA_SOLO_SI_ESTA_HABILITADA>'
py -3 .\scripts\e2e_smoke.py
py -3 .\scripts\upgrade_smoke.py
```

`e2e_smoke.py` prueba el cliente legado y exige `ALLOW_LEGACY_KEYS=true`; omítelo si has deshabilitado las claves legadas. `upgrade_smoke.py` da de alta un cliente temporal y prueba la nueva API, pero **también utiliza un cliente legado de prueba** para comprobar el aislamiento. Configura un entorno de pruebas independiente, nunca credenciales de producción. El workflow está configurado para ejecutar la validación integral en CI con PostgreSQL, RabbitMQ y Mailpit; los resultados de ejecución de v0.10.4 siguen pendientes de obtener.

> La configuración de ejemplo no es segura para producción. Gestiona claves y contraseñas en un gestor de secretos y prueba la restauración de respaldos antes de desplegar una actualización.

## 5. Verificación específica v0.10

Para nuevos clientes, el JSON enviado al administrador **debe** incluir `permissions`, por ejemplo `["EMAIL_SEND","EMAIL_READ"]`; los permisos `*` de instalaciones existentes no se revocan en la actualización. Antes de producción, desactivar las claves legadas una vez migrados todos los consumidores.

```powershell
py -3 .\scripts\test_version_contract.py
py -3 .\scripts\check_architecture.py
py -3 .\scripts\check_openapi_contract.py
```

Las pruebas completas de Flyway requieren que `MAIL_PLATFORM_SMOKE_DB_URL` apunte a una base PostgreSQL de pruebas y que `DB_USERNAME` tenga permiso `CREATE SCHEMA`. Para restauración aislada se requieren utilidades `pg_dump`, `pg_restore`, `psql`, `createdb`, `dropdb` (el script de verificación usa Bash; ejecutar con Git Bash/WSL o usar herramientas equivalentes manualmente). La campaña sintética exige un Mailpit local, habilitación explícita y cuotas adecuadas.

Nunca expongas `ADMIN_API_KEY`, `API_KEY_PEPPER`, claves AES ni secretos de clientes en documentación, repositorios o aplicaciones frontend.

## 6. Certificación adicional v0.10.4 sin Docker

Con PostgreSQL 17, RabbitMQ 4 y Mailpit locales, **no ejecutes** los ensayos de caos o carga contra una base de producción. La comprobación de publicación de OpenAPI y permisos requiere una API arrancada y credenciales de un cliente de ensayo y un administrador:

```powershell
py -3 .\scripts\check_operational_health.py
py -3 .\scripts\check_openapi_contract.py
py -3 .\scripts\e2e_smoke.py
py -3 .\scripts\upgrade_smoke.py
```

Los escenarios adicionales están documentados en `docs/CERTIFICACION_V0104_ES.md`. En Windows deben detenerse o iniciarse los servicios **manualmente**, usando copias descartables. La existencia de un script no prueba que los servicios se hayan recuperado.
