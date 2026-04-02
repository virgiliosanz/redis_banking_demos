# Hardening minimo y siguiente iteracion de la POC

## 1. Objetivo
Dejar la POC operable con un nivel minimo de defensa y con una frontera clara entre lo que ya esta resuelto en laboratorio y lo que sigue pendiente para una evolucion seria.

## 2. Secretos y configuracion
### Regla actual de la POC
- La configuracion no sensible vive versionada en el repo.
- Los secretos locales viven fuera de Git en `./.secrets/`.
- Los secretos de MySQL se inyectan en Compose como `secrets`.
- Los secretos WordPress se montan en solo lectura bajo `/run/project-secrets`.

### Limites aceptados
- `./.secrets/` es valido para laboratorio, no para staging serio ni produccion.
- El bootstrap local genera secretos operativos, pero no rota ni audita su uso.
- No existe integracion con Vault, AWS Secrets Manager, Doppler o equivalente.

### Recomendacion para la siguiente iteracion
- Sustituir `./.secrets/` por un backend de secretos real.
- Mantener el contrato de ficheros montados solo lectura para no contaminar `compose` con valores inline.
- Separar secretos por entorno y por contexto WordPress.

## 3. Hardening minimo aplicado
- `server_tokens off` en Nginx.
- Cabeceras basicas: `X-Frame-Options`, `X-Content-Type-Options` y `Referrer-Policy`.
- Bloqueo de `xmlrpc.php`.
- Bloqueo de acceso a dotfiles y a extensiones sensibles de configuracion o volcado.
- `no-new-privileges` en los contenedores del stack.
- Rotacion minima de logs Docker con `json-file`, `10m` por fichero y `3` rotaciones.

## 4. Limites de hardening que siguen abiertos
- No hay TLS real terminado en la propia POC.
- No hay allowlist de acceso a `/wp-admin/`.
- No hay rate limiting en Nginx.
- No hay autenticacion ni seguridad activada en Elasticsearch.
- No hay escaneo de imagenes ni pipeline CI.
- No hay backup verificado ni prueba de restauracion.

## 5. Gaps hacia produccion
- Formalizar el script anual de rollover de contenido entre `live` y `archive`.
- Sustituir secretos locales de laboratorio por un backend de secretos real.
- Introducir observabilidad real: logs estructurados, slow query log, metrica y alertado.
- Endurecer MySQL y Elasticsearch con configuracion especifica del entorno.
- Definir politica de acceso administrativo y de exposicion publica del panel.
- Añadir TLS, certificados y politica de renovacion.

## 6. Siguiente proyecto recomendado
### Opcion prioritaria
`proyecto-rollover-anual-e-ia-ops-bootstrap.md`

### Objetivo
Formalizar la operacion anual `live -> archive` y dejar un contrato claro para que IA-Ops opere sobre una plataforma WordPress ya real, con busqueda unificada y señales utiles.

### Alcance propuesto
- Script anual de rollover con `dry-run`, validacion y rollback.
- Informe de ejecucion y validacion de conteos, URLs e indexacion.
- Contrato minimo de logs, healthchecks y comandos de diagnostico para `IA-Ops Bootstrap`.
- Integracion de observabilidad minima real: slow query log, logs estructurados y estados de backup.

## 7. Criterio de salida de esta POC
La POC actual ya sirve para demostrar topologia, routing, segmentacion por contexto, WordPress real, persistencia compartida, politica de cache, busqueda unificada y operacion basica del stack. No sirve aun para extraer conclusiones de rendimiento o seguridad de produccion.
