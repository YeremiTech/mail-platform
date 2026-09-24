# VALIDATION_REPORT — Mail Platform v0.10.6-SNAPSHOT

## Validaciones ejecutadas

- `python3 -m unittest discover -s scripts/tests -v`: **34/34 PASS**.
- `python3 -m py_compile scripts/*.py scripts/tests/*.py`: **PASS**.
- `python3 scripts/test_version_contract.py`: **PASS**, 8 módulos y Flyway V1..V17.
- `python3 scripts/check_architecture.py`: **PASS**, 43 fuentes de dominio/aplicación.
- `python3 scripts/check_security_route_contract.py`: **PASS**, 45 operaciones REST con regla explícita y `/api/**` deny-all.
- `python3 scripts/test_ops_scripts.py`: **PASS**.
- `bash scripts/offline_feature_smoke.sh`: **PASS**, incluido parser de 152 archivos Java/test.
- `bash -n scripts/*.sh`: **PASS**.
- Dashboard Grafana JSON: **PASS**.

## Validación Maven

Se intentó `bash ./mvnw -v`.

Entorno disponible:

- Java: OpenJDK 21.0.11.
- Proyecto: requiere Java 25.
- Maven Wrapper: no pudo descargar Maven porque `repo.maven.apache.org` no fue resoluble desde el entorno.

Resultado: **NO EJECUTADO/NO CERTIFICADO** para Maven, JUnit, JaCoCo y pruebas Spring de integración. No se presenta como aprobado.

## Validaciones pendientes de infraestructura

- `./mvnw -B clean verify` con Java 25.
- PostgreSQL 17: V1→V17, V12→V17, V15→V17, V16→V17 y preflight por lotes sobre dataset grande.
- Backup/restore real incluyendo Large Objects.
- RabbitMQ: interrupción y recuperación del Outbox.
- SMTP/Mailpit: E2E, recuperación y RFC 8058.
- Carga de 10 000 destinatarios con límites de duración y heap.
- DKIM/SPF/DMARC con relay y dominio definitivos.
