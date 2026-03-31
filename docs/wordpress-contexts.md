# Configuracion WordPress por contexto para la POC

## Objetivo
Definir como se configuran las instancias WordPress de `live`, `archive`, `admin-live` y `admin-archive` sin introducir logica de particion dentro de PHP.

## Principios cerrados
- WordPress no decide si una peticion es `live` o `archive`.
- `LB-Nginx` decide el contexto y selecciona backend y `docroot`.
- Cada `docroot` representa una instancia WordPress normal con su propia configuracion.
- `BE-Admin` sigue siendo pasivo: carga `admin-live` o `admin-archive` por el `docroot` que recibe.

## Contextos resultantes
| Contexto | Backend PHP | Host principal | Base de datos | Elastic |
| :--- | :--- | :--- | :--- | :--- |
| `live` | `fe-live` | `nuevecuatrouno.com` | `db-live` | `elastic:9200` |
| `archive` | `fe-archive` | rutas anuales bajo `nuevecuatrouno.com` | `db-archive` | `elastic:9200` |
| `admin-live` | `be-admin` | `nuevecuatrouno.com` en rutas admin | `db-live` | `elastic:9200` |
| `admin-archive` | `be-admin` | `archive.nuevecuatrouno.com` en rutas admin | `db-archive` | `elastic:9200` |

## Modelo de configuracion recomendado

### Regla base
- Cada contexto tiene su propio `wp-config.php`.
- Se admite compartir codigo de WordPress y plugins, pero no se comparte el fichero de configuracion final.
- La POC puede reutilizar fragmentos comunes mediante un fichero incluido, pero la decision final debe quedar resuelta por contexto.

### Estructura sugerida
```text
/tank/data/wp-root/
  live/current/public/wp-config.php
  archive/current/public/wp-config.php
  admin-live/current/public/wp-config.php
  admin-archive/current/public/wp-config.php
  shared/config/wp-common.php
```

### Patron recomendado
```php
<?php
require_once '/var/www/shared/config/wp-common.php';

define('DB_NAME', 'n9_live');
define('DB_USER', 'wp_live');
define('DB_PASSWORD', getenv('WP_DB_PASSWORD'));
define('DB_HOST', 'db-live:3306');

define('WP_HOME', 'https://nuevecuatrouno.com');
define('WP_SITEURL', 'https://nuevecuatrouno.com');

define('ELASTICSEARCH_HOST', 'http://elastic:9200');
define('WP_ENVIRONMENT_TYPE', 'staging');

require_once ABSPATH . 'wp-settings.php';
```

El patron es el mismo para el resto de contextos, cambiando host, DB y parametros especificos.

## Parametros comunes
- `WP_ENVIRONMENT_TYPE=staging`
- `DISALLOW_FILE_EDIT=true`
- `AUTOMATIC_UPDATER_DISABLED=true`
- `WP_DEBUG=false`
- `WP_DEBUG_LOG=true`
- `WP_DEBUG_DISPLAY=false`
- `FORCE_SSL_ADMIN=true`
- Claves y salts propios de cada entorno
- Prefijo de tablas explicito por contexto o por base de datos

## Parametros por contexto

### `live`
- `DB_HOST=db-live:3306`
- `DB_NAME=n9_live`
- `DB_USER=wp_live`
- `WP_HOME=https://nuevecuatrouno.com`
- `WP_SITEURL=https://nuevecuatrouno.com`
- `UPLOADS` segun estructura local del sitio `live`

### `archive`
- `DB_HOST=db-archive:3306`
- `DB_NAME=n9_archive`
- `DB_USER=wp_archive`
- `WP_HOME=https://nuevecuatrouno.com`
- `WP_SITEURL=https://nuevecuatrouno.com`
- La seleccion de contenido historico la hace `LB-Nginx` por ruta; WordPress `archive` sigue viendo el host principal

### `admin-live`
- `DB_HOST=db-live:3306`
- `DB_NAME=n9_live`
- `DB_USER=wp_live`
- `WP_HOME=https://nuevecuatrouno.com`
- `WP_SITEURL=https://nuevecuatrouno.com`
- `FORCE_SSL_ADMIN=true`

### `admin-archive`
- `DB_HOST=db-archive:3306`
- `DB_NAME=n9_archive`
- `DB_USER=wp_archive`
- `WP_HOME=https://archive.nuevecuatrouno.com`
- `WP_SITEURL=https://archive.nuevecuatrouno.com`
- `FORCE_SSL_ADMIN=true`

