# AUDIT_REPORT — Mail Platform v0.10.6-SNAPSHOT

## Alcance

Revisión incremental de v0.10.5 centrada en los pendientes operativos identificados antes de producción.

## Cambios aplicados

1. **Perfil seguro del contenedor**: `Dockerfile` establece `SPRING_PROFILES_ACTIVE=production` por defecto. El perfil sigue pudiendo sustituirse explícitamente para entornos controlados.
2. **Migración de adjuntos a gran escala**: se conserva V17 intacta y se añade un preflight idempotente por lotes que debe ejecutarse, cuando corresponda, después de V16 y antes de V17. Usa `FOR UPDATE SKIP LOCKED`, tamaño de lote configurable y aprobación explícita.
3. **Contrato OpenAPI completo**: el verificador descubre las operaciones de todos los controladores y exige que todas aparezcan en OpenAPI con `operationId`, respuestas y esquemas de autenticación.
4. **Capacidad de 10 000 destinatarios**: el ensayo semanal pasa de 200 a 10 000 destinatarios y mide aislamiento transaccional, aceptación SMTP, captura Mailpit, duración máxima y uso de heap.
5. **Pruebas de release**: se añadieron comprobaciones que bloquean la regresión de estos cuatro controles.

## Compatibilidad

- Ocho módulos Maven conservados.
- Flyway V1..V17 conservadas sin modificación.
- No se eliminaron endpoints públicos.
- No se cambiaron DTO públicos.

## Riesgos que dependen del entorno

- La compilación y JUnit/JaCoCo deben ejecutarse con Java 25 y Maven.
- PostgreSQL debe validar el preflight sobre una copia representativa con volumen real de adjuntos.
- La prueba de 10 000 destinatarios requiere infraestructura de CI/staging dimensionada; no debe ejecutarse contra producción.
- La confirmación `DELIVERED` depende del proveedor/relay y no puede inferirse únicamente de la aceptación SMTP.
- SPF, DKIM y DMARC requieren el dominio y relay definitivos.

## Estado

La calibración de código y automatización está reforzada. La certificación de producción sigue condicionada a la ejecución satisfactoria de Maven/JaCoCo, PostgreSQL/RabbitMQ/SMTP, carga, recuperación y configuración del dominio definitivo.
