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
- Sustituir stubs PHP por core real de WordPress y contenido de prueba controlado.
- Definir estrategia de persistencia para `uploads`, `mu-plugins` y contenido compartido.
- Introducir observabilidad real: logs estructurados, slow query log, metrica y alertado.
- Endurecer MySQL y Elasticsearch con configuracion especifica del entorno.
- Definir politica de acceso administrativo y de exposicion publica del panel.
- Añadir TLS, certificados y politica de renovacion.

## 6. Siguiente proyecto recomendado
### Opcion prioritaria
`proyecto-wordpress-real-y-operacion-minima.md`

### Objetivo
Sustituir los stubs por WordPress real, definir persistencia minima y dejar una base valida para empezar la capa IA-Ops sobre señales reales.

### Alcance propuesto
- Core WordPress real en los cuatro contextos.
- Datos semilla o fixtures controlados.
- Logs utiles para diagnostico.
- Politica minima de acceso admin.
- Preparacion de fuentes para `IA-Ops Bootstrap`.

## 7. Criterio de salida de esta POC
La POC actual ya sirve para demostrar topologia, routing, segmentacion por contexto y operacion basica del stack. No sirve aun para validar funcionalidad real de WordPress ni para extraer conclusiones de rendimiento o seguridad de produccion.
