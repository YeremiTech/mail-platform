# Mail Platform v0.6.0: mejoras implementadas y pruebas pendientes

## Resumen

Se conserva la arquitectura de ocho módulos Maven, la compatibilidad de los endpoints de v0.5.0 y las migraciones V1–V9. Se agrega **V10** (auditoría de administración). Las modificaciones de esta versión no significan que todas las áreas hayan alcanzado el 90 % de preparación operativa: aún faltan la ejecución del build JDK 25, migraciones reales, pruebas de servicios y simulacros.

## Funciones nuevas

1. **CSV para campañas**: `POST /api/v1/campaigns/import-csv`, multipart con parte `metadata` (JSON) y `file` (CSV UTF-8). Hasta 5 MiB y 10 000 destinatarios; cabecera obligatoria `email,display_name`, columnas opcionales `var_nombre`, `var_ciudad`, etc. Se reconocen comillas, comas y saltos de línea RFC-4180. Se rechazan correos repetidos, direcciones inválidas, más de 24 columnas y campos individuales de más de 4096 caracteres. Los envíos con `purpose=MARKETING` requieren una columna `consent` con valor `true` explícito **para cada destinatario**. Esta declaración del importador no es verificación legal del consentimiento; la plataforma no incluye todavía un registro de bajas/supresión.
2. **Antivirus ClamAV**: integración INSTREAM TCP. Con el scanner activado, los adjuntos se inspeccionan antes de su almacenamiento; el resultado FOUND responde HTTP 422 y una caída del scanner responde HTTP 503, sin almacenar el archivo. En desarrollo está desactivado por defecto; `production` lo activa obligatoriamente y necesita `CLAMAV_HOST` configurado. El análisis de firmas existente permanece. Aún no hay streaming del PDF de extremo a extremo ni almacenamiento de objetos.
3. **Auditoría administrativa**: la migración V10 agrega `mail_admin_audit`, sin registrar secretos ni contenidos de correo. Los cambios administrativos se auditan en la misma transacción (alta de clientes, emisión y revocación de claves, actualización de estado, límites y host de webhook). `GET /api/v1/admin/clients/audit?limit=50` está restringido a ADMIN. Se corrige la clase `PersistentClientCredentials` para que pueda recibir el proxy transaccional de Spring.
4. **Métricas de salud**: se mantienen las métricas anteriores y se agregan contadores/gauges de webhooks pendientes o DEAD, destinatarios en staging, antigüedad del correo listo más antiguo, salud de refresco de métricas y tiempo de última actualización. El gauge de Outbox DEAD excluye cancelaciones solicitadas por los clientes. Las reglas de Prometheus y el dashboard Grafana son plantillas de despliegue, no una instalación en ejecución.
5. **Operaciones**: `scripts/backup-postgres.sh` realiza un dump personalizado y checksum SHA-256 con permisos restrictivos. `scripts/restore-postgres.sh` requiere confirmación explícita del destino y que las escrituras estén detenidas, valida checksum y ejecuta restore transaccional. Se agrega `application-production.properties` con protección de SMTP STARTTLS y credenciales legacy desactivadas. Los procedimientos deben ensayarse en una base aislada antes de usarse en producción.
6. **Calidad**: reglas arquitectónicas automatizadas para evitar dependencias de infraestructura dentro de dominio/aplicación; nuevos tests de CSV y protocolo antivirus, y smoke HTTP para CSV y auditoría. Se rechazan destinatarios duplicados en todas las campañas y variables excesivamente grandes.

## Ejemplo de importación CSV

Archivo `recipients.csv`:

```csv
email,display_name,consent,var_name,var_city
ana@example.org,"Ana, Pérez",true,Ana,Lima
beto@example.org,Beto,true,Beto,Arequipa
```

Parte `metadata` de la solicitud multipart:

```json
{"subject":"Aviso","templateKey":"custom/aviso","purpose":"MARKETING","commonVariables":{}}
```

Ejemplo (Linux/macOS; en Windows utilizar `curl.exe`):

