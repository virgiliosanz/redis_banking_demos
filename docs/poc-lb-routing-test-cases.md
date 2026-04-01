# Casos de prueba de routing LB-Nginx

## Objetivo
Trazar los casos minimos que debe cubrir la configuracion de `LB-Nginx` durante la POC.

## Casos funcionales
| Caso | Host | Path | Resultado esperado |
| :--- | :--- | :--- | :--- |
| Admin live | `nuevecuatrouno.test` | `/wp-admin/` | `be_admin`, `site_context=live`, `docroot=/var/www/html/admin-live` |
| Login live | `nuevecuatrouno.test` | `/wp-login.php` | `be_admin`, `site_context=live`, `docroot=/var/www/html/admin-live` |
| REST admin live | `nuevecuatrouno.test` | `/wp-json/wp/v2/users` | `be_admin`, `site_context=live`, `docroot=/var/www/html/admin-live` |
| Admin archive | `archive.nuevecuatrouno.test` | `/wp-admin/` | `be_admin`, `site_context=archive`, `docroot=/var/www/html/admin-archive` |
| Front archive por fecha | `nuevecuatrouno.test` | `/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/` | `fe_archive`, `site_context=archive`, `docroot=/var/www/html/archive` |
| Front live por fecha | `nuevecuatrouno.test` | `/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/` | `fe_live`, `site_context=live`, `docroot=/var/www/html/live` |
| Host archive no admin | `archive.nuevecuatrouno.test` | `/2018/10/mi-articulo/` | `302` a `http://nuevecuatrouno.test$request_uri` |
| Healthcheck | cualquier host | `/healthz` | `200 ok` sin pasar por upstream |

## Campos de log a verificar
- `request_id`
- `host`
- `uri`
- `request_time`
- `upstream_response_time`
- `php_upstream`
- `site_context`

## Validaciones estaticas
- `docker compose config`
- `nginx -t` sobre la configuracion montada

## Validaciones dinamicas posteriores
- Peticiones `curl` contra `/healthz`
- Peticiones con cabecera `Host` simulando `nuevecuatrouno.test` y `archive.nuevecuatrouno.test`
- Verificacion de `302` en host `archive` no admin
- Verificacion de upstream y contexto a traves de logs
