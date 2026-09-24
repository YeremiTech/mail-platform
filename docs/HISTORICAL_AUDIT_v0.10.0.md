# AUDIT REPORT — Mail Platform v0.10.0-SNAPSHOT

## Resultado de esta fase (sobre el ZIP v0.9.0-mejorado)

Se revisó y modificó **el código existente**, manteniendo ocho módulos Maven, contratos públicos compatibles y migraciones V1–V13 intactas. La revisión ha sido de código fuente y scripts, más las comprobaciones offline enumeradas en `VALIDATION_REPORT.md`. No se considera validado ningún resultado que dependa de un servicio externo no disponible.

### Matriz actual de las doce áreas

Las cifras de partida corresponden a la auditoría anterior y eran **estimaciones, no certificaciones**. Dado que no se ejecutó `mvn verify` con JDK 25 ni el circuito de infraestructura, no se inventan porcentajes nuevos de madurez. Las mejoras constatadas a nivel de fuente no equivalen a funcionamiento de producción demostrado.

| Área | Anterior estimado | Mejora aplicada / evidencia local | Pendiente para medir el porcentaje final |
|---|---:|---|---|
| 1. Envío de correos | 90 % | Contratos antiguos conservados; test de integración de transporte y staging incorporado al CI. | Prueba SMTP real, interpretación de `SENT` como aceptación SMTP y seguimiento de eventos finales/bounces del proveedor. |
| 2. Recuperación de contraseñas | 90 % | Nueva prueba de consumo de grant concurrente con diez consumidores; lógica existente conserva sus restricciones. | Ejecutar con PostgreSQL, fuerza bruta/endpoints y revocación real en el sistema propietario de cuentas. |
| 3. Adjuntos | 85 % | Validación por firma y ClamAV con InputStream; límite 15 MB; multipart respaldado en disco; V15 retención legal. Smoke de firmas por flujo superado. | ClamAV y retención sobre PostgreSQL reales; JDBC `bytea` sigue cargando el adjunto; medir heap/carga concurrente. |
| 4. Seguridad y clientes | 91 % | Allowlist central, permisos obligatorios para clientes nuevos, `*` existente conservado, endpoints/admin protegidos; pruebas de ACL preparadas. | MockMvc/E2E real, migración completa de credenciales legadas y revisión de secreto/roles en producción. |
| 5. Colas y confiabilidad | 87 % | Outbox/worker históricos preservados; script de staging sintético y suites E2E en CI. | Interrupciones reales de PostgreSQL, RabbitMQ, worker y SMTP, idempotencia ante pérdida de confirmación. |
| 6. Arquitectura modular | 91 % | Ocho módulos; guard offline sobre 43 clases de dominio/aplicación pasó; ejecución obligatoria en workflow. | Ejecutar build Maven Java25, verificar con herramientas adicionales que el grafo completo no tenga ciclos. |
| 7. Integración con otros sistemas | 86 % | OpenAPI declara ambos headers de autenticación; script de contrato y clientes anteriores conservados; ejemplo de permisos en documentación. | Ejecutar la API e integrar desde Python, Node.js y .NET; contrastar todos los DTO en release. |
| 8. Plantillas dinámicas | 89 % | Versionado/inmutabilidad y pruebas HTTP anteriores conservadas; permisos de lectura/escritura separados. | Correr tests contra PG, variables adversariales y clientes concurrentes. |
| 9. Envíos masivos | 88 % | Baja pública y exclusiones existentes conservadas; simulador opt-in de 10k en localhost para medir ingreso/drenaje. | Ejecutar carga real, medir latencia transaccional, cuotas, concurrencia de baja y procesador. |
| 10. Observabilidad | 83 % | `X-Request-Id` y MDC; tests para filtro; promtool test cases para alertas backlog/dead/scrape missing. | Ejecutar promtool, desplegar Prometheus/Grafana, simular dependencias caídas y comprobar avisos. |
| 11. Pruebas automatizadas | 70 % | JaCoCo, gates iniciales (API 10%, app 20%, worker 15%), clases nuevas de integración y OWASP en pipeline. Smoke offline Java/parsing aprobado. | Ejecutar JUnit/JaCoCo/Maven, aumentar gates medidos para lógica crítica, corregir toda prueba fallida, verificar análisis de vulnerabilidades. |
| 12. Preparación para producción | 62 % | CI Java25 con Postgres/Rabbit/Mailpit y JAR dinámico, restauración aislada opt-in, guías Windows y auditoría de release. | Ejecutar CI real, backup/restore en PG, pruebas multiinstancia, TLS, alertas, fallo/rollback y objetivos RPO/RTO acordados. |

