# Infraestructura WordPress POC (Docker)

## 1. Objetivo
Definir de forma unificada la arquitectura, decisiones, fases ejecutadas, operacion minima y criterios de paso a produccion de una POC WordPress segmentada en `live`, `archive` y `admin`.

Este documento sustituye al resto de documentos tecnicos y al documento de proyecto anterior. La POC queda descrita aqui de forma integral.

Indice documental vivo: `docs/README.md`.

## 2. Estado del proyecto

### Estado global
- POC implementada y validada en laboratorio con `docker compose`.
- La topologia `live`, `archive` y `admin` ya arranca, enruta y pasa smoke tests.
- El rollover anual `live -> archive` ya esta implementado y probado en laboratorio sobre `2024`.
- Las syncs editorial y de plataforma ya mantienen la consistencia minima entre `live` y `archive`.
- `IA-Ops Bootstrap` ya dispone de colectores read-only, `Nightly Auditor` y `Sentry Agent` minimos.
- `Nightly Auditor` ya puede programarse de forma reproducible con `cron` mediante un bloque gestionado versionado en el repo.
- La salida ya puede conectarse a Telegram con resumen corto para `Nightly Auditor` y `Sentry Agent`.
- `Monit` queda evaluado como opcion reactiva futura, no como requisito del laboratorio actual.
- Siguiente iteracion recomendada: validar entrega real a Telegram con credenciales del entorno y decidir si el disparo reactivo merece `Monit` o una alternativa mas simple.

### Artefactos implementados
- `compose.yaml` operativo con `LB-Nginx`, `FE-Live`, `FE-Archive`, `BE-Admin`, `DB-Live`, `DB-Archive`, `Elastic` y `Cron-Master`.
- Configuracion de `LB-Nginx` versionada en `nginx/lb/`.
- Configuracion minima de `php-fpm` en `php/common/`.
- Plantillas WordPress por contexto en `wordpress/templates/`.
- Bootstrap local y smoke tests en `scripts/`.
- Runbook, checklist y documentos auxiliares en `docs/`.

### Lecciones aprendidas
- La regla de particion pertenece al balanceador, no a WordPress.
- `BE-Admin` debe ser un backend pasivo con dos `docroot`.
- Sin una matriz clara de prioridad en el balanceador, el diseno queda ambiguo.
- El layout de mounts y `docroot` debe definirse antes de aterrizar WordPress.
- Cada contexto necesita su propio `wp-config.php`.
- Reservar `archive.nuevecuatrouno.test` solo para admin elimina la ambigüedad de host canonico del frontend historico.
- Una POC sin checks de salud y smoke tests se vuelve opaca muy rapido.
- El salto a produccion es sobre todo operativo: secretos, backups, despliegue, recuperacion y capacidad.
- En Docker, Nginx debe resolver dinamicamente los upstreams si los contenedores se recrean.
- Para laboratorio, la rotacion minima de logs en el runtime Docker evita crecimiento opaco del host.
- La frontera anual de routing debe salir de configuracion versionada para poder acompasar el rollover con seguridad.
- Los heartbeats de jobs criticos son una fuente mas fiable que una lectura ciega de logs para vigilar cron y syncs.
- Una capa IA-Ops minima ya aporta valor sin LLM externo si consume checks estructurados, logs acotados y drift report.

## 3. Alcance y premisas
- Plataforma base: `docker`.
- Carga esperada en la POC: `2-3` usuarios concurrentes como maximo.
- No se disena para `100k usuarios/dia`.
- No habra alta disponibilidad en esta fase.
- No habra backups en esta fase.
- Elasticsearch no es un componente critico en la POC.
- El objetivo es validar topologia, enrutado y separacion funcional.

