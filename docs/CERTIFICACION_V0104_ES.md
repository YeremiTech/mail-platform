# Certificación reproducible de Mail Platform v0.10.4-SNAPSHOT

**Estado:** esta guía describe criterios de aceptación y comandos reproducibles; que los comandos estén presentes no implica que hayan superado pruebas con infraestructura real. Solo utilizar datos sintéticos y servicios descartables. No ejecutar caos ni campañas masivas contra producción.

## 1. Requisitos previos

- JDK 25 (`java -version` y `javac -version`), Maven Wrapper con acceso a dependencias Maven, Python 3.11+.
- PostgreSQL 17 con permiso para crear *bases aisladas* de ensayo; RabbitMQ 4, un servidor SMTP de captura como Mailpit (SMTP 1025, HTTP 8025), y ClamAV para validar escaneo opcional.
- Variables de entorno en CI: `MAIL_PLATFORM_SMOKE_DB_URL`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_HOST`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, `ADMIN_API_KEY`, `API_KEY_PEPPER`, `WEBHOOK_ENCRYPTION_KEY`, `SENSITIVE_PAYLOAD_KEY`, `OTP_HMAC_SECRET`, `E2E_CLIENT_ID`, `E2E_API_KEY` y, para marketing, `MARKETING_PUBLIC_BASE_URL` HTTPS. Nunca subir valores reales al repositorio ni incluirlos en informes.
- La base y el relay de prueba **no deben** estar conectados con consumidores, campañas, destinatarios ni secretos de producción. Los ejemplos usan `example.test` y acceso HTTP solo en `localhost`.

## 2. Prueba obligatoria de Maven, JaCoCo y seguridad

En Linux/CI:

```bash
bash ./mvnw -B clean verify
python3 scripts/check_test_results.py
python3 scripts/check_coverage_reports.py
python3 scripts/test_version_contract.py
python3 scripts/check_architecture.py
python3 -m unittest discover -s scripts/tests -v
```

En Windows sin Docker, instalar servicios como procesos o servicios del sistema y ejecutar desde PowerShell:

```powershell
.\mvnw.cmd -B clean verify
py -3 .\scripts\check_test_results.py
py -3 .\scripts\check_coverage_reports.py
py -3 .\scripts\test_version_contract.py
```

Los tests de PostgreSQL habilitados por `MAIL_PLATFORM_SMOKE_DB_URL` y los umbrales JaCoCo son obligatorios para certificar el build. El pipeline debe fallar cuando faltan informes XML, suites críticas o cobertura suficiente; **nunca** se deben desactivar tests para aprobarlo. Ejecutar la comprobación OWASP de dependencias y revisar los hallazgos de CVSS ≥7 antes de publicar.

## 3. Certificación de Flyway y adjuntos

- `FlywayMigrationIntegrationTest` ensaya V1, V12, V15 y V16→V17, sin editar ninguna migración anterior. Confirmar migración de `bytea` heredado a Large Objects y conservación de checksums.
- `JdbcLargeObjectAttachmentIntegrationTest` comprueba rollback que no deje objetos huérfanos, lectura y borrado concurrentes, aislamiento de cliente y eliminación `lo_unlink` de objetos elegibles.
- `scripts/verify_pg_restore.sh` necesita `RESTORE_VERIFY_APPROVED=yes`, `REQUIRE_BLOB_FIXTURE=yes`, PostgreSQL real y privilegios para crear y eliminar una **base aislada**. Conserva y comprueba bytes de un adjunto, no solo el recuento de filas.
- Registrar tiempo de migración y tamaño del respaldo. V17 puede exigir una ventana operativa para bases históricas grandes. El backup custom debe incluir `--blobs` y verificarse antes de migrar producción.

## 4. Probar API, RabbitMQ, SMTP y métricas

Después de `mvn verify`, arrancar una instancia en entorno de prueba:

```bash
java -jar mail-api/target/mail-api-0.10.4-SNAPSHOT.jar
python3 scripts/check_operational_health.py
python3 scripts/check_openapi_contract.py
python3 scripts/e2e_smoke.py
python3 scripts/upgrade_smoke.py
```

Criterios: `/actuator/health/liveness` devuelve 200 cuando la API está viva; `/readiness` depende de PostgreSQL y RabbitMQ; las métricas devuelven 401 anónimo, 403 al cliente de envío y 200 al administrador. La versión de `/v3/api-docs` debe coincidir con el POM. `e2e_smoke.py` comprueba aceptación SMTP real y un PDF recibido por Mailpit; `upgrade_smoke.py` comprueba permisos, aislamiento y RFC 8058 en mensajes RAW, con GET de baja sin efecto y POST de un clic con el marcador obligatorio.

`SENT` significa *aceptado por SMTP*, no entrega al buzón final. El servidor de captura no garantiza que un tercero haya recibido el correo.

## 5. Carga sintética, solo en staging

```bash
LOAD_SMOKE_ENABLED=yes LOAD_RECIPIENTS=200 LOAD_VERIFY_SMTP=yes \
 LOAD_VERIFY_MAILPIT=yes LOAD_WAIT_SECONDS=3600 \
 python3 scripts/load_smoke.py
