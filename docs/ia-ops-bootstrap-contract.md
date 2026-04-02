# Contrato operativo de IA-Ops Bootstrap

## 1. Objetivo
Definir de forma cerrada que puede leer `IA-Ops Bootstrap`, como debe sanear el contexto, que checks minimos debe ejecutar y como debe devolver el resultado.

Este contrato aplica a:
- `Sentry Agent`: flujo reactivo ante incidencia
- `Nightly Auditor`: flujo programado de auditoria

No autoriza remediacion automatica.

## 2. Principios
- solo lectura por defecto
- contexto acotado y reproducible
- datos sensibles filtrados antes de salir del host
- salida accionable y traducible a automatizacion
- severidades simples y defendibles
- las validaciones funcionales existentes del repo forman parte del contexto base

## 3. Fuentes permitidas

### 3.1 Host
- memoria
- `load average`
- disco
- CPU
- indicador simple de `iowait` si el host lo expone
- estado del daemon Docker

### 3.2 Runtime Docker
- `docker compose ps`
- `docker inspect <container>`
- `docker compose logs --tail N <service>`

### 3.3 Nginx
- `access.log`
- `error.log`
- trazas con `request_id`, `host`, `site_context`, `php_upstream`
- cabeceras edge/origen cuando existan: `CF-Connecting-IP`, `X-Forwarded-For`, `CF-Ray`

### 3.4 PHP / WordPress
- `wp-cli` via `cron-master` en modo lectura
- estado de plugins, theme y core
- login/admin y smokes funcionales del repo

### 3.5 MySQL
- `mysqladmin ping`
- processlist resumido en modo solo lectura
- slow query log
- tamano por base o tabla
- estado basico de conectividad

### 3.6 Elasticsearch
- `_cluster/health`
- `_cat/indices`
- `_alias/n9-search-posts`

### 3.7 Cron
- estado de jobs criticos mediante heartbeat o ultima ejecucion correcta
- logs recientes del `cron-master`
- retraso respecto a la frecuencia esperada

## 4. Comandos permitidos

### 4.1 Host
- `uptime`
- `df -h`
- `iostat`
- `vm_stat`
- `top -l 1` o equivalente de solo lectura
- `docker info`

### 4.2 Runtime
- `docker compose ps`
- `docker compose logs --tail <N> <service>`
- `docker compose exec -T <service> <comando-de-lectura>`
- `curl` contra endpoints internos de salud o estado
- `wp --allow-root ...` solo via `cron-master` y en lectura

## 5. Comandos sensibles
Solo con confirmacion manual explicita:
- borrado de indices
- borrado de posts o datos
- cambios de `routing-cutover`
- reinicios no planificados o destructivos
- vaciado de cache con impacto funcional
- cambios persistentes en DB, WordPress, Nginx o Compose

## 6. Saneado de datos
Antes de enviar contexto a un modelo externo:
- redaccion de emails
- redaccion de IPs publicas completas
- preservacion opcional de IPs privadas
- truncado de logs a ventanas acotadas

Reglas minimas:
- no enviar logs completos
- no enviar hashes de password
- no enviar secretos o contenido de `.secrets`
- no enviar dumps completos de DB o indices

## 7. Checks minimos

### 7.1 Host
Checks obligatorios:
- memoria usada
- `load average`
- uso de disco en la ruta del proyecto o del runtime
- disponibilidad de Docker
- `iowait` simple cuando sea accesible

Umbrales iniciales:
- memoria
  - `warning`: >= 85%
  - `critical`: >= 92%
- disco
  - `warning`: >= 80%
  - `critical`: >= 90%
- carga
  - `warning`: `load1` > numero de CPU logicas
  - `critical`: `load1` > `1.5 * CPU logicas`
- `iowait`
  - `warning`: >= 10%
  - `critical`: >= 20%

### 7.2 Servicios
Checks obligatorios:
- estado de todos los contenedores
- `healthcheck` de `lb-nginx`, `fe-live`, `fe-archive`, `be-admin`, `db-live`, `db-archive`, `elastic`, `cron-master`
- `4xx` repetidos en ventana corta en `lb-nginx`
- `5xx` o errores recientes en logs de `lb-nginx`
- `mysqladmin ping` para ambas DB
- queries largas en `processlist` para `db-live` y `db-archive`
- salud de Elasticsearch y presencia del alias `n9-search-posts`

