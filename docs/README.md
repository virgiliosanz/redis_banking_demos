# Documentacion viva

Este directorio contiene la documentacion estable del sistema. Su funcion es describir la plataforma tal como existe hoy, no el historico de fases o decisiones transitorias de ejecucion.

El historico de trabajo y los planes de proyecto viven en `tasks/`.

## Estado actual
- POC WordPress implementada y validada en laboratorio con `docker compose`
- topologia separada en `live`, `archive` y `admin`
- WordPress real operativo en `live` y `archive`
- busqueda unificada con indices separados y alias comun en Elasticsearch
- rollover anual `live -> archive` probado en laboratorio
- sincronizacion editorial y de plataforma disponible entre `live` y `archive`
- IA-Ops minimo operativo con `Nightly Auditor`, `Sentry Agent`, `cron` y salida a Telegram
- plano reactivo ligero resuelto con `cron` + evaluador Python + cooldown por incidente
- orquestacion compleja ya movida a Python en `ops/`
- checks MySQL en solo lectura con `ping` y processlist largo para `db-live` y `db-archive`
- drift `live/archive` con resumen accionable para editorial y plataforma
- baseline reproducible de calidad para Python, shell, PHP y `docker compose`
- 84 tests unitarios cubriendo 22 de 28 modulos Python
- dispatch table en `sentry.py` para extender diagnosticos sin tocar el core
- heartbeat writing consolidado entre sync y rollover

## Topologia funcional

| Componente | Funcion |
| :--- | :--- |
| `LB-Nginx` | entrada HTTP, routing y contrato FastCGI |
| `FE-Live` | `php-fpm` para trafico vivo |
| `FE-Archive` | `php-fpm` para contenido historico |
| `BE-Admin` | `php-fpm` administrativo para `live` y `archive` |
| `DB-Live` | MySQL de contenido vivo |
| `DB-Archive` | MySQL de contenido historico |
| `Elastic` | busqueda comun con indices separados |
| `Cron-Master` | `wp-cli`, syncs, rollover y jobs programados |

## Routing vigente
- `nuevecuatrouno.test` sirve el frontend publico y el admin de `live`
- `archive.nuevecuatrouno.test` queda reservado para el admin de `archive`
- `wp-admin`, `wp-login.php`, `admin-ajax.php` y REST de administracion caen en `BE-Admin`
- las URLs de posts con patron `/%year%/%monthnum%/%day%/%postname%/` y anio `2015-2024` caen en `archive`
- las URLs fechadas con anio `2025+` caen en `live`
- cualquier otra peticion publica cae en `live`

La frontera anual real del balanceador ya no esta hardcodeada: sale de `config/routing-cutover.env` y acompana al rollover.

## Capacidades operativas actuales
- bootstrap local reproducible con `./scripts/bootstrap-local-stack.sh`
- smoke tests de routing, servicios, busqueda, cache, persistencia y funcionalidad
- rollover anual con `report-only`, `dry-run` y `execute`
- sync editorial y de plataforma con drift report accionable
- auditoria nocturna programable con `cron`
- agente reactivo programable con `cron`, deduplicacion y salida a Telegram
- baseline reproducible de calidad: `./scripts/check-quality.sh` (unittest, shellcheck, php -l, py_compile, compose config)
- gestion de crontabs refactorizada con helpers genericos reutilizables

## Limites actuales
- laboratorio local, no produccion
- sin alta disponibilidad
- sin backups verificados
- sin observabilidad pesada
- sin `cloudflared` en la POC
- sin remediacion automatica destructiva

## Mapa documental
- `poc-local-runbook.md`
  - bootstrap, pruebas manuales, cron, Telegram y operacion local
- `content-lifecycle-live-archive.md`
  - rollover anual, sync editorial, sync de plataforma, validaciones y rollback
- `ia-ops-bootstrap-contract.md`
  - checks, severidades, fuentes permitidas, saneado y formato de salida de agentes
- `search-architecture-live-archive.md`
  - indices separados, alias comun y degradacion de busqueda
- `cache-policy-by-context.md`
  - politica de cache del origen y frontera con Cloudflare
- `wordpress-persistence-layout.md`
  - persistencia compartida y aislamiento por contexto
- `origin-behind-cloudflare-tunnel.md`
  - modelo objetivo de origen privado detras de Cloudflare Tunnel

## Criterio de autoridad
- este `README.md` es la vista global de arquitectura y estado
- los demas documentos existen por dominio operativo concreto
- si un dato de detalle ya vive en un documento de dominio, no debe duplicarse aqui salvo como resumen
