# Persistencia WordPress por contexto

## Objetivo
Dejar explicito que datos se comparten entre `live`, `archive`, `admin-live` y `admin-archive`, y que datos deben mantenerse aislados aunque compartan el mismo codigo WordPress.

## Layout operativo

### Compartido
- `runtime/wp-root/shared/uploads`
- `runtime/wp-root/shared/mu-plugins`
- `runtime/wp-root/shared/config`

### Aislado por contexto
- `runtime/wp-root/live/current/public`
- `runtime/wp-root/archive/current/public`
- `runtime/wp-root/admin-live/current/public`
- `runtime/wp-root/admin-archive/current/public`
- `runtime/wp-root/live/var/cache/wp-content`
- `runtime/wp-root/archive/var/cache/wp-content`
- `runtime/wp-root/admin-live/var/cache/wp-content`
- `runtime/wp-root/admin-archive/var/cache/wp-content`

## Regla de montaje
- `uploads` se monta sobre `wp-content/uploads` en todos los contextos.
- `mu-plugins` se monta sobre `wp-content/mu-plugins` en todos los contextos.
- `cache` se monta sobre `wp-content/cache` con un directorio distinto por contexto.
- `shared/config` queda fuera del docroot y solo lectura para los servicios PHP y CLI.

## Politica de escritura
- `uploads` es persistente, compartido y entra en backup.
- `mu-plugins` es codigo comun y debe tratarse como artefacto controlado, no como cache.
- `cache` es descartable y puede purgarse sin impacto de integridad.
- El core WordPress y `wp-config.php` siguen siendo reproducibles por bootstrap; no son el lugar para datos persistentes.

## Ownership esperado
- `cron-master` puede escribir en `uploads` cuando se usen flujos de mantenimiento o WP-CLI.
- Los procesos `php-fpm` escriben solo donde WordPress lo necesita (`uploads` y cache del contexto).
- `lb-nginx` consume `uploads` y `mu-plugins` en solo lectura.

## Validacion minima
- Un fichero de prueba bajo `shared/uploads` debe verse desde `nuevecuatrouno.test` y `archive.nuevecuatrouno.test`.
- La existencia o purga de cache en `live` no debe afectar al directorio de cache de `archive`.
- Los mounts deben seguir pasando los smoke tests de routing y salud.

## Rollback
1. Parar el stack: `docker compose down`
2. Revertir `compose.yaml` y los scripts de bootstrap afectados.
3. Recrear runtime: `./scripts/bootstrap-local-runtime.sh`
4. Levantar de nuevo: `docker compose up -d --build`
5. Verificar con `./scripts/smoke-routing.sh`, `./scripts/smoke-services.sh` y `./scripts/smoke-persistence.sh`
