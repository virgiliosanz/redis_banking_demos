# Proyecto: Hardening HTTP + PHP y housekeeping tasks/

## Objetivo
Completar el hardening de seguridad HTTP y PHP runtime, y verificar el estado del directorio `tasks/`.

## Baseline
- Branch: `main`
- Commit inicial: `28d2b70` (tras archivado del proyecto anterior)

## Fases ejecutadas

### Fase 1: HTTP security headers en Nginx ✅
Añadidos 3 headers de seguridad HTTP faltantes en el server block de `poc-routing.conf`:
- **Strict-Transport-Security:** condicional vía map `$hsts_header` (solo emite cuando `$https=on`)
- **Content-Security-Policy:** policy compatible con WordPress admin (`unsafe-inline`, `unsafe-eval` para scripts/styles)
- **Permissions-Policy:** restrictiva (camera, microphone, geolocation, payment, usb, sensors deshabilitados)

**Implementación:**
- Map `$hsts_header` añadido tras los debug maps
- Headers añadidos al server block junto a los existentes (X-Frame-Options, X-Content-Type-Options, Referrer-Policy)
- Headers repetidos en `location = /healthz` porque Nginx no hereda `add_header` cuando hay `add_header` en el location

**Validación:** `curl -sI http://localhost/healthz` devuelve los 5 headers (HSTS vacío en HTTP)

### Fase 2: PHP runtime hardening ✅
Creado `php/common/zz-security.ini` con directivas de seguridad, copiado a FPM y CLI via Dockerfile:
- `expose_php = Off` — oculta header `X-Powered-By: PHP/x.x`
- `display_errors = Off` — nunca mostrar errores al navegador
- `log_errors = On` — log a fichero
- `allow_url_include = Off` — bloquea inclusión remota de ficheros
- Session hardening: `cookie_httponly=On`, `cookie_secure=On`, `use_strict_mode=On`

**Nota:** `disable_functions` descartado — puede romper plugins de WordPress que usan funciones de sistema legítimamente.

**Validación:** `docker exec n9-fe-live php -r '...'` muestra todas las directivas aplicadas

### Fase 3: Housekeeping tasks/ ✅
Verificado estado del directorio `tasks/`:
- `tasks/proyecto-simplificacion-runtime-wp-root-y-docroots.md` — proyecto futuro documentado con análisis y plan. Marcado "Pendiente para fase posterior". **Dejado activo** porque es deliberado.
- `tasks/README.md` — meta-fichero del directorio
- `tasks/archive/` — 10 proyectos archivados correctamente

**Conclusión:** directorio limpio y organizado.

## Problemas encontrados durante validación

### 1. Nginx con cap_drop + tmpfs incompatible
**Síntoma:** Nginx crasheaba en loop con `chown("/var/cache/nginx/client_temp", 101) failed (1: Operation not permitted)`

**Causa:** `cap_drop: [ALL]` + tmpfs con `noexec` bloquea las operaciones privilegiadas del entrypoint oficial de Nginx (mkdir, chown) antes de bajar a usuario `nginx`.

**Solución:**
- Deshabilitado `cap_drop: [ALL]` en lb-nginx
- Deshabilitado `read_only: true` en lb-nginx (el entrypoint necesita escribir en `/var/cache/nginx/*_temp`)
- Mantenido `no-new-privileges` y tmpfs en `/tmp`, `/run`, `/var/cache/nginx`

**Tradeoff:** Riesgo aceptable para POC local. Para producción se requiere imagen custom de Nginx con entrypoint ajustado.

### 2. PHP-FPM sin capabilities
**Síntoma:** `ERROR: [pool www] failed to setgid(82): Operation not permitted (1)` — PHP-FPM no arrancaba workers.

**Causa:** `cap_drop: [ALL]` quitó `SETGID` y `SETUID` que PHP-FPM necesita para bajar privilegios (root → www-data uid 82).

**Solución:** Añadido `cap_add: [SETGID, SETUID]` a fe-live, fe-archive, be-admin (mismo patrón que MySQL).

