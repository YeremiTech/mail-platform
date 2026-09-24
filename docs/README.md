# Documentación de Mail Platform

Índice de guías de integración, arquitectura, operación y validación para la versión de trabajo `0.10.6-SNAPSHOT`. Se conservan en inglés los nombres técnicos, las rutas y los contratos de API.

[README principal](../README.md) · [Estado técnico](../PROJECT_STATUS.md) · [Historial de cambios](../CHANGELOG.md)

## Integración y desarrollo

| Documento | Contenido |
| --- | --- |
| [Guía de integración](INTEGRATION_GUIDE.md) | Autenticación, recuperación de contraseñas y envío de facturas o boletas. |
| [Integración multilenguaje](INTEGRACION_MULTILENGUAJE_ES.md) | Ejemplos para Python, Node.js y .NET. |
| [Ejemplos de API](API_EXAMPLES.md) | Solicitudes HTTP y aprovisionamiento de permisos. |
| [Arquitectura](ARCHITECTURE.md) | Módulos, Outbox, concurrencia y aislamiento entre clientes. |
| [Windows sin Docker](EJECUCION_WINDOWS_ES.md) | Instalación y ejecución con servicios locales. |

## Operación y verificación

| Documento | Contenido |
| --- | --- |
| [Preparación para producción](PRODUCTION_READINESS_ES.md) | Requisitos operativos y verificaciones pendientes. |
| [Guía de certificación v0.10.6](CERTIFICACION_V0106_ES.md) | Procedimientos para Java 25, Flyway, RabbitMQ, SMTP, restauración y carga. |
| [Estado técnico](../PROJECT_STATUS.md) | Funcionalidades y estado registrado de la versión actual. |
| [Reporte de validación](../VALIDATION_REPORT.md) | Comprobaciones realizadas, limitaciones y evidencia faltante. |
| [Auditoría](../AUDIT_REPORT.md) | Hallazgos y riesgos operativos. |
| [Seguridad](../SECURITY.md) | Gestión de secretos, cifrado y exposición de la API. |

**Importante:** las guías de pruebas y automatización no equivalen a resultados reales de CI o a una certificación de producción. Es necesario incorporar y ejecutar los workflows en el repositorio antes de mostrar una insignia de CI como evidencia.

## Historial y migraciones

El [CHANGELOG](../CHANGELOG.md) contiene el historial de versiones. Las siguientes guías se conservan para actualizar instalaciones anteriores y consultar decisiones históricas; no sustituyen la documentación vigente.

- Certificación anterior: [v0.10.5](CERTIFICACION_V0105_ES.md), [v0.10.4](CERTIFICACION_V0104_ES.md) y [v0.10.3](CERTIFICACION_V0103_ES.md).
- Mejoras v0.10: [v0.10.2](MEJORAS_V0102_ES.md), [v0.10.1](MEJORAS_V0101_ES.md) y [v0.10](MEJORAS_V010_ES.md).
- Mejoras anteriores: [v0.8](MEJORAS_V080_ES.md), [v0.7](MEJORAS_V070_ES.md), [v0.6](MEJORAS_V060_ES.md) y [v0.5](MEJORAS_V050_ES.md).
- Guías operativas: [v0.6](OPERACION_V060_ES.md) y [v0.5](OPERACION_V050_ES.md).
- Evidencia histórica: [auditoría v0.10](HISTORICAL_AUDIT_v0.10.0.md), [validación v0.10](HISTORICAL_VALIDATION_v0.10.0.md), [validación v0.5](HISTORICAL_VALIDATION_v0.5.0.md) y [validación v0.4.1](HISTORICAL_VALIDATION_v0.4.1.md).

Este índice evita mover archivos o cambiar las rutas originales de documentación, código y migraciones.