```bash
curl -X POST 'http://localhost:8080/api/v1/campaigns/import-csv' \
  -H "X-Client-Id: $MAIL_CLIENT_ID" \
  -H "X-Internal-Api-Key: $MAIL_API_KEY" \
  -F 'metadata={"subject":"Aviso","templateKey":"custom/aviso","purpose":"MARKETING"};type=application/json' \
  -F 'file=@recipients.csv;type=text/csv'
```

La plantilla debe existir, estar publicada y pertenecer al cliente autenticado. `purpose=TRANSACTIONAL` no exige `consent`, pero no exime al sistema consumidor de la normativa aplicable.

## Configuración antivirus

```properties
SPRING_PROFILES_ACTIVE=production
ATTACHMENTS_AV_ENABLED=true
CLAMAV_HOST=clamav.internal
CLAMAV_PORT=3310
```

No exponer TCP/3310 a Internet. Permitir únicamente tráfico desde la API. Probar con EICAR en un entorno controlado y alertar si `mail_attachment_scans_total{result="unavailable"}` aumenta. **Si se activa el perfil de producción sin configurar ClamAV, el arranque falla**. La API no debe almacenar archivos no inspeccionados en ese perfil.

## Respaldo y recuperación

La aplicación debe estar en mantenimiento y los workers detenidos antes de restaurar una copia. Los scripts requieren los clientes PostgreSQL correspondientes a la versión de servidor y variables `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` o un `PGPASSFILE` seguro.

```bash
bash scripts/backup-postgres.sh mail_platform ./backups
# Restauración destructiva SOLO sobre una base de ensayo, tras parar las escrituras:
CONFIRM_RESTORE_DB=mail_platform_restore ACKNOWLEDGE_WRITES_STOPPED=yes \
  bash scripts/restore-postgres.sh ./backups/mail_platform-AAAAMMDDThhmmssZ.dump mail_platform_restore
```

Crear previamente `mail_platform_restore` vacía y conceder permisos de restauración. Verificar que su volumen de datos y sus migraciones son correctos y ejecutar los smoke tests antes de cerrar el incidente. Programar respaldos cifrados fuera del servidor y ensayar RPO/RTO regularmente.

## Requisitos de aceptación por área

| Área | Avance implementado en v0.6 | Aún pendiente para avalar >= 90 % |
|---|---|---|
| Envío | Se mantienen programación, cancelación, idempotencia individual y SMTP | Prueba real de programación bajo carga, diferentes remitentes por cliente y aceptación SMTP |
| Recuperación | Sin cambios al OTP, HMAC ni a las autorizaciones de un uso | Pruebas concurrentes y anti-enumeración extremo a extremo con consumidores |
| Adjuntos | Antivirus opcional fail-closed, firmas y cuotas de 15 MB | ClamAV real probado; streaming/object storage y políticas de retención |
| Seguridad | Auditoría transaccional y credenciales persistentes | Pentest, secrets manager, rotación automatizada y revisión de SSRF/webhooks |
| Colas | Gauges de atrasos y alertas, corrección de falsa alarma por cancelaciones | Ensayos de pérdida de confirmación SMTP, DLQ de RabbitMQ y failover |
| Arquitectura | Regla automática de importaciones entre dominio y aplicación | Pruebas de contratos y despliegue independiente de API/worker |
| Integración | Método `importCampaignCsv` en el starter, API HTTP tipada | SDKs verificados para otros lenguajes y compatibilidad de versiones |
| Plantillas | Se mantienen CRUD, versionado y publicación | Test intensivo de variables y política de versiones/localización |
| Masivos | CSV, prevalidación, límites de variables, deduplicación | 10k en infraestructura real; listas de supresión y bajas |
| Monitoreo | Nuevas gauges, alertas Prometheus y dashboard Grafana | Instalar, autenticar scrape, probar alertas y tracing distribuido |
| Pruebas | JUnit CSV/ClamAV, smoke API y guardas arquitectónicas en CI | `mvnw clean verify` JDK 25, carga, fallos y pruebas de seguridad |
| Producción | Scripts de backup/restore con checksum, perfil seguro | Simulacro real de restore, redundancia, backups externos y entregabilidad |

**Importante:** los webhooks siguen siendo `at least once` y `SENT` sigue significando aceptación por el servidor SMTP, no lectura ni llegada garantizada a la bandeja de entrada.