## 4. Componentes
| Componente | CPU | RAM | Red interna | Funcion |
| :--- | :--- | :--- | :--- | :--- |
| `LB-Nginx` | 1 | 2 GB | `10.0.0.10` | Entrada publica, TLS, enrutado y FastCGI |
| `FE-Live` | 1 | 2 GB | `10.0.0.11` | `php-fpm` para trafico vivo |
| `FE-Archive` | 1 | 2 GB | `10.0.0.12` | `php-fpm` para contenido historico publico |
| `BE-Admin` | 1 | 2 GB | `10.0.0.13` | `php-fpm` administrativo con dos `docroot`: `live` y `archive` |
| `DB-Live` | 1 | 4 GB | `10.0.0.20` | MySQL de contenido vivo |
| `DB-Archive` | 1 | 4 GB | `10.0.0.21` | MySQL de contenido historico |
| `Elastic` | 1 | 4 GB | `10.0.0.30` | Busqueda comun no critica |
| `Cron-Master` | 1 | 2 GB | `10.0.0.40` | `WP-CLI` y tareas programadas |

## 5. Enrutado funcional

### Entrada publica
- Solo `LB-Nginx` expone `80/443`.
- El host expone SSH por su puerto configurado.
- En esta POC local se usa `http` en `80`; `8443` queda reservado pero no hay TLS real completado.

### Reglas principales
- `nuevecuatrouno.test` sirve trafico general.
- `archive.nuevecuatrouno.test` se reserva para el admin del contexto `archive`.
- Cualquier peticion a `/wp-admin`, login, `admin-ajax.php` o REST de administracion se enruta a `BE-Admin`.
- Si la URL del post cumple el patron `/%year%/%monthnum%/%day%/%postname%/` y el anio es `2015-2023`, la peticion se enruta a `FE-Archive`.
- Si la URL del post cumple el patron `/%year%/%monthnum%/%day%/%postname%/` y el anio es `2024+`, la peticion se enruta a `FE-Live`.
- Cualquier otra peticion se enruta a `FE-Live`.
- El contenido historico publico sigue sirviendose bajo `nuevecuatrouno.test`, no bajo `archive.nuevecuatrouno.test`.

### Orden de evaluacion
1. Trafico administrativo.
2. Host `archive.nuevecuatrouno.test` fuera del admin para redireccion o bloqueo.
3. Patron anual fechado del path.
4. Regla por defecto hacia `FE-Live`.

### Matriz resumida
| Condicion | Backend | Base de datos esperada | Notas |
| :--- | :--- | :--- | :--- |
| Host `nuevecuatrouno.test` y path `/wp-admin` | `BE-Admin` | `DB-Live` | `docroot` admin `live` |
| Host `nuevecuatrouno.test` y path `/wp-login.php` | `BE-Admin` | `DB-Live` | Login de `live` |
| Host `nuevecuatrouno.test` y path `/wp-json/*` administrativo | `BE-Admin` | `DB-Live` | Solo rutas de administracion |
| Host `archive.nuevecuatrouno.test` y path administrativo | `BE-Admin` | `DB-Archive` | `docroot` admin `archive` |
| Host `archive.nuevecuatrouno.test` y path no administrativo | `LB-Nginx` | n/a | Redireccion o bloqueo |
| Path de post fechado con anio `2015-2023` | `FE-Archive` | `DB-Archive` | Regla por URL |
| Cualquier otro caso | `FE-Live` | `DB-Live` | Regla por defecto |

### Ejemplos
- `https://nuevecuatrouno.test/wp-admin/` -> `BE-Admin`
- `https://archive.nuevecuatrouno.test/wp-admin/` -> `BE-Admin`
- `https://nuevecuatrouno.test/2019/05/15/otro-articulo/` -> `FE-Archive`
- `https://nuevecuatrouno.test/2026/04/01/noticia-actual/` -> `FE-Live`
- `https://archive.nuevecuatrouno.test/2018/10/mi-articulo/` -> redireccion o bloqueo en `LB-Nginx`

## 6. Contrato FastCGI y balanceador

### Principios
- `LB-Nginx` es el unico punto de entrada HTTP.
- Los contenedores PHP no exponen HTTP publico, solo `php-fpm`.
- El balanceador decide el backend antes de ejecutar `fastcgi_pass`.
- Cada backend recibe el `docroot` y el contexto FastCGI que le corresponde.

### Variables FastCGI obligatorias
- `SCRIPT_FILENAME` debe resolver contra el `docroot` del backend seleccionado.
- `DOCUMENT_ROOT` debe coincidir con el `docroot` activo.
- `HTTP_HOST` debe preservarse.
- `REQUEST_URI` debe preservarse integra.
- `SERVER_NAME` debe reflejar el host solicitado.
- `HTTPS` debe marcarse cuando la entrada publica sea TLS.

