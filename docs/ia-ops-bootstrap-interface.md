# Interfaz minima para IA-Ops Bootstrap

## Objetivo
Definir el contrato minimo que necesitara un futuro `IA-Ops Bootstrap` para operar sobre esta plataforma sin improvisar fuentes, comandos o formato de salida.

## Principios
- solo lectura por defecto
- contexto acotado
- datos sensibles filtrados antes de salir del host
- salida accionable y traducible a automatizacion

## Fuentes minimas

### Docker y runtime
- `docker compose ps`
- `docker compose logs --tail N <service>`
- `docker inspect <container>`

### Nginx
- `access.log`
- `error.log`
- request metadata: `request_id`, `host`, `site_context`, `php_upstream`
- cabeceras edge/origen cuando existan: `CF-Connecting-IP`, `X-Forwarded-For`, `CF-Ray`

### PHP / WordPress
- errores PHP
- `wp-cli` en `cron-master`
- estado de plugins, themes y core

### MySQL
- `mysqladmin ping`
- slow query log
- tamano por tabla y base
- conexion y estado basico

### Elasticsearch
- `_cluster/health`
- `_cat/indices`
- estado del alias de lectura

## Comandos minimos permitidos
- `docker compose ps`
- `docker compose logs --tail 500 <service>`
- `docker compose exec -T <service> <comando-de-lectura>`
- `wp --allow-root ...` via `cron-master` en modo de lectura
- `curl` a endpoints internos de health o estado

## Comandos expresamente sensibles
Solo con confirmacion manual:
- borrado de indices
- borrado de posts o datos
- reinicios destructivos
- vaciado de cache si afecta a servicio en curso
- cualquier cambio de configuracion persistente

## Redaccion minima
Antes de enviar contexto a APIs externas:
- redaccion de emails
- redaccion de IPs publicas completas
- preservacion opcional de IPs privadas

## Formato de salida esperado
- `resumen`
- `severidad`
- `evidencias`
- `causa_probable`
- `validaciones_recomendadas`
- `acciones_manuales`
- `playbook_ansible_sugerido`
- `riesgo_si_no_se_actua`

## Casos minimos que debe cubrir
- `lb-nginx` no responde o devuelve `5xx`
- `php-fpm` lento o saturado
- `db-live` o `db-archive` con slow queries repetidas
- `elastic` caido o alias de lectura ausente
- jobs de `cron-master` retrasados o fallidos

## Punto de acoplamiento con el siguiente proyecto
El siguiente proyecto no deberia reconstruir la plataforma.

Deberia consumir:
- los smoke tests existentes
- los logs estructurados actuales
- los runbooks ya documentados
- el estado del rollover anual cuando exista