### Cambios de código confirmados por inspección

1. La provisión administrativa ahora rechaza un nuevo cliente sin `permissions`, con `*` o con una autoridad desconocida; V14 cambia el DEFAULT SQL únicamente para inserciones nuevas. No se revocan permisos de clientes existentes automáticamente.
2. El procesamiento de archivos ya no llama `MultipartFile.getBytes()` en el controlador: utiliza streams separados para validación de firma, ClamAV y persistencia. La copia JDBC a `bytea` persiste como limitación concreta de memoria; ClamAV sigue siendo opcional y de fallo cerrado cuando se habilita.
3. El mantenimiento no elimina archivos bajo `legal_hold`, retenidos hasta el futuro o referenciados por mensajes/campañas. V15 añade los campos y el índice necesarios; el nuevo endpoint es solo administrativo y exige cliente/motivo para actualizar.
4. Un filtro agrega y propaga `X-Request-Id` al logging MDC, rechazando identificadores suministrados con caracteres inseguros. La definición OpenAPI enumera las dos cabeceras de autenticación.
5. GitHub Actions usa Java25, pruebas estáticas, JUnit/JaCoCo, servicios para tests E2E, promtool, Dependency-Check y artefacto JAR descubierto dinámicamente, corrigiendo la referencia obsoleta v0.8.
6. Se añadieron suites para ACL, migraciones V1→V15 y V12→V15, retención de adjuntos y consumo concurrente de grant, además de guard del release, carga controlada y restauración aislada. Los tests de servicios externos aún no han podido ejecutarse localmente.

### Riesgos remanentes y decisiones explícitas

- **Bloqueo de release:** ningún uso empresarial crítico antes del build Java25, PostgreSQL/Flyway y ciclo completo RabbitMQ/SMTP ejecutados satisfactoriamente. No se ha certificado el objetivo >=90 %.
- **Compatibilidad:** el default SQL V14 limitado se aplica únicamente a nuevas filas; las instalaciones existentes deben migrar de `*` a permisos mínimos mediante un inventario controlado. No romper integraciones existentes silenciosamente.
- **Memoria y archivos:** `bytea` sigue requiriendo asignación de hasta 15 MB por adjunto en la capa JDBC. Mantener límites operativos de tamaño/concurrencia y planificar almacenamiento por streaming o externo si la carga lo exige.
- **Mensajería:** no declarar entrega exactamente una vez; un correo `SENT` puede no haber llegado al buzón. Los duplicados ante confirmación SMTP perdida exigen pruebas/reconciliación.
- **Seguridad operativa:** el bloqueo legal es una capacidad técnica; la política de períodos, acceso y conservación debe aprobarla el propietario de datos según jurisdicción y negocio.
- **CI y umbrales:** el workflow y promtool-test se han validado sintácticamente; ejecución con servicios, calidad de cobertura y disponibilidad de feeds NVD están pendientes de confirmar.

---

## Histórico: informe de la iteración v0.9.0

# AUDIT REPORT — Mail Platform v0.9.0-SNAPSHOT

## Alcance

Auditoría realizada directamente sobre el ZIP de Mail Platform v0.8.0 recibido. Se conservó la arquitectura multimódulo y no se reescribieron módulos funcionales. La revisión se centró en estructura, contratos, seguridad, aislamiento por cliente, confiabilidad, migraciones, adjuntos, campañas, webhooks, observabilidad, pruebas y preparación operativa.

## Línea base verificada

- 8 módulos Maven: `mail-domain`, `mail-application`, `mail-template-engine`, `mail-provider-smtp`, `mail-infrastructure`, `mail-worker`, `mail-api` y `mail-client-spring-boot-starter`.
- Java configurado en Maven: 25.
- Spring Boot configurado: 4.1.1.
- 114 fuentes Java principales y 13 archivos de pruebas Java.
- 13 controladores REST.
- El ZIP original contenía migraciones Flyway V1–V12; la actualización agrega V13 sin modificar las migraciones anteriores.
- No se encontraron pruebas anotadas con `@Disabled`.

## Funcionalidades existentes confirmadas por inspección

### Envío y procesamiento

- Envío REST individual con TO/CC/BCC, prioridad, programación, plantillas e idempotencia.
- Outbox transaccional, publicación a RabbitMQ, workers separados por colas y reintentos.
- Claims de procesamiento con token, heartbeat y recuperación de trabajos estancados.
- Registro de intentos de entrega.
- Cancelación de correos y lotes pendientes.
- El estado persistido `SENT` representa aceptación por el proveedor SMTP. No demuestra entrega final al buzón del destinatario.

