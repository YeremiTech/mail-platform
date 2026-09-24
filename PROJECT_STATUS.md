# Mail Platform 0.10.6-SNAPSHOT — estado técnico

**Versión de trabajo:** ocho módulos Maven, Java 25, Spring Boot, PostgreSQL 17, RabbitMQ 4, SMTP y Flyway V1–V17. Este release no cambia endpoints públicos ni modifica las migraciones ya publicadas.

## Mejoras añadidas en v0.10.6

- Seguridad REST: fallback `denyAll` para rutas nuevas y guard automático que comprueba las 45 operaciones declaradas. La prueba MockMvc con credenciales reales todavía requiere Java 25 y PostgreSQL.
- Despliegue: perfil `production` con comprobación estricta de SMTP/TLS, ClamAV, credenciales de infraestructura y secretos de cifrado independientes. Los tests puros funcionan sin Maven; falta verificar el arranque completo en staging.
- Observabilidad: métrica `mail_rabbitmq_available`, alerta Prometheus y ensayo de regla. La alerta en vivo sigue pendiente.
- Automatización: ensayos semanales limitados a infraestructura descartable para concurrencia entre dos instancias, recuperación RabbitMQ/SMTP y carga sintética. Publicación del artefacto de CI condicionada también a un análisis de dependencias satisfactorio. Ningún workflow remoto se ha ejecutado desde este entorno.
- Evidencia: `generate_ci_evidence.py` diferencia JUnit/JaCoCo medidos, chequeos de infraestructura ejecutados y validaciones externas pendientes. Consultar `docs/CERTIFICACION_V0105_ES.md`.

## Cambios históricos incorporados en v0.10.4 sobre v0.10.3

- **Versión de OpenAPI:** reemplazo del número fijo 0.10.0 por `project.version` filtrado por Maven en un archivo de propiedades dedicado. Las demás configuraciones mantienen sus variables de entorno intactas. El verificador HTTP compara `info.version` del contrato publicado con el POM raíz.
- **Disponibilidad operativa:** `readiness` incorpora estado de disponibilidad de la aplicación, PostgreSQL y RabbitMQ; `liveness` es independiente del broker. Nuevo test HTTP de permisos y sondas que exige 401 anónimo, 403 cliente de envío y 200 administrador para métricas.
- **Pruebas críticas:** el pipeline obliga a ejecutar las pruebas existentes de adjuntos, Flyway, aislamiento, recuperación, concurrencia, webhooks, worker y SMTP. No acepta XML JUnit ausente o pruebas críticas omitidas.
- **Errores permanentes SMTP:** enlaces de baja comercial inválidos se consideran inválidos de forma permanente y no se retratan como incidencias temporales de infraestructura.
- **Carga y continuidad:** comprobadores opcionales solo para localhost y servicios descartables: campaña sintética con comprobación de aceptación SMTP y captura Mailpit, solicitud transaccional durante la campaña, recuperación de Outbox tras parada controlada de RabbitMQ y dos instancias concurrentes con clave de idempotencia compartida. Las suites opcionales requieren aprobación explícita mediante `workflow_dispatch`; no ejecutarlas no constituye prueba superada.
- **Documentación y release:** guía reproducible de certificación v0.10.4, documentación Windows actualizada e informes de auditoría y validación diferenciando pruebas locales de infraestructura real.

## Estado de certificación

Las validaciones locales estáticas/independientes se describen en `VALIDATION_REPORT.md`. El proyecto **no está certificado para producción crítica** hasta conseguir resultados de `mvn clean verify` con JDK 25 y ejecución real de PostgreSQL/Flyway, RabbitMQ, SMTP/Mailpit, restauración, carga y recuperación. La verificación DKIM de cabeceras RFC 8058 requiere un correo realmente recibido desde el relay y DNS públicos; Mailpit por sí solo no puede demostrarla.

Consultar [certificación v0.10.6](docs/CERTIFICACION_V0105_ES.md), [auditoría](AUDIT_REPORT.md) y [validación](VALIDATION_REPORT.md).
