# Mail Platform v0.10.1 — cinco correcciones de auditoría

Esta iteración modifica **el proyecto existente**, mantiene los ocho módulos y los contratos REST previos, conserva V1–V15 intactas y añade V16. No se ha ejecutado una compilación Maven íntegra con Java 25 en el entorno de edición.

## 1. Cobertura y calidad

Los controles `jacoco:check` del ciclo `verify` ahora exigen cobertura de líneas **API 40 %, aplicación 55 % y worker 60 %**. Se han incorporado pruebas para el disco temporal SMTP, cabeceras comerciales, permisos de métricas, endpoint de baja en un clic, política de DNS mixto, transporte HTTPS fijado y almacenamiento PostgreSQL LO. Son **umbrales provisionales, no cobertura medida**. Si el pipeline falla, se deben añadir pruebas de comportamientos críticos hasta satisfacerlos: no excluir código o desactivar `jacoco:check` para obtener verde artificial.

## 2. Adjuntos mediante streams en PostgreSQL

Para adjuntos **nuevos**, `JdbcAttachmentStorageAdapter` escribe por bloques de 32 KiB a `pg_largeobject` dentro de una transacción, calcula SHA-256 y guarda el OID en `mail_attachment.content_oid`. Un tamaño declarado diferente del transmitido provoca rollback; los registros anteriores con `content` (`bytea`) permanecen legibles. La lectura obtiene un lock `FOR SHARE` hasta cerrar el stream del Large Object. `MailDeliveryWorker` consume un stream por adjunto, escribe archivos temporales limitados y comprueba longitud y SHA-256; `SmtpMailProvider` emplea `FileSystemResource`; el worker intenta borrar los archivos en `finally`, aun cuando falle SMTP.

**Antes de desplegar V16:** ejecutar y validar `scripts/backup-postgres.sh` con `pg_dump --blobs`, restaurar sobre una BD aislada y ensayar envío con una copia de los datos de producción. Para migrar bytea histórico, ejecutar `ops/sql/migrate_legacy_attachment_bytea.sql` en pequeñas transacciones repetidas. No migra adjuntos con `legal_hold=true`. Las copias futuras deben incluir **tanto tablas como Large Objects**. Vigilar espacio de disco del worker, espacio de BD, vacuum y conexiones sostenidas durante streaming. El almacenamiento usa una conexión activa hasta cerrar cada stream; no devolver streams sin cerrar.

## 3. Bajas comerciales de un clic

El correo visible continúa mostrando el enlace de confirmación `GET /api/v1/public/unsubscribe?token=...`; GET no tiene efectos. Se añaden `GET` (vista sin efectos) y `POST /api/v1/public/unsubscribe/one-click?token=...` para proveedores compatibles; el POST requiere el campo `List-Unsubscribe=One-Click`. La operación consume el token de uso único y crea la supresión del cliente. Solo los correos MARKETING pasan su URL al proveedor; los TRANSACTIONAL no.

`MARKETING_ONE_CLICK_ENABLED=false` por defecto. Activarlo solo con URL pública **HTTPS**, dominio controlado y relay SMTP que aplique firma **DKIM sobre ambos encabezados** `List-Unsubscribe` y `List-Unsubscribe-Post`. Comprobar el mensaje recibido en un buzón de pruebas: la plataforma añade las cabeceras, pero no puede certificar que un tercero las haya firmado. La creación y el consumo de tokens existentes conservan su protección por cliente y expiración.

## 4. Webhooks: protección DNS rebinding/SSRF

En cada intento, el transporte resuelve todos los registros IP del dominio autorizado, rechaza respuestas vacías o con cualquier IP privada/reservada, elige una IP pública y abre el socket TCP **a esa IP**, sin nueva resolución al conectar. TLS mantiene `hostname` original en SNI y verifica el certificado HTTPS contra el mismo nombre. Solo admite HTTPS 443, no sigue redirecciones, impone timeouts y comprueba respuesta HTTP/1.1 limitada.

Sigue siendo necesaria una política de egreso de red para impedir que configuraciones futuras permitan conexión a redes internas. Verificar funcionamiento con un receptor HTTPS de pruebas y DNS controlado; las pruebas unitarias offline de rechazo de IP no sustituyen la validación TLS real.

## 5. Métricas con credencial independiente

`/actuator/info`, `/actuator/metrics`, `/actuator/metrics/**` y `/actuator/prometheus` requieren `PERM_METRICS_READ` o `ROLE_ADMIN`. **`PERM_ALL` histórico ya no es suficiente** en estas rutas. Crear un cliente de monitorización mediante la API administrativa con permisos `METRICS_READ` y su propia credencial API (nunca reutilizar la clave del cliente que envía correo). Configurar el scraper para enviar `X-Client-Id` y `X-Internal-Api-Key` por TLS y restringir el acceso a Actuator por red. `/actuator/health` sigue público para balanceadores, según el contrato existente; no exponer detalles internos en el health público.

## 6. Cómo validar en Windows sin Docker

Con **JDK 25**, PostgreSQL, RabbitMQ y Mailpit instalados, abrir PowerShell en la raíz y configurar `.env.example` usando secretos **solo de pruebas**; no subir el archivo con secretos al repositorio. Ejecutar:

```powershell
py -3 scripts/test_version_contract.py
.\mvnw.cmd -B clean verify
```

En una BD de pruebas con privilegio `CREATE SCHEMA`, definir `MAIL_PLATFORM_SMOKE_DB_URL`, `DB_USERNAME` y `DB_PASSWORD`; `FlywayMigrationIntegrationTest` prueba V1→V16, V12→V16 y V15→V16, mientras `JdbcLargeObjectAttachmentIntegrationTest` comprueba transmisión, aislamiento y limpieza de LO. Lanzar el API con el JAR `mail-api-0.10.1-SNAPSHOT.jar`; ejecutar `scripts/e2e_smoke.py` y `scripts/upgrade_smoke.py` con Mailpit, además de verificar RFC 8058 con relay DKIM y prueba real de webhooks TLS. Registrar XML Surefire, JaCoCo y los resultados de restauración.

Consultar [auditoría](../AUDIT_REPORT.md) y [validación](../VALIDATION_REPORT.md). **Sin estos ensayos, v0.10.1 no está certificada para producción crítica.**
