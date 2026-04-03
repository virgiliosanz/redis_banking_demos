# Persistencia WordPress por contexto

## Objetivo
Dejar explicito que datos se comparten entre los servicios (`fe-live`, `fe-archive`, `be-admin`, `cron-master`) y que datos deben mantenerse aislados, todo sobre un unico docroot WordPress.

## Modelo de docroot unico
Todos los servicios PHP comparten un unico core WordPress en:

```
runtime/wp-root/current/public/
```

Un solo `wp-config.php` dinamico selecciona la base de datos segun la variable `N9_SITE_CONTEXT`:
- Nginx la pasa como `fastcgi_param N9_SITE_CONTEXT` al upstream correspondiente.
- WP-CLI la recibe como variable de entorno.

Valores validos: `live`, `archive`.

## Layout operativo

### Docroot unico
- `runtime/wp-root/current/public/` — core WordPress, `wp-config.php`, `index.php`

### Compartido
- `runtime/wp-root/shared/uploads` — media compartido entre `live` y `archive`
- `runtime/wp-root/shared/mu-plugins` — plugins must-use comunes
- `runtime/wp-root/shared/config` — configuracion no sensible fuera del docroot

### Cache aislada por servicio
- `runtime/wp-root/live/var/cache/wp-content` — cache de `fe-live`
- `runtime/wp-root/archive/var/cache/wp-content` — cache de `fe-archive`

`be-admin` no tiene directorio de cache propio; las operaciones administrativas no generan cache de pagina.

## Regla de montaje
- `uploads` se monta sobre `wp-content/uploads` en todos los servicios.
- `mu-plugins` se monta sobre `wp-content/mu-plugins` en todos los servicios.
- `cache` se monta sobre `wp-content/cache` con un directorio distinto por servicio frontend.
- `shared/config` queda fuera del docroot y solo lectura para los servicios PHP y CLI.

## Politica de escritura
- `uploads` es persistente, compartido y entra en backup.
- `mu-plugins` es codigo comun y debe tratarse como artefacto controlado, no como cache.
- `cache` es descartable y puede purgarse sin impacto de integridad.
- El core WordPress y `wp-config.php` siguen siendo reproducibles por bootstrap; no son el lugar para datos persistentes.

## Ownership esperado
- `cron-master` puede escribir en `uploads` cuando se usen flujos de mantenimiento o WP-CLI.
- Los procesos `php-fpm` escriben solo donde WordPress lo necesita (`uploads` y cache del servicio).
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