Umbrales iniciales:
- contenedor `unhealthy` o `exited`
  - `critical`
- alias de lectura ausente
  - `critical`
- `4xx` repetidos en ventana corta
  - `warning` si superan el baseline esperado
  - `critical` si apuntan a regresion de routing, assets o rotura de frontend
- `5xx` repetidos en ventana corta
  - `warning` si hay recurrencia
  - `critical` si se concentran en pocos minutos o afectan a rutas base
- queries largas en MySQL
  - `warning` si superan `30s`
  - `critical` si superan `120s`

### 7.3 Aplicacion
Checks obligatorios:
- `/healthz` en ambos hosts
- login/admin responde sin loops
- busqueda unificada responde
- smokes del repo:
  - `smoke-routing.sh`
  - `smoke-services.sh`
  - `smoke-search.sh`
  - `smoke-rollover-year.sh` cuando aplique a un anio objetivo

Umbrales iniciales:
- smoke critico fallido
  - `critical`
- busqueda degradada pero sitio sirve contenido
  - `warning`

### 7.4 Cron
Checks obligatorios:
- heartbeat de ultima ejecucion correcta por job critico
- logs recientes del `cron-master`
- retraso frente a la ventana esperada

Umbrales iniciales:
- job critico retrasado una ventana
  - `warning`
- job critico sin ejecucion en dos ventanas o con fallo repetido
  - `critical`

## 8. Severidades
- `info`: observacion sin accion inmediata
- `warning`: degradacion, drift o riesgo operativo moderado
- `critical`: caida, corrupcion potencial, busqueda rota, DB no accesible, alias ausente, cron critico fallido

## 9. Formato de salida

### 9.1 Sentry Agent
- `resumen`
- `severidad`
- `servicio_afectado`
- `evidencias`
- `causa_probable`
- `validaciones_recomendadas`
- `acciones_manuales`
- `playbook_ansible_sugerido`
- `riesgo_si_no_se_actua`

### 9.2 Nightly Auditor
- `resumen`
- `severidad_global`
- `host`
- `servicios`
- `aplicacion`
- `cron`
- `drift_detectado`
- `riesgos`
- `acciones_recomendadas`

## 10. Casos minimos que debe cubrir
- `lb-nginx` no responde
- `5xx` repetidos en frontend
- `4xx` repetidos en frontend fuera del baseline esperado
- `db-live` o `db-archive` no responden
- `db-live` o `db-archive` con processlist anomalo y queries largas
- slow queries repetidas
- `elastic` caido
- alias `n9-search-posts` ausente
- rollover en estado inconsistente
- sync editorial o de plataforma con drift
- job critico de `cron-master` retrasado o fallido

## 11. Ejemplo de salida

### 11.1 Sentry Agent
```md
resumen: alias de lectura de Elasticsearch ausente tras reindexado
severidad: critical
servicio_afectado: elastic
evidencias:
- `_cat/aliases` no contiene `n9-search-posts`
- `smoke-search.sh` falla en la comprobacion del alias
causa_probable: reindexado ejecutado sin republicar el alias de lectura
validaciones_recomendadas:
- confirmar indices `n9-live-*` y `n9-archive-*`
- verificar ultimo job de reindexado
acciones_manuales:
- republicar alias de lectura
- repetir smoke de busqueda
playbook_ansible_sugerido: elasticsearch_publish_search_alias
riesgo_si_no_se_actua: la busqueda publica queda degradada o rota
```

### 11.2 Nightly Auditor
```md
resumen: plataforma sana con warning por retraso en cron editorial
severidad_global: warning
host:
- memoria: ok
- carga: ok
- disco: ok
servicios:
- docker compose ps: ok
- alias de lectura: ok
aplicacion:
- smoke-routing: ok
- smoke-search: ok
cron:
- sync editorial: retrasado 1 ventana
drift_detectado:
- editorial_drift: no
- platform_drift: no
riesgos:
- si el retraso persiste, archive quedara desalineado en usuarios editoriales
acciones_recomendadas:
- revisar logs recientes de `cron-master`
- confirmar ultimo heartbeat del job editorial
```

## 12. Dependencias con la siguiente fase
La Fase 8 debe implementar wrappers y colectores que respeten este contrato, no redefinirlo.