### Recuperación de contraseñas

- Challenges y grants vinculados al cliente autenticado.
- Consumo mediante actualizaciones condicionales para reducir carreras.
- OTP protegido y payload sensible separado/cifrado.
- El sistema consumidor sigue siendo responsable de cambiar la contraseña y revocar sesiones.

### Adjuntos

- Validación de tamaño y MIME permitido.
- Comprobación de firma/contenido y soporte ClamAV opcional.
- Ownership por `client_id` y comprobaciones contra acceso cruzado.
- Seguimiento explícito de relaciones mensaje-adjunto y limpieza de huérfanos.
- Riesgo pendiente: el flujo HTTP/JDBC materializa el archivo completo en memoria; para archivos grandes conviene evolucionar a streaming real de extremo a extremo.

### Clientes y seguridad

- Claves persistentes aleatorias, HMAC con pepper, expiración y revocación.
- Clave administrativa independiente.
- Cuota por minuto y límites diarios.
- Auditoría administrativa.
- Documentación privada por defecto y endpoints administrativos separados.
- Webhooks HTTPS firmados, secreto cifrado, host previamente autorizado y política contra direcciones privadas.

### Plantillas y campañas

- Plantillas tenant-scoped, versionadas, publicables y fijadas a una versión al encolar.
- Campañas por bloques, importación CSV, consentimiento y suppressions por cliente.
- Baja pública GET sin efectos y POST de confirmación con token opaco, expirable y de un solo uso.
- Revalidación de suppression inmediatamente antes del handoff SMTP.

### Observabilidad y operación

- Actuator, Prometheus, dashboard Grafana y alertas.
- Scripts de backup/restore con controles offline.
- Perfil de producción con legacy API keys deshabilitadas, antivirus obligatorio y SMTP STARTTLS requerido.
- Guía Windows sin Docker ya existente.

## Problemas y brechas detectadas

1. **Permisos de clientes demasiado amplios.** Antes de esta revisión, todo cliente persistente autenticado obtenía únicamente `ROLE_INTERNAL`, por lo que la autorización no diferenciaba operaciones.
2. **Uso de credenciales sin trazabilidad efectiva.** La tabla ya disponía de `last_used_at`, pero la autenticación no lo actualizaba.
3. **Semántica pública del estado `SENT`.** Internamente significa que SMTP aceptó el mensaje, pero el nombre puede interpretarse como entrega final. No se renombró por compatibilidad; requiere una evolución de contrato versionada o un campo adicional probado.
4. **Procesamiento de adjuntos con buffers completos.** El controller usa `MultipartFile.getBytes()` y el almacenamiento JDBC vuelve a materializar bytes. Funcional para el límite actual de 15 MB, pero no cumple un objetivo estricto de streaming de archivos grandes.
5. **Cobertura de pruebas insuficiente para certificar producción.** Existen pruebas relevantes, pero no fue posible ejecutar Maven/JUnit en este entorno.
6. **Validación real de infraestructura pendiente.** PostgreSQL/Flyway, RabbitMQ, SMTP, Mailpit, ClamAV real, pruebas de carga, fallos y restauración real no pudieron ejecutarse aquí.
7. **No existe evidencia suficiente para afirmar 90–95 % de madurez en las 12 áreas.** La fuente contiene varias capacidades maduras, pero la preparación operativa depende de pruebas que siguen pendientes.

## Cambios implementados

### V13 — permisos por operación

Se agregó `V13__client_operation_permissions.sql` con una columna `permissions` por cliente. El valor por defecto es `*` para conservar el comportamiento de clientes existentes.

Permisos soportados:

- `EMAIL_SEND`
- `EMAIL_READ`
- `EMAIL_CANCEL`
- `ATTACHMENT_WRITE`
- `BATCH_READ`
- `BATCH_WRITE`
- `CAMPAIGN_READ`
- `CAMPAIGN_WRITE`
- `TEMPLATE_READ`
- `TEMPLATE_WRITE`
- `SUPPRESSION_READ`
- `SUPPRESSION_WRITE`
- `WEBHOOK_READ`
- `WEBHOOK_WRITE`
- `PASSWORD_RECOVERY`
- `DOCUMENT_SEND`

El wildcard `*` no puede combinarse con permisos explícitos.

### Autorización