### Upstreams
```nginx
upstream fe_live {
    zone fe_live 64k;
    server fe-live:9000 resolve;
    keepalive 16;
}

upstream fe_archive {
    zone fe_archive 64k;
    server fe-archive:9000 resolve;
    keepalive 16;
}

upstream be_admin {
    zone be_admin 64k;
    server be-admin:9000 resolve;
    keepalive 16;
}
```

### Maps de decision
```nginx
map $host $host_context {
    default live;
    archive.nuevecuatrouno.test archive;
}

map $uri $path_context {
    default live;
    ~^/(2015|2016|2017|2018|2019|2020|2021|2022|2023)/[0-1][0-9]/[0-3][0-9]/ archive;
    ~^/(2024|2025|2026|2027|2028|2029)/[0-1][0-9]/[0-3][0-9]/ live;
}

map $uri $is_admin_path {
    default 0;
    ~^/wp-admin(/|$) 1;
    =/wp-login.php 1;
    =/wp-admin/admin-ajax.php 1;
    ~^/wp-json(/|$) 1;
}

map "$host|$is_admin_path" $archive_host_public_block {
    default 0;
    "archive.nuevecuatrouno.test|0" 1;
}

map "$is_admin_path|$host_context|$path_context" $site_context {
    default live;
    "1|live|live" live;
    "1|live|archive" live;
    "1|archive|live" archive;
    "1|archive|archive" archive;
    "0|live|live" live;
    "0|live|archive" archive;
    "0|archive|live" archive;
    "0|archive|archive" archive;
}

map "$is_admin_path|$site_context" $php_upstream {
    default fe_live;
    "0|live" fe_live;
    "0|archive" fe_archive;
    "1|live" be_admin;
    "1|archive" be_admin;
}

map "$is_admin_path|$site_context" $site_docroot {
    default /var/www/html/live;
    "0|live" /var/www/html/live;
    "0|archive" /var/www/html/archive;
    "1|live" /var/www/html/admin-live;
    "1|archive" /var/www/html/admin-archive;
}
```

### Host `archive.nuevecuatrouno.test`
- Queda reservado al admin del contexto `archive`.
- El trafico no administrativo debe redirigirse a `https://nuevecuatrouno.test$request_uri` o bloquearse con `404/403`.
- Para la POC se recomienda redireccion.

### `/wp-json/`
- En esta POC se enruta todo `/wp-json/` a `BE-Admin` para no romper el admin moderno de WordPress.
- Si mas adelante hay REST publico intensivo, esta regla debera refinarse.

## 7. Red y flujos internos
- `LB-Nginx -> FE-Live` por FastCGI.
- `LB-Nginx -> FE-Archive` por FastCGI.
- `LB-Nginx -> BE-Admin` por FastCGI.
- `FE-Live -> DB-Live` por `3306`.
- `FE-Archive -> DB-Archive` por `3306`.
- `FE-Live -> Elastic` por `9200`.
- `FE-Archive -> Elastic` por `9200`.
- `BE-Admin -> DB-Live` cuando el `docroot` activo sea `live`.
- `BE-Admin -> DB-Archive` cuando el `docroot` activo sea `archive`.
- `BE-Admin -> Elastic` cuando aplique.
- `Cron-Master -> DB-Live` y `DB-Archive`.
- `Cron-Master -> Elastic` cuando aplique.

## 8. Layout Docker y almacenamiento

### Red Docker propuesta
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

### Host path base
- `/tank/data/wp-root`

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
    config/
    uploads/
    mu-plugins/
