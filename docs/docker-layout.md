# Docker layout y docroots para la POC

## Objetivo
Fijar el layout real de contenedores, red, mounts, `docroot` y permisos para la POC WordPress segmentada en `live`, `archive` y `admin`.

## Criterios de diseno
- La topologia sigue siendo POC, no produccion.
- Se prioriza simplicidad operativa sobre aislamiento fino.
- `LB-Nginx` necesita ver el mismo arbol de contenidos para resolver estaticos y `SCRIPT_FILENAME`.
- Los contenedores PHP deben tener `docroot` inequívoco.
- `BE-Admin` es un unico contenedor con dos `docroot`.

## Red Docker propuesta

### Red unica de POC
- Nombre recomendado: `wp-poc-net`
- Tipo: `bridge`
- Resolucion DNS interna por nombre de servicio Docker

### Hostnames internos esperados
- `lb-nginx`
- `fe-live`
- `fe-archive`
- `be-admin`
- `db-live`
- `db-archive`
- `elastic`
- `cron-master`

## Layout de servicios
| Servicio | Imagen o rol | Puertos expuestos | Red | Notas |
| :--- | :--- | :--- | :--- | :--- |
| `lb-nginx` | Nginx | `80:80`, `443:443` | `wp-poc-net` | Unico punto de entrada |
| `fe-live` | PHP-FPM | ninguno | `wp-poc-net` | Solo FastCGI interno |
| `fe-archive` | PHP-FPM | ninguno | `wp-poc-net` | Solo FastCGI interno |
| `be-admin` | PHP-FPM | ninguno | `wp-poc-net` | Admin `live` y `archive` |
| `db-live` | MySQL | ninguno | `wp-poc-net` | Solo red interna |
| `db-archive` | MySQL | ninguno | `wp-poc-net` | Solo red interna |
| `elastic` | Elasticsearch | ninguno | `wp-poc-net` | No critico |
| `cron-master` | PHP-CLI o contenedor operativo | ninguno | `wp-poc-net` | Tareas programadas |

## Layout de datos en host

### Raiz compartida
- Host path base: `/tank/data/wp-root`

### Estructura recomendada
```text
/tank/data/wp-root/
  live/
    current/
      public/
        wp-content/
        wp-config.php
  archive/
    current/
      public/
        wp-content/
        wp-config.php
  admin-live/
    current/
      public/
        wp-content/
        wp-config.php
  admin-archive/
    current/
      public/
        wp-content/
        wp-config.php
  shared/
    uploads/
    mu-plugins/
```

### Decision de POC
- Se permite duplicacion parcial de arboles si simplifica la separacion de contextos.
- No se fuerza aun una estrategia de release symlink o imagen inmutable.
- `shared/` existe solo si realmente se decide compartir contenido mutable entre instancias.

## Mounts por servicio

### `lb-nginx`
- `/tank/data/wp-root/live/current/public` -> `/var/www/html/live:ro`
- `/tank/data/wp-root/archive/current/public` -> `/var/www/html/archive:ro`
- `/tank/data/wp-root/admin-live/current/public` -> `/var/www/html/admin-live:ro`
- `/tank/data/wp-root/admin-archive/current/public` -> `/var/www/html/admin-archive:ro`
- `/tank/data/nginx/certs` -> `/etc/nginx/certs:ro`
- `/tank/data/nginx/conf.d` -> `/etc/nginx/conf.d:ro`
- `/tank/data/nginx/logs` -> `/var/log/nginx`

### `fe-live`
- `/tank/data/wp-root/live/current/public` -> `/var/www/html/live`
- Opcional: `/tank/data/wp-root/shared/uploads` -> `/var/www/html/live/wp-content/uploads`
- Opcional: `/tank/data/wp-root/shared/mu-plugins` -> `/var/www/html/live/wp-content/mu-plugins`

### `fe-archive`
- `/tank/data/wp-root/archive/current/public` -> `/var/www/html/archive`
- Opcional: `/tank/data/wp-root/shared/uploads` -> `/var/www/html/archive/wp-content/uploads`
- Opcional: `/tank/data/wp-root/shared/mu-plugins` -> `/var/www/html/archive/wp-content/mu-plugins`

### `be-admin`
- `/tank/data/wp-root/admin-live/current/public` -> `/var/www/html/admin-live`
- `/tank/data/wp-root/admin-archive/current/public` -> `/var/www/html/admin-archive`
- Opcional: `/tank/data/wp-root/shared/uploads` -> `/var/www/html/admin-live/wp-content/uploads`
- Opcional: `/tank/data/wp-root/shared/uploads` -> `/var/www/html/admin-archive/wp-content/uploads`