```

Opcionalmente elevar a `LOAD_RECIPIENTS=10000` **después** de verificar recursos, cuotas, Mailpit y ventana máxima de prueba. El script solo admite URL API local; comprueba que finalice la etapa, que cada destinatario quede registrado como aceptado por SMTP y que Mailpit reciba cada dirección sintética. También exige que el envío transaccional iniciado durante la campaña no sobrepase `LOAD_MAX_TRANSACTION_LATENCY_SECONDS` (ajustable según el objetivo de servicio definido). Recoger CPU, heap, memoria del broker, conexiones SQL, latencia p50/p95/p99, colas y tasas de error. El script no sustituye una prueba de rendimiento sostenida.

## 6. Fallo controlado y recuperación del Outbox

**Solo en una infraestructura descartable cuya interrupción esté autorizada.** El script no detiene servicios automáticamente; requiere una acción consciente del operador.

```bash
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py setup
# Detener exclusivamente el RabbitMQ descartable del ensayo.
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py submit
# Reiniciar RabbitMQ y comprobar la recuperación.
MAIL_CHAOS_APPROVED=yes python3 scripts/chaos_recovery_smoke.py recover --timeout 300
```

Se exige readiness 503 durante la parada con liveness 200, admisión e idempotencia de una solicitud persistida y posterior aceptación SMTP/captura Mailpit. Comprobar que aparece **una** confirmación SMTP registrada; por la semántica de SMTP no existe promesa general de *exactly once* ante pérdida de confirmaciones. Repetir los ensayos separadamente con worker, base de datos y SMTP caídos, documentando si aparecen duplicados o intervenciones manuales.

## 7. Dos instancias simultáneas

Con dos API en puertos locales 8080 y 8081, **misma base y broker**, y ambas sanas:

```bash
HA_TEST_APPROVED=yes HA_API_URLS=http://127.0.0.1:8080,http://127.0.0.1:8081 \
 python3 scripts/multi_instance_smoke.py
```

Ocho POST concurrentes con la misma clave de idempotencia deben devolver un identificador, producir una sola aceptación SMTP registrada y un mensaje sintético en Mailpit. Esta comprobación **no** demuestra resistencia a la pérdida de una región ni entrega SMTP exactamente una vez.

## 8. Verificar DKIM en el relay real

Enviar un correo comercial **de prueba** por el relay de la futura producción a un buzón externo controlado. Guardar su mensaje RAW `.eml` fuera del repositorio y ejecutar el verificador existente `scripts/verify_dkim_relay.py` con el dominio y la muestra especificados en `python3 scripts/verify_dkim_relay.py --help` si la interfaz admite `--help` (consultar la fuente si usa variables de entorno). Deben superar: firma DKIM criptográfica real, dominio autorizado y cobertura de `List-Unsubscribe` y `List-Unsubscribe-Post`, con SPF/DMARC coherentes. Mailpit no demuestra esta parte, porque el relay definitivo aún no firmó el mensaje.

## 9. GitHub Actions

Los eventos `push`/`pull_request` ejecutan compilación, suites críticas, controles de cobertura, integración HTTP/SMTP, reglas de alerta y restauración aislada en servicios CI. En **Actions → ci → Run workflow** existen las opciones adicionales `run_load`, `run_ha`, `run_chaos` (apagadas por defecto) y `recipients` entre 1 y 10000. Ejecutarlas manualmente únicamente sobre servicios descartables y revisar los artefactos de JUnit, JaCoCo y logs; el workflow está preparado, **no se afirma que haya pasado hasta contar con una ejecución real**.

## 10. Evidencia de cierre

Guardar fecha, commit, versiones, variables no sensibles, comando, exit code, informe JUnit/JaCoCo, pruebas HTTP/SMTP, métricas y duración, resultado de backup/restore y alertas disparadas. Marcar por separado: implementado / verificado localmente / validado en staging / aprobado en producción. No asignar un porcentaje ≥90 % por área sin pruebas y criterios medibles de disponibilidad y recuperación.
