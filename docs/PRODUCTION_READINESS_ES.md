# Guía de aceptación operativa — Mail Platform v0.10.4

La configuración y el código de la aplicación no son, por sí solos, una prueba de aptitud para producción. Los procedimientos ampliados de staging, multiinstancia y recuperación se detallan en [Certificación v0.10.4](CERTIFICACION_V0104_ES.md). Este documento es un procedimiento verificable para completar las validaciones que no pudieron ejecutarse en el entorno de edición.

## 1. Entorno independiente

- Java 25, Maven Wrapper, PostgreSQL compatible con las migraciones actuales, RabbitMQ y servidor SMTP de pruebas (Mailpit o similar).
- Python 3.11+ para los scripts HTTP, ClamAV opcional habilitado en staging con firmas actualizadas.
- Variables reales inyectadas desde un gestor de secretos. No versionar archivos `.env` con contraseñas. La URL pública de bajas comerciales debe ser HTTPS fuera de localhost.
- Credenciales por aplicación y permisos explícitos. Separar ADMIN de las credenciales que envían correos. Restringir las rutas administrativas y de métricas por red.

## 2. Matriz de ejecución reproducible

| Ensayo | Comando / actividad | Evidencia exigida |
|---|---|---|
| Consistencia de release | `python3 scripts/test_version_contract.py` | Ocho módulos, v0.10.4, V1–V17 y JAR dinámico. |
| Arquitectura | `python3 scripts/check_architecture.py` | Sin dependencias circulares detectadas por el guard. |
| Java y JaCoCo | `./mvnw -B clean verify` | Exit 0, informes Surefire/JaCoCo, cumplimiento de umbrales provisionales. |
| Dependencias | `./mvnw -B -DskipTests package org.owasp:dependency-check-maven:13.0.0:aggregate -DfailBuildOnCVSS=7` | Informe y revisión humana de falsos positivos; variable `NVD_API_KEY` protegida si disponible. |
| Base nueva y actualización | `FlywayMigrationIntegrationTest` con `MAIL_PLATFORM_SMOKE_DB_URL` y CREATE SCHEMA | V1→V17, V12→V17, V15→V17, V16→V17, conservación de `*` existente, nuevo default limitado. |
| Contrato HTTP | `python3 scripts/check_openapi_contract.py` | Rutas, verbos y documentación de credenciales presentes. |
| Correo completo | `python3 scripts/e2e_smoke.py` | Consulta HTTP, RabbitMQ, SMTP y comprobación del mensaje en Mailpit. |
| Seguridad y campañas | `python3 scripts/upgrade_smoke.py` | ACL/403, aislamiento, idempotencia, opt-out, campañas y webhook. |
| Alertas | `promtool check rules ops/prometheus/mail-alerts.yml` y `promtool test rules ops/prometheus/mail-alerts.test.yml` | Reglas válidas y escenarios con alertas disparadas. |
| Restauración | `RESTORE_VERIFY_APPROVED=yes PGDATABASE=<base_staging> bash scripts/verify_pg_restore.sh` | Filas y último Flyway coincidentes, base aislada eliminada. Solo con permiso CREATE DATABASE y autorización. |
| Campaña sintética | `LOAD_SMOKE_ENABLED=yes LOAD_RECIPIENTS=10000 ... python3 scripts/load_smoke.py` | Admisión y vaciado de staging en localhost/Mailpit; métricas y latencia capturadas por separado. |
| Interrupción | Terminar worker, detener/reiniciar broker, detener/reiniciar PG, simular caída SMTP | Sin pérdida de solicitudes confirmadas como persistidas; reintentos finitos y dead-letter revisada. |

**En CI:** se activan suites de integración cuando existe `MAIL_PLATFORM_SMOKE_DB_URL`. Los resultados de GitHub Actions no se han obtenido todavía; el workflow debe ejecutarse con una rama protegida y verificarse antes de darlo por obligatorio para release.

## 3. Windows sin Docker

Instalar Java 25, PostgreSQL, RabbitMQ y Mailpit como servicios o procesos independientes. Configurar las variables de `.env.example` mediante PowerShell con valores de pruebas, ejecutar `.\mvnw.cmd -B clean verify` desde el directorio del POM y después `java -jar .\mail-api\target\mail-api-0.10.4-SNAPSHOT.jar`. Para pruebas Python: `py -3 .\scripts\e2e_smoke.py` y `py -3 .\scripts\upgrade_smoke.py` en una segunda terminal. Consultar `docs/EJECUCION_WINDOWS_ES.md` para ejemplos detallados y consideraciones de claves.

## 4. Copias de seguridad y continuidad

Antes de instalar nuevas migraciones, capturar `pg_dump` con checksum y validar restauración en una base que **no** reciba tráfico real. Probar corrupción de un backup, revocación de acceso, pérdida de discos, detención simultánea de API/worker y actualización fallida. Acordar con el propietario del sistema objetivos RPO/RTO y retención; no se fijan cifras universales sin conocer el volumen de correo, el proveedor y el negocio. Mantener las claves de cifrado originales y comprobar su recuperación segura.

No borrar correos, payloads o adjuntos bajo `legal_hold`; preservar documentación de auditoría. Restringir permisos de ejecución del servicio, cifrar conexiones reales con TLS y documentar rotación de certificados y credenciales. El endpoint de baja comercial debe conservarse accesible y no debe alterar estado mediante GET.

## 5. Criterios para decidir el despliegue

No declarar el objetivo de 90% por área alcanzado hasta que la evidencia anterior esté disponible. Registrar: commit, plataforma, versiones Java/PG/Rabbit/SMTP, fecha, línea de comando, salida completa, fallos, incidencias y corrección. Probar una actualización gradual y una recuperación bajo la misma configuración de producción. Poner en cuarentena cualquier release con un fallo de aislamiento entre clientes, pérdidas de mensajes confirmados o restauración no demostrada.