```

### Mounts clave
- `lb-nginx` monta `live`, `archive`, `admin-live` y `admin-archive` en solo lectura bajo `/var/www/html/...`
- `fe-live` monta `/tank/data/wp-root/live/current/public` -> `/var/www/html/live`
- `fe-archive` monta `/tank/data/wp-root/archive/current/public` -> `/var/www/html/archive`
- `be-admin` monta `admin-live` y `admin-archive`
- `fe-live`, `fe-archive` y `be-admin` montan `/tank/data/wp-root/shared/config` -> `/var/www/shared/config:ro`
- `cron-master` monta cada contexto bajo `/srv/wp/...`
- `db-live` recomendado: `/tank/data/mysql/live` -> `/var/lib/mysql`
- `db-archive` recomendado: `/tank/data/mysql/archive` -> `/var/lib/mysql`
- `elastic` recomendado: `/tank/data/elasticsearch` -> `/usr/share/elasticsearch/data`

### Docroots efectivos
| Servicio | Docroot efectivo |
| :--- | :--- |
| `fe-live` | `/var/www/html/live` |
| `fe-archive` | `/var/www/html/archive` |
| `be-admin` contexto `live` | `/var/www/html/admin-live` |
| `be-admin` contexto `archive` | `/var/www/html/admin-archive` |

### Permisos
- Usuario esperado dentro de contenedores PHP: `www-data`
- UID/GID objetivo: `33:33`
- `lb-nginx` monta contenido en solo lectura
- La escritura debe limitarse a `wp-content/uploads` y caches temporales imprescindibles
- Debe evitarse escritura en core, plugins, temas y configuracion del balanceador

## 9. Configuracion WordPress por contexto

### Principios
- WordPress no decide si una peticion es `live` o `archive`.
- `LB-Nginx` decide el contexto y selecciona backend y `docroot`.
- Cada `docroot` representa una instancia WordPress normal con su propia configuracion.
- `BE-Admin` sigue siendo pasivo: carga `admin-live` o `admin-archive` por el `docroot` que recibe.

### Contextos resultantes
| Contexto | Backend PHP | Host principal | Base de datos | Elastic |
| :--- | :--- | :--- | :--- | :--- |
| `live` | `fe-live` | `nuevecuatrouno.test` | `db-live` | `elastic:9200` |
| `archive` | `fe-archive` | rutas anuales bajo `nuevecuatrouno.test` | `db-archive` | `elastic:9200` |
| `admin-live` | `be-admin` | `nuevecuatrouno.test` en rutas admin | `db-live` | `elastic:9200` |
| `admin-archive` | `be-admin` | `archive.nuevecuatrouno.test` en rutas admin | `db-archive` | `elastic:9200` |

### Regla base de configuracion
- Cada contexto tiene su propio `wp-config.php`.
- Se admite compartir codigo de WordPress y plugins, pero no el fichero de configuracion final.
- Puede reutilizarse un fichero comun en `/var/www/shared/config/wp-common.php`.

### Parametros comunes recomendados
- `WP_ENVIRONMENT_TYPE=staging`
- `DISALLOW_FILE_EDIT=true`
- `AUTOMATIC_UPDATER_DISABLED=true`
- `WP_DEBUG=false`
- `WP_DEBUG_LOG=true`
- `WP_DEBUG_DISPLAY=false`
- `FORCE_SSL_ADMIN=true`
- Claves y salts propios de cada entorno

### Parametros por contexto
- `live`: `DB_HOST=db-live:3306`, `DB_NAME=n9_live`, `DB_USER=wp_live`, `WP_HOME=https://nuevecuatrouno.test`, `WP_SITEURL=https://nuevecuatrouno.test`
- `archive`: `DB_HOST=db-archive:3306`, `DB_NAME=n9_archive`, `DB_USER=wp_archive`, `WP_HOME=https://nuevecuatrouno.test`, `WP_SITEURL=https://nuevecuatrouno.test`
- `admin-live`: `DB_HOST=db-live:3306`, `DB_NAME=n9_live`, `DB_USER=wp_live`, `WP_HOME=https://nuevecuatrouno.test`, `WP_SITEURL=https://nuevecuatrouno.test`
- `admin-archive`: `DB_HOST=db-archive:3306`, `DB_NAME=n9_archive`, `DB_USER=wp_archive`, `WP_HOME=https://archive.nuevecuatrouno.test`, `WP_SITEURL=https://archive.nuevecuatrouno.test`

### Variables de entorno recomendadas
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
- Opcionales: `ELASTICSEARCH_URL`, `WP_DISABLE_ELASTICSEARCH`, `WP_DEBUG_LOG_PATH`