## Variables de entorno recomendadas

### Variables comunes a PHP
- `WP_DB_PASSWORD`
- `WP_AUTH_KEY`
- `WP_SECURE_AUTH_KEY`
- `WP_LOGGED_IN_KEY`
- `WP_NONCE_KEY`
- `WP_AUTH_SALT`
- `WP_SECURE_AUTH_SALT`
- `WP_LOGGED_IN_SALT`
- `WP_NONCE_SALT`
- `WP_ENVIRONMENT_TYPE`

### Variables opcionales
- `ELASTICSEARCH_URL=http://elastic:9200`
- `WP_DISABLE_ELASTICSEARCH=0`
- `WP_DEBUG_LOG_PATH=/var/log/php/wp-debug.log`

### Regla importante
- No meter secretos en la documentacion ni en el repositorio.
- En POC se pueden inyectar por variables de entorno de Docker.

## Fichero comun recomendado

### `wp-common.php`
Este fichero puede centralizar:
- ajustes de depuracion,
- endurecimiento basico,
- lectura de variables de entorno,
- y comportamiento comun cuando `Elastic` no este disponible.
- Si hace falta, resolucion controlada de `home` y `siteurl` para `archive` cuando se sirva desde mas de un host

### Lo que no debe hacer
- decidir entre `live` y `archive`
- inferir contexto por URL
- reimplementar reglas del balanceador

## Politica de host para `archive`

### Decision cerrada
- `archive.nuevecuatrouno.com` no expone frontend publico de archivo.
- Ese host queda reservado al admin del contexto `archive`.
- El frontend publico historico se sirve bajo `nuevecuatrouno.com` cuando la ruta anual cae en `FE-Archive`.

### Consecuencia
- Desaparece la tension de `WP_HOME` y `WP_SITEURL` para el frontend `archive`.
- `admin-archive` puede seguir usando `archive.nuevecuatrouno.com` como host administrativo dedicado.

## Carga de `BE-Admin`

### Decision cerrada
- `BE-Admin` no cambia configuracion en tiempo de ejecucion.
- El contexto administrativo viene dado por el `docroot` montado y por el `wp-config.php` presente en ese arbol.

### Resultado
- `/var/www/html/admin-live/wp-config.php` apunta a `db-live`
- `/var/www/html/admin-archive/wp-config.php` apunta a `db-archive`

Esto evita logica condicional adicional dentro del contenedor administrativo.

## Elasticsearch y degradacion

### Regla para la POC
- Elasticsearch es comun a ambos contextos.
- Si `Elastic` no responde, WordPress no debe caer por completo.

### Comportamiento recomendado
- las paginas normales deben seguir sirviendose
- las funciones de busqueda pueden degradarse a:
- respuesta vacia controlada
- fallback al buscador nativo de WordPress
- o desactivacion temporal de la feature

### Lo que no conviene
- que el bootstrap de WordPress falle solo porque `Elastic` no responde
- dependencias duras en plugins que hagan `fatal error` sin el servicio

## Cron-Master

### Regla base
- `cron-master` debe ejecutar cada contexto con su `docroot` propio.

### Ejemplos
- `wp --path=/srv/wp/live option get home`
- `wp --path=/srv/wp/archive option get home`
- `wp --path=/srv/wp/admin-live plugin list`
- `wp --path=/srv/wp/admin-archive plugin list`

### Criterio
- No hay autodeteccion de contexto.
- Cada tarea apunta explicitamente al `path` correcto.

## Recomendaciones de implementacion
- Mantener un fichero comun reutilizable y cuatro `wp-config.php` pequenos.
- Mantener plugins y temas iguales entre `live` y `admin-live` salvo necesidad real.
- Mantener plugins y temas iguales entre `archive` y `admin-archive` salvo necesidad real.
- Evitar escribir plugins distintos solo para resolver el routing.

## Criterios de cierre de fase
- Cada contexto tiene DB, host y `docroot` definidos.
- `BE-Admin` queda resuelto solo por `docroot`.
- Los secretos salen del repo.
- Existe comportamiento degradado documentado para `Elastic`.
- `cron-master` puede ejecutar cada contexto sin heuristicas.