- Los clientes persistentes cargan sus permisos después de autenticarse.
- Spring Security aplica permisos según método HTTP y familia de endpoint.
- Las claves legacy mantienen `PERM_ALL` mientras la compatibilidad legacy esté habilitada.
- La clave administrativa mantiene su separación de responsabilidades.

### Ciclo de vida de credenciales

- Una autenticación persistente válida actualiza `mail_api_credential.last_used_at`.
- La comparación de MAC sigue recorriendo todas las credenciales activas antes de decidir el resultado.

### Administración

- Creación y actualización de clientes permite asignar permisos.
- El cambio de permisos queda incluido en el evento de auditoría administrativa existente.

### Versión y documentación

- Reactor Maven actualizado a `0.9.0-SNAPSHOT`.
- `README.md` y `CHANGELOG.md` actualizados.
- Se agregaron este `AUDIT_REPORT.md` y el nuevo `VALIDATION_REPORT.md`.

## Evaluación final basada en evidencia disponible

Los porcentajes siguientes son estimaciones técnicas, no certificaciones de producción. Penalizan de forma explícita la falta de ejecución Maven/JDK 25 e integración real.

| Área | Estado inicial verificado | Cambio principal | Evidencia disponible | Limitación principal | Estimación final |
|---|---|---|---|---|---:|
| Envío de correos | Implementación amplia | Sin cambio incompatible | inspección + smoke offline | E2E SMTP no ejecutado | 90 % |
| Recuperación de contraseñas | Implementación sólida | sin cambio funcional | inspección + pruebas existentes revisadas | JUnit/integración no ejecutados | 90 % |
| Archivos adjuntos | Validación/AV/aislamiento presentes | sin cambio | smoke ClamAV simulado + inspección | buffering completo; AV real pendiente | 86 % |
| Seguridad y clientes | Fuerte, pero rol interno global | permisos por operación + `last_used_at` | parseo, arquitectura, revisión de reglas | MockMvc/DB real pendientes | 91 % |
| Colas y confiabilidad | Outbox, leases, retry, recovery | sin cambio | inspección | RabbitMQ/fault tests pendientes | 88 % |
| Arquitectura modular | 8 módulos coherentes | preservada | guard de arquitectura PASS | Maven reactor no compilado aquí | 92 % |
| Integración con otros sistemas | REST + starter + ejemplos | sin cambio | inspección/documentación | contratos externos no ejecutados | 88 % |
| Plantillas dinámicas | tenant/versionado/pinning | sin cambio | inspección | integración DB/render completa pendiente | 90 % |
| Envíos masivos | campañas/CSV/consent/suppression | sin cambio | smoke CSV + inspección | carga 10k no ejecutada | 89 % |
| Monitoreo | métricas/Prometheus/Grafana | sin cambio | JSON/config validada | alertas no ejercitadas con servicios reales | 84 % |
| Pruebas automatizadas | 13 pruebas Java + smokes | sin aumento de suite | smokes offline PASS | Maven/JUnit no ejecutable aquí | 72 % |
| Preparación para producción | configuración y scripts relevantes | seguridad de permisos mejorada | shell/backup simulado PASS | restore real, carga, chaos, TLS/SMTP real pendientes | 68 % |

## Requisitos concretos pendientes para superar 90 % con evidencia

1. Ejecutar `./mvnw -B clean verify` con JDK 25 y dependencias disponibles.
2. Ejecutar Flyway V1–V13 sobre una base vacía y V13 sobre una copia actualizada desde V12.
3. Añadir/ejecutar pruebas MockMvc de autorización para cada permiso y denegación cruzada.
4. Ejecutar PostgreSQL + RabbitMQ + Mailpit/SMTP real y comprobar recuperación de workers y mensajes duplicados posibles.
5. Probar ClamAV real y política fail-closed del perfil production.
6. Realizar carga de campañas de 10 000 destinatarios y medir memoria/latencia.
7. Migrar adjuntos a procesamiento streaming si el objetivo exige archivos mayores o menor presión de heap.
8. Ejecutar backup y restore reales sobre una base aislada y documentar RPO/RTO observados.
9. Ejercitar alertas Prometheus/Grafana provocando cada condición de fallo.
10. Definir una evolución compatible del contrato para distinguir explícitamente `SMTP_ACCEPTED` de entrega final sin romper consumidores de `SENT`.

## Conclusión

La versión resultante mejora de forma real el aislamiento funcional entre clientes y la trazabilidad de credenciales sin sustituir la arquitectura existente. No se declara certificada para producción ni se afirma que todas las áreas hayan alcanzado 90 %, porque las pruebas Maven/JDK 25 y las integraciones reales no pudieron ejecutarse en este entorno.