### Politica de host para `archive`
- `archive.nuevecuatrouno.test` no expone frontend publico.
- El frontend historico se sirve bajo `nuevecuatrouno.test` cuando la ruta anual cae en `FE-Archive`.
- `admin-archive` usa `archive.nuevecuatrouno.test` como host administrativo dedicado.

### Elasticsearch y degradacion
- Elasticsearch es comun a ambos contextos, con indices separados y alias unificado de lectura para busqueda.
- Si `Elastic` no responde, WordPress no debe caer por completo.
- La busqueda debe degradar a respuesta vacia controlada, fallback nativo o desactivacion temporal de feature.

### Cron-Master
- Debe ejecutar cada contexto con su `docroot` propio.
- Ejemplos:
```sh
wp --path=/srv/wp/live option get home
wp --path=/srv/wp/archive option get home
wp --path=/srv/wp/admin-live plugin list
wp --path=/srv/wp/admin-archive plugin list
```

## 10. Observabilidad y operacion

### Healthchecks por contenedor
- `lb-nginx`: `curl -fsS http://127.0.0.1/healthz`
- `fe-live`, `fe-archive`, `be-admin`: comprobacion del puerto `9000`; `ping.path=/ping` y `pm.status_path=/status` quedan preparados para evolucion posterior
- `db-live`, `db-archive`: `mysqladmin ping`
- `elastic`: `curl -fsS http://127.0.0.1:9200/_cluster/health`
- `cron-master`: proceso residente o marca de ultima ejecucion correcta

### Logs minimos
- `lb-nginx`: `access_log`, `error_log`
- `php-fpm`: log de proceso y errores PHP
- MySQL: error log y slow query log
- Elastic: logs de aplicacion y arranque
- `cron-master`: salida y errores de jobs
- Runtime Docker con rotacion minima `json-file`, `max-size=10m`, `max-file=3`

### Campos recomendados en access log
- `request_id`
- `host`
- `uri`
- `status`
- `request_time`
- `upstream_response_time`
- `php_upstream`
- `site_context`

### Senales minimas por servicio
- `LB-Nginx`: `5xx`, tiempo medio, fallos a upstream, redirecciones inesperadas
- `php-fpm`: workers activos/libres, lentitud, reinicios
- MySQL: disponibilidad, `Threads_running`, conexiones, slow queries, espacio
- Elastic: disponibilidad HTTP, tiempo de respuesta, estado del nodo
- `cron-master`: exito/fallo, duracion, retrasos

### Umbrales orientativos
- `warning`: aumento sostenido de `5xx`, pocos workers libres, slow queries repetidas, Elastic lento, retraso puntual de jobs
- `critical`: `LB-Nginx` caido, `php-fpm` no responde, DB inaccesible, `BE-Admin` caido, Elastic caido cuando rompe una feature clave, fallo repetido de jobs criticos

### Politica operativa
- Reinicios automaticos solo para procesos claramente caidos.
- No usar reinicios agresivos por memoria como sustituto de diagnostico.
- Antes de tocar aplicacion, aislar si el fallo es de routing, PHP, DB o Elastic.
- Si se comparten logs fuera del host, filtrar IPs publicas y correos.

### Smoke tests minimos
- `GET http://nuevecuatrouno.test/wp-admin/` -> backend admin `live`
- `GET http://archive.nuevecuatrouno.test/wp-admin/` -> backend admin `archive`
- `GET http://nuevecuatrouno.test/2019/05/15/noticia-historica/` -> `fe-archive`
- `GET http://nuevecuatrouno.test/2026/04/01/noticia-actual/` -> `fe-live`
- `GET http://archive.nuevecuatrouno.test/cultura/post/` -> redireccion a `nuevecuatrouno.test`
- `LB-Nginx` responde en `/healthz`
- `php-fpm` responde en el puerto `9000` en `live`, `archive` y `admin`
- MySQL responde a `ping` en ambas DB
- Elastic responde en `_cluster/health`
- `xmlrpc.php` bloqueado con `403`
- `wp-config.php` no expuesto y responde `404`
- Dotfiles y extensiones sensibles bloqueados

