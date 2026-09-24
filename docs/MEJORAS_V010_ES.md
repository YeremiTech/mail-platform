# Mail Platform v0.10.0 — mejoras y procedimiento de actualización

**Alcance:** evolución localizada de la v0.9, no un proyecto nuevo. Se conservan los ocho módulos Maven, las rutas REST anteriores y Flyway V1–V13, y se añaden V14 y V15. La revisión de código y las verificaciones offline no sustituyen una validación de producción.

## Cambios implementados

| Componente | Cambio | Compatibilidad / límite |
|---|---|---|
| Identidad y permisos | Cliente nuevo requiere permisos explícitos y reconocidos; se rechaza `*` en creación. | V14 cambia el valor por defecto SQL de nuevas filas a `EMAIL_SEND,EMAIL_READ`; V13 no se altera. Los clientes existentes conservan su asignación `*` hasta revisión. |
| Adjuntos | Verificación por firma con `InputStream`, comprobación de extensión/MIME, lectura acotada, análisis INSTREAM opcional de ClamAV y multipart respaldado en disco. | La persistencia actual en PostgreSQL `bytea` sigue necesitando un búfer hasta 15 MB. No es transmisión de extremo a extremo. |
| Retención | V15 agrega `legal_hold`, `retention_until` e índice de candidatos. Administración GET/PUT por adjunto, cliente y motivo obligatorio; mantenimiento respeta el bloqueo y fecha. | Nunca usar limpieza SQL manual que ignore referencias a mensajes, campañas o bloqueo legal. El motivo no se registra en texto libre. |
| Observabilidad | Cabecera de respuesta `X-Request-Id`, MDC y mayor claridad de esquemas de seguridad OpenAPI. | No incluir identificadores de solicitud individuales como etiquetas Prometheus. |
| Calidad | JaCoCo, mínimos iniciales de cobertura por módulo, pruebas nuevas de permisos, recuperación concurrente, Flyway y retención. | Los mínimos son **provisionales**: elevarlos después de ejecutar y medir el build real. |
| CI | Java25, PostgreSQL, RabbitMQ, Mailpit, scanner OWASP, regla de arquitectura, chequeo de OpenAPI, E2E, test de reglas de alerta y JAR resuelto dinámicamente. | El diseño del workflow existe; debe ejecutarse y corregirse según resultados reales de GitHub Actions. |
| Operación | Simulador de campaña sintética de 10k y script de restauración en una base aislada con consentimiento explícito. | El simulador verifica admisión/drenaje, **no** entrega final al buzón ni comportamiento de proveedores externos. |

## Secuencia de actualización sin pérdida de datos

1. Revisar todas las integraciones que todavía usan credenciales legadas y autoridades `*`; documentar qué permisos necesita cada aplicación. No revocar claves antiguas sin migrar sus consumidores.
2. En una copia de staging de la base v0.9, ejecutar respaldo y restauración independiente con los scripts de `scripts/`. Conservar **las claves de cifrado originales** de los datos existentes; jamás sustituirlas durante una actualización ordinaria.
3. Establecer JDK 25, PostgreSQL, RabbitMQ y SMTP de prueba. Ejecutar `./mvnw -B clean verify` (PowerShell: `.\mvnw.cmd -B clean verify`). Resolver cualquier fallo sin excluir pruebas de forma artificial.
4. Iniciar el artefacto v0.10 contra una copia v0.9 y confirmar que Flyway aplica V14 y V15. Después, probar en una base vacía V1→V15, permisos y retención; revisar `flyway_schema_history`.
5. Comprobar de forma explícita que los clientes existentes mantienen sus permisos, y que los nuevos deben indicar, por ejemplo, `permissions: ["EMAIL_SEND","EMAIL_READ"]` al registrarse.
6. Usar un archivo sintético para aplicar y liberar un bloqueo legal con `/api/v1/admin/attachments/{id}/retention`. Confirmar que limpieza automática no afecta archivos retenidos ni archivos vinculados a correos/campañas.
7. Ejecutar `scripts/e2e_smoke.py`, `scripts/upgrade_smoke.py` y `scripts/check_openapi_contract.py` contra staging. Confirmar en Mailpit el correo realmente recibido por el SMTP de pruebas.
8. Activar gradualmente los clientes empresariales. Registrar tiempos de cola, fallos, tiempos de respuesta y disponibilidad. Mantener un plan de reversión aprobado: las migraciones de esquema requieren estrategia expand/contract y no deben revertirse eliminando historial Flyway.

## Ejemplo de creación de cliente

```http
POST /api/v1/admin/clients
X-Client-Id: admin
X-Internal-Api-Key: <secreto-administrativo>
Content-Type: application/json

{"clientId":"billing","displayName":"Facturación","requestsPerMinute":120,
 "ttlDays":30,"permissions":["EMAIL_SEND","EMAIL_READ","ATTACHMENT_WRITE","DOCUMENT_SEND"]}
```

Esta operación entrega una clave API nueva: guardarla en el gestor de secretos del servidor consumidor, nunca en Angular ni aplicaciones cliente distribuidas. Rotar antes de su vencimiento y revocar inmediatamente si se sospecha exposición.

## Endpoint de retención

```http
PUT /api/v1/admin/attachments/{attachmentId}/retention
X-Client-Id: admin
X-Internal-Api-Key: <secreto-administrativo>
Content-Type: application/json

{"clientId":"billing","legalHold":true,"retentionUntil":null,
 "reason":"Conservación asociada a expediente interno"}
```

La razón se exige para autorización administrativa y solo se audita su presencia/categoría, no el texto completo. La aplicación no determina automáticamente las obligaciones legales de cada jurisdicción: se deben aprobar los plazos y políticas específicos antes de configurar la retención.

## Limitaciones conocidas

- SMTP `SENT` significa aceptado por el servidor; no garantiza entrega final. El seguimiento de rebotes y entrega exige eventos reales del proveedor elegido.
- Persistencia `bytea` de adjuntos todavía usa memoria; definir límites de concurrencia y tamaño de carga antes de producción.
- Restauración, carga de 10 000 destinatarios, ClamAV real, promtool y pruebas Java25/infraestructura solo están preparadas, **no ejecutadas** en el entorno de edición.
- Revisar las reglas de HTTPS, DNS, SPF/DKIM/DMARC, certificado y salida de red en el despliegue específico.

Consultar `VALIDATION_REPORT.md`, `AUDIT_REPORT.md` y `docs/PRODUCTION_READINESS_ES.md` para distinguir implementación de evidencia probada.