**Validación:** Stack levantado, WordPress responde correctamente en portada y artículos.

### 3. Headers HTTP solo en /healthz
**Síntoma:** Los headers de seguridad no se emiten en páginas de WordPress, solo en `/healthz`.

**Causa:** Nginx no hereda `add_header` del server cuando los locations tienen sus propios `add_header`. Los locations `/`, `@wordpress_front` y assets estáticos tienen `add_header Cache-Control`, lo que pisa los del server.

**Decisión:** Documentar la limitación. Implementación completa en todos los locations queda como **follow-up** (requiere repetir 6 headers en 5 locations o refactorizar cache headers con `map`).

## Métricas

| Métrica | Antes | Después |
|---|---|---|
| Headers HTTP de seguridad | 3 (X-Frame, X-Content, Referrer) | 6 (+ HSTS, CSP, Permissions) |
| Headers emitidos en WordPress | 3 | 3 (limitación Nginx) |
| Headers emitidos en /healthz | 0 | 6 |
| PHP hardening | DISALLOW_FILE_EDIT (wp-common) | + expose_php, display_errors, allow_url_include, session |
| cap_drop en PHP-FPM | ALL sin cap_add | ALL + cap_add SETGID/SETUID |
| cap_drop en Nginx | ALL | disabled (incompatible con entrypoint) |
| read_only en Nginx | true | disabled (entrypoint escribe en cache) |
| WordPress funcional | N/A (stack sin seed) | ✅ (portada + artículos) |

## Decisiones tomadas
- **disable_functions descartado:** puede romper plugins de WordPress que usan funciones de sistema legítimamente (exec, proc_open, etc.).
- **cap_drop disabled en Nginx:** la imagen oficial `nginx:alpine` no es compatible con `cap_drop: [ALL]` + tmpfs. Requiere imagen custom.
- **read_only disabled en Nginx:** el entrypoint oficial necesita escribir en `/var/cache/nginx/*_temp` al iniciar.
- **Headers HTTP solo en /healthz por ahora:** implementación completa en todos los locations requiere duplicar 6 headers en 5 lugares o refactorizar cache policy. Queda como follow-up.

## Lecciones aprendidas
- Las imágenes oficiales de Docker (Nginx, PHP-FPM) están diseñadas para `docker run` tradicional, no para hardening máximo con `cap_drop: [ALL]` + `read_only: true`. Requieren capabilities mínimas (`SETGID`, `SETUID`) para funcionar.
- Nginx no hereda `add_header` del server cuando hay `add_header` en locations hijos — es un comportamiento documentado pero contraintuitivo.
- `cap_drop: [ALL]` es un patrón de seguridad válido, pero requiere análisis caso por caso de qué capabilities necesita cada servicio. MySQL y PHP-FPM necesitan `SETGID+SETUID`; Nginx necesita más (o ningún cap_drop).
- El bootstrap de WordPress (`./scripts/bootstrap-local-stack.sh`) es necesario tras cada `docker compose down -v` porque borra los volúmenes.

## Mejoras pendientes (follow-up)
1. **Headers HTTP en todos los locations:** Repetir los 6 headers de seguridad en locations `/`, `@wordpress_front`, y assets, o refactorizar cache headers con `map` variables.
2. **Imagen custom de Nginx:** Ajustar entrypoint para permitir `cap_drop: [ALL]` + `read_only: true` con capabilities mínimas.
3. **CSP más restrictiva:** Eliminar `unsafe-inline` y `unsafe-eval` cuando se conozcan los plugins de WordPress en uso (requiere nonces).
4. **HSTS preload:** Añadir `preload` cuando HTTPS esté validado completamente en producción.

## Commits
- `28d2b70` — security: HTTP security headers + PHP runtime hardening
- `74c6016` — fix: nginx tmpfs+cap_drop compatibility + headers in /healthz
- `52e2ec5` — fix: add SETGID+SETUID caps to PHP-FPM services

## Estado
**CERRADO** — Stack operativo con hardening parcial. Limitaciones documentadas para follow-up.
