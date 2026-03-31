# Observabilidad y operacion para la POC

## Objetivo
Definir el minimo operativo para saber si la POC esta sana, detectar rapido fallos de routing o de servicios, y tener una base clara para automatizar chequeos.

## Alcance
- Healthchecks por contenedor
- Logs minimos utiles
- Senales clave por servicio
- Alertas `warning` y `critical`
- Pruebas de humo de routing y disponibilidad

## Principios
- La POC no necesita una plataforma completa de observabilidad.
- Si un check no ayuda a diagnosticar rapido, sobra.
- El objetivo es detectar caidas, routing roto, `php-fpm` saturado, MySQL lento y degradacion de Elastic.
- Siempre que sea posible, los chequeos deben poder traducirse a `docker compose`, `Monit` o playbooks.

## Healthchecks por contenedor

### `lb-nginx`
- Check local: `curl -fsS http://127.0.0.1/healthz`
- Resultado esperado: `200 OK`
- Frecuencia orientativa: cada `30s`

### `fe-live`
- Exponer `ping.path = /ping` en `php-fpm`
- Exponer `pm.status_path = /status`
- Check local recomendado: `cgi-fcgi -bind -connect 127.0.0.1:9000` contra `/ping`
- Resultado esperado: `pong`

### `fe-archive`
- Igual que `fe-live`

### `be-admin`
- Igual que `fe-live`
- Importa especialmente porque concentra admin de `live` y `archive`

### `db-live`
- Check: `mysqladmin ping -h 127.0.0.1 -u root -p...`
- Resultado esperado: `mysqld is alive`

### `db-archive`
- Igual que `db-live`

### `elastic`
- Check: `curl -fsS http://127.0.0.1:9200/_cluster/health`
- Resultado esperado en POC: respuesta HTTP valida aunque el estado sea `yellow`

### `cron-master`
- Check de proceso si hay scheduler residente
- O, si no, check por marca de ultima ejecucion correcta
- Resultado esperado: timestamp reciente dentro de ventana esperada

## Endpoints y probes recomendados

### Publicos via `LB-Nginx`
- `GET /healthz` en `nuevecuatrouno.com`
- `GET /healthz` en `archive.nuevecuatrouno.com`

### Internos PHP
- `/ping`
- `/status`

### Recomendacion
- No exponer `/status` o `/ping` publicamente sin control.
- Restringirlos a red interna o protegerlos en `LB-Nginx`.

## Logs minimos por servicio

### `lb-nginx`
- `access_log`
- `error_log`

### Formato recomendado de `access_log`
- `request_id`
- `host`
- `uri`
- `status`
- `request_time`
- `upstream_response_time`
- `php_upstream`
- `site_context`

### `fe-live`, `fe-archive`, `be-admin`
- log de `php-fpm`
- log de errores PHP
- opcional: slow log de `php-fpm`

### `db-live`, `db-archive`
- error log
- slow query log
- eventos de arranque/parada

### `elastic`
- log de aplicacion
- log de arranque

### `cron-master`
- salida estandar de jobs
- errores de jobs
- marca de ultima ejecucion correcta por tarea critica

## Senales minimas por servicio

### `lb-nginx`
- `5xx` por minuto
- tiempo medio de respuesta
- fallos de conexion a upstream
- volumen de redirecciones inesperadas a dominio principal

### `fe-live`, `fe-archive`, `be-admin`
- workers activos
- workers libres
- cola de peticiones si aplica
- tiempo de ejecucion lento
- reinicios del proceso

### `db-live`, `db-archive`
- disponibilidad
- `Threads_running`
- conexiones abiertas
- slow queries
- espacio ocupado

### `elastic`
- disponibilidad HTTP
- tiempo de respuesta
- estado del nodo

### `cron-master`
- exito/fallo de tareas
- duracion
- retraso respecto a horario esperado

## Umbrales orientativos para la POC

### `warning`
- `LB-Nginx`: aumento sostenido de `5xx`
- `php-fpm`: pocos workers libres o tiempos altos sostenidos
- MySQL: slow queries repetidas o `Threads_running` anomalo
- Elastic: respuesta lenta pero servicio vivo
- `cron-master`: retraso puntual o un job no critico fallido

### `critical`
- `LB-Nginx` caido
- `php-fpm` no responde en cualquier backend
- `db-live` o `db-archive` no responden
- `BE-Admin` caido
- Elastic caido cuando rompe una feature clave de la demo
- fallo repetido de jobs criticos

## Politica operativa

### Reinicios automaticos
- Admitidos solo para procesos claramente caidos.
- No usar reinicios agresivos por memoria alta como sustituto de diagnostico.

### Escalada
- Primero verificar si el problema es de routing, backend PHP, DB o Elastic.
- Solo despues mirar ajustes de aplicacion.

### Privacidad
- Si se comparten logs fuera del host, filtrar IPs publicas y correos.
- Mantener `request_id` para correlacion sin exponer datos sensibles.

## Smoke tests minimos

### Routing
- `GET https://nuevecuatrouno.com/wp-admin/` -> backend admin `live`
- `GET https://archive.nuevecuatrouno.com/wp-admin/` -> backend admin `archive`
- `GET https://nuevecuatrouno.com/2019/05/noticia/` -> backend `fe-archive`
- `GET https://nuevecuatrouno.com/actualidad/post/` -> backend `fe-live`
- `GET https://archive.nuevecuatrouno.com/cultura/post/` -> redireccion a `nuevecuatrouno.com`

### Disponibilidad
- `LB-Nginx` responde en `/healthz`
- `php-fpm` responde `pong` en `live`, `archive` y `admin`
- MySQL responde a `ping` en ambas DB
- Elastic responde en `_cluster/health`

### WordPress
- `wp --path=/srv/wp/live option get home`
- `wp --path=/srv/wp/archive option get home`
- `wp --path=/srv/wp/admin-live plugin list`
- `wp --path=/srv/wp/admin-archive plugin list`

## Automatizacion recomendada

### Nivel 1
- Docker healthchecks
- script de smoke tests con `curl`
- script de verificacion WP-CLI

### Nivel 2
- Monit para procesos
- rotacion de logs
- alertas simples a correo o webhook

## Comandos orientativos

### `LB-Nginx`
```sh
curl -fsS http://127.0.0.1/healthz
tail -n 100 /tank/data/nginx/logs/poc-error.log
```

### MySQL
```sh
mysqladmin ping -h db-live -u root -p
mysqladmin ping -h db-archive -u root -p
```

### Elastic
```sh
curl -fsS http://elastic:9200/_cluster/health
```

### WP-CLI
```sh
wp --path=/srv/wp/live option get home
wp --path=/srv/wp/archive option get home
```

## Criterios de cierre de fase
- Cada contenedor tiene check de salud definido.
- Cada servicio tiene logs minimos definidos.
- Existen umbrales `warning` y `critical`.
- Existe una bateria corta de smoke tests.
- La POC se puede diagnosticar sin intuicion ni acceso manual ad hoc.