## 11. Shortcuts aceptados de la POC
- Sin alta disponibilidad.
- Sin backups.
- Sin storage distribuido: bind mount compartido.
- Sin cache de produccion ni optimizacion para trafico alto.
- Sin clustering de Elasticsearch.
- Sin modelado de capacidad para carga real.
- Sin TLS real terminado en esta iteracion local.
- Sin backend de secretos real, backup verificado ni observabilidad avanzada de produccion.

## 12. Riesgos aceptados en la POC
- Punto unico de fallo en `LB-Nginx`.
- Punto unico de fallo en `Elastic`.
- Dependencia de almacenamiento compartido simple.
- Falta de validacion de restauracion al no haber backups.
- Topologia valida para demo o validacion tecnica, no para produccion.

## 13. Criterios de paso a produccion

### Mapa de huecos
| Area | Estado POC | Requisito de produccion |
| :--- | :--- | :--- |
| Backups | inexistentes | backups verificados y restore probado |
| Secretos | variables simples | gestion de secretos fuera de repo y control de acceso |
| Storage | bind mounts simples | estrategia de persistencia y recuperacion clara |
| HA | inexistente | eliminar o mitigar puntos unicos de fallo |
| Cache | no definida | estrategia de cache de pagina, objeto y opcode |
| Seguridad admin | minima | control de acceso fuerte y superficie reducida |
| Elastic | no critico, 1 nodo | politica degradada definida y operacion estable |
| Despliegue | documental | pipeline reproducible y rollback |
| Observabilidad | minima | alertas operativas y retencion de logs |
| Capacidad | no modelada | pruebas de carga y tuning minimo |

### Requisitos por area
- Backups y restore: backup de ambas DB, configuraciones, `uploads`, retencion documentada y restore probado.
- Secretos: fuera del repositorio, rotacion documentada y separacion por contexto.
- Seguridad: `wp-admin` endurecido con VPN, allowlist IP, SSO o Basic Auth; `xmlrpc.php` controlado; politica de plugins y temas; cabeceras de seguridad.
- Persistencia: separar codigo, configuracion y datos mutables; limitar escritura; estrategia inmutable o versionada; ownership auditado.
- Cache y rendimiento: cache de pagina y objeto, OPcache, tuning de `php-fpm`, indices y tuning de MySQL, pruebas de carga.
- HA y recuperacion: decidir entre HA real o recuperacion rapida, con runbooks y tratamiento explicito de puntos unicos de fallo.
- Despliegue reproducible: `docker compose` o IaC versionado, Nginx versionado, variables inyectadas de forma controlada, rollback.
- Observabilidad real: healthchecks activos en el orquestador, alertas reales, retencion y rotacion de logs, correlacion por `request_id`.
- Gobernanza WordPress: politica de plugins, propagacion de cambios entre contextos, diferencias controladas y migraciones de DB trazables.

### Checklist de promocion
#### Bloqueantes
- [ ] Backups y restore verificados
- [ ] Secretos fuera del repo y gestionados
- [ ] `wp-admin` endurecido
- [ ] Despliegue reproducible
- [ ] Runbook de recuperacion
- [ ] Alertas reales conectadas
- [ ] Smoke tests ejecutables

#### Importantes
- [ ] Cache definida
- [ ] Tuning minimo de PHP y MySQL
- [ ] Politica de plugins y actualizaciones
- [ ] Rotacion de logs
- [ ] Politica clara para Elastic degradado

#### Recomendables
- [ ] HA parcial o estrategia de replica
- [ ] Pruebas de carga
- [ ] Dashboards operativos

### Orden recomendado de evolucion
1. Secretos, despliegue reproducible y hardening de admin.
2. Backups y restore probado.
3. Cache y tuning de rendimiento.
4. Alertas reales y runbooks.
5. HA o estrategia de recuperacion mas robusta.

## 14. Cierre
La POC ya no es solo documental: queda implementada, demostrable y verificable en laboratorio con WordPress real, persistencia compartida, politica de cache y busqueda unificada. Para produccion, los huecos no estan en la arquitectura conceptual sino en la operacion real: seguridad, despliegue, recuperacion, rollover anual, observabilidad y capacidad.
