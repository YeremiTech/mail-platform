# Mejoras Mail Platform v0.10.2

## 1. JaCoCo

Los gates ya no se limitan a API, aplicación y worker. Se elevan a 50 %, 60 % y 65 % respectivamente, y se añaden controles LINE/BRANCH específicos sobre el proveedor SMTP y el almacenamiento PostgreSQL de adjuntos, dos componentes críticos introducidos/reforzados en las últimas versiones.

## 2. Adjuntos por streaming

V17 completa la transición iniciada por V16. PostgreSQL convierte en la propia base cualquier `bytea` histórico mediante `lo_from_bytea`, asigna `content_oid`, exige que todos los adjuntos tengan Large Object y elimina la columna `content`. El adaptador JDBC ya no usa `getBytes()` para adjuntos. El worker consume el `InputStream` por bloques, valida longitud/SHA-256 y utiliza spool temporal para JavaMail, de modo que el heap no contiene el adjunto completo.

## 3. Cabeceras de baja comercial

`List-Unsubscribe` y `List-Unsubscribe-Post: List-Unsubscribe=One-Click` quedan habilitadas por defecto cuando el mensaje es comercial y la URL pública es HTTPS y válida. El endpoint POST de un clic sigue exigiendo el marcador RFC 8058. `MARKETING_ONE_CLICK_ENABLED=false` se conserva como escape de compatibilidad, no como valor normal de producción.

## Validación pendiente

La implementación debe certificarse ejecutando Maven/JaCoCo con JDK 25 y PostgreSQL/SMTP reales. En producción, el relay debe incluir ambas cabeceras en la firma DKIM.