### `cron-master`
- `/tank/data/wp-root/live/current/public` -> `/srv/wp/live`
- `/tank/data/wp-root/archive/current/public` -> `/srv/wp/archive`
- `/tank/data/wp-root/admin-live/current/public` -> `/srv/wp/admin-live`
- `/tank/data/wp-root/admin-archive/current/public` -> `/srv/wp/admin-archive`

### `db-live`
- Volumen dedicado Docker o bind mount:
- recomendado POC: `/tank/data/mysql/live` -> `/var/lib/mysql`

### `db-archive`
- Volumen dedicado Docker o bind mount:
- recomendado POC: `/tank/data/mysql/archive` -> `/var/lib/mysql`

### `elastic`
- Volumen dedicado Docker o bind mount:
- recomendado POC: `/tank/data/elasticsearch` -> `/usr/share/elasticsearch/data`

## Docroots efectivos por servicio
| Servicio | Docroot efectivo |
| :--- | :--- |
| `fe-live` | `/var/www/html/live` |
| `fe-archive` | `/var/www/html/archive` |
| `be-admin` contexto `live` | `/var/www/html/admin-live` |
| `be-admin` contexto `archive` | `/var/www/html/admin-archive` |
| `lb-nginx` contexto `live` | `/var/www/html/live` |
| `lb-nginx` contexto `archive` | `/var/www/html/archive` |
| `lb-nginx` admin `live` | `/var/www/html/admin-live` |
| `lb-nginx` admin `archive` | `/var/www/html/admin-archive` |

## Permisos y usuario

### Regla base
- Usuario esperado dentro de contenedores PHP: `www-data`
- UID/GID objetivo: `33:33`

### Criterio para la POC
- El host debe exponer los arboles de WordPress con permisos compatibles con `33:33`.
- `lb-nginx` monta contenido en solo lectura.
- Los contenedores PHP pueden tener escritura solo donde WordPress la necesite realmente.

### Escritura permitida minima
- `wp-content/uploads`
- caches temporales solo si son imprescindibles

### Escritura a evitar
- core de WordPress
- plugins y temas en caliente
- configuracion del balanceador

## Logs por servicio
- `lb-nginx`: `/tank/data/nginx/logs`
- `fe-live`: log de `php-fpm` y errores PHP en directorio propio de servicio
- `fe-archive`: log de `php-fpm` y errores PHP en directorio propio de servicio
- `be-admin`: log de `php-fpm` y errores PHP en directorio propio de servicio
- `db-live`: logs del motor en almacenamiento propio
- `db-archive`: logs del motor en almacenamiento propio
- `cron-master`: salida de jobs y errores en directorio propio

### Directorios recomendados
```text
/tank/data/logs/
  fe-live/
  fe-archive/
  be-admin/
  cron-master/
  db-live/
  db-archive/
```

## Nombres internos y conectividad
| Origen | Destino | Metodo |
| :--- | :--- | :--- |
| `lb-nginx` | `fe-live:9000` | FastCGI |
| `lb-nginx` | `fe-archive:9000` | FastCGI |
| `lb-nginx` | `be-admin:9000` | FastCGI |
| `fe-live` | `db-live:3306` | MySQL |
| `fe-archive` | `db-archive:3306` | MySQL |
| `be-admin` | `db-live:3306` | MySQL |
| `be-admin` | `db-archive:3306` | MySQL |
| `fe-live` | `elastic:9200` | HTTP interno |
| `fe-archive` | `elastic:9200` | HTTP interno |
| `be-admin` | `elastic:9200` | HTTP interno |
| `cron-master` | `db-live:3306` | MySQL |
| `cron-master` | `db-archive:3306` | MySQL |
| `cron-master` | `elastic:9200` | HTTP interno |

## Decision sobre contenedores PHP

### Opcion elegida para la POC
- `fe-live`, `fe-archive` y `be-admin` son tres contenedores separados.
- `be-admin` concentra los dos contextos administrativos por `docroot`.

### Motivo
- Mantiene claro el papel de cada backend.
- Evita que `FE-Live` o `FE-Archive` tengan responsabilidades administrativas.
- Sigue siendo suficientemente simple para una POC.

## Criterios de cierre de esta fase
- Existe una estructura de directorios concreta en host.
- Cada servicio tiene mounts definidos.
- Cada backend PHP tiene `docroot` inequívoco.
- `lb-nginx` puede resolver estaticos y `SCRIPT_FILENAME`.
- Queda claro donde vive cada tipo de dato.

## Riesgos aceptados
- Exceso de bind mounts para una POC pequena.
- Posible duplicacion de contenido entre `live`, `archive` y `admin`.
- Sin estrategia de releases ni rollback todavia.
- Sin separar aun secretos, solo layout.

## Siguiente fase recomendada
- Definir configuracion WordPress por contexto:
- `live`
- `archive`
- `admin-live`
- `admin-archive`
