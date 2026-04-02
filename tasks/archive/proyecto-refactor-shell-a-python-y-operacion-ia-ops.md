# Proyecto: refactor shell a Python y operacion IA-Ops

## 1. Objetivo
Reducir la fragilidad operativa del stack actual migrando la logica de automatizacion desde shell a Python alli donde ya hay complejidad suficiente, y dejar programados los flujos IA-Ops minimos con una via clara hacia alertas reales antes de pensar en produccion.

## 2. Relacion con la documentacion vigente
- Arquitectura y estado global: `docs/project.md`
- Runbook operativo local: `docs/poc-local-runbook.md`
- Contrato IA-Ops: `docs/ia-ops-bootstrap-contract.md`
- Proyecto previo completado: `tasks/archive/proyecto-rollover-anual-e-ia-ops-bootstrap.md`

## 3. Motivacion
- Bash ya es suficiente para wrappers simples, pero empieza a ser fragil para parseo JSON, agregacion de checks, severidades, heartbeats, informes y branching operativo.
- Los flujos `Nightly Auditor` y `Sentry Agent` ya existen, pero aun no estan programados ni conectados a un canal real de incidentes.
- Antes de produccion conviene subir el liston de mantenibilidad y operacion, no seguir anadiendo logica compleja en shell.

## 4. Alcance acordado
- Definir que scripts siguen siendo shell y cuales migran a Python.
- Crear una estructura Python reutilizable para colectores, reporting y logica operativa.
- Mantener compatibilidad con los entrypoints actuales del repo mientras se migra.
- Programar `Nightly Auditor` mediante `cron` como baseline.
- Evaluar e integrar `Monit` como disparador reactivo del flujo tipo `Sentry Agent` si aporta valor real.
- Dejar una salida conectable a un canal real de alertas o incidentes.
- Actualizar documentacion y runbooks al nuevo modelo.

## 5. Fuera de alcance
- Reescritura completa de toda la plataforma en una sola fase.
- Integracion productiva final con un proveedor concreto de alerting si antes no cerramos el contrato.
- Sustitucion completa de `docker compose` o del modelo actual de laboratorio.
- Remediacion automatica sobre el stack.

## 6. Principios de trabajo
- Bash queda solo como capa fina de entrada o compatibilidad.
- La logica de negocio y operacion compleja se mueve a Python.
- La migracion debe ser incremental y con equivalencia funcional demostrable.
- No se rompe ningun runbook existente sin dejar wrapper compatible.
- Cada paso debe mantener smoke tests, rollback y validacion real.

## 7. Propuesta tecnica

### Shell debe quedar para
- wrappers finos de entrada (`scripts/*.sh`)
- bootstrap muy lineal del entorno
- invocacion de `docker compose`, `curl` o `wp-cli` sin logica compleja

### Python debe absorber
- colectores y parsing estructurado
- agregacion de checks
- calculo de severidades y umbrales
- generacion de informes
- logica de drift y heartbeats
- orquestacion del rollover y sincronizaciones con mejor modelado

### Estructura objetivo orientativa
```text
ops/
  __init__.py
  cli/
  collectors/
  reporting/
  rollover/
  sync/
  scheduling/
```

## 8. Fases

### Fase 1. Contrato de migracion shell -> Python
#### Estado
Completada

#### Objetivo
Decidir que scripts se migran, en que orden y con que compatibilidad.

#### Tareas
- Inventariar scripts actuales y clasificarlos por complejidad.
- Definir estructura Python objetivo y politica de dependencias.
- Decidir wrappers shell que se conservaran por ergonomia.

#### Criterios de cierre
- Existe una matriz clara de migracion y una estructura base aprobada.

#### Progreso actual
- Se inventaria el arbol actual de `scripts/` y se clasifica por rol: bootstrap, smokes, colectores, reporting, sync y rollover.
- Se valida que el repo no tiene aun `pyproject.toml` ni estructura Python previa, y que el baseline disponible es `python3 3.14.3`.
- Se confirma que el problema no es el numero bruto de scripts, sino la concentracion de logica operativa compleja en shell: JSON, severidades, branching, heartbeats e informes.

#### Matriz de migracion acordada

##### Se quedan en shell
- `scripts/bootstrap-local-runtime.sh`
- `scripts/bootstrap-local-stack.sh`
- `scripts/bootstrap-local-secrets.sh`
- `scripts/bootstrap-wordpress-layout.sh`
- `scripts/bootstrap-wordpress-config.sh`
- `scripts/bootstrap-wordpress-core.sh`
- `scripts/bootstrap-wordpress-install.sh`
- `scripts/bootstrap-wordpress-seed.sh`
- `scripts/bootstrap-elasticpress.sh`
- `scripts/render-routing-cutover.sh`
- `scripts/advance-routing-cutover.sh`
- `scripts/write-heartbeat.sh`
- `scripts/redact-sensitive.sh`
- `scripts/smoke-routing.sh`
- `scripts/smoke-services.sh`
- `scripts/smoke-search.sh`
- `scripts/smoke-cache-policy.sh`
- `scripts/smoke-cache-isolation.sh`
- `scripts/smoke-persistence.sh`
- `scripts/smoke-rollover-year.sh`
- `scripts/smoke-functional.sh`

Motivo:
- son wrappers lineales o pruebas operativas cortas
- dependen de shell, `curl`, `docker compose` y `wp-cli` de forma directa
- mantenerlos en shell reduce friccion en runbooks y depuracion local

##### Migracion prioritaria a Python
- `scripts/collect-host-health.sh`
- `scripts/collect-runtime-health.sh`
- `scripts/collect-app-health.sh`
- `scripts/collect-elastic-health.sh`
- `scripts/collect-cron-health.sh`
- `scripts/collect-nightly-context.sh`
- `scripts/run-nightly-auditor.sh`
- `scripts/run-sentry-agent.sh`
- `scripts/report-live-archive-sync-drift.sh`

Motivo:
- concentran parsing estructurado, severidades, agregacion y generacion de informes
- son las piezas con mas retorno inmediato en reutilizacion y testabilidad

##### Migracion posterior a Python
- `scripts/sync-editorial-users.sh`
- `scripts/sync-platform-config.sh`
- `scripts/rollover-content-year.sh`

Motivo:
- son las piezas mas delicadas del plano operativo
- tienen mas branching, riesgo funcional y dependencia de artefactos persistentes
- conviene moverlas despues de tener utilidades Python comunes y reporting estable

##### Se mantiene en PHP
- `scripts/rollover-collect-year-summary.php`
- `scripts/rollover-delete-source-posts.php`
- `scripts/rollover-detect-archive-collisions.php`
- `scripts/rollover-export-year.php`
- `scripts/rollover-import-snapshot.php`
- `scripts/sync-editorial-source-snapshot.php`
- `scripts/sync-editorial-snapshot.php`
- `scripts/sync-editorial-plan.php`
- `scripts/sync-editorial-apply.php`
- `scripts/sync-platform-source-snapshot.php`
- `scripts/sync-platform-snapshot.php`
- `scripts/sync-platform-plan.php`
- `scripts/sync-platform-apply.php`

Motivo:
- viven pegados a `wp-cli eval-file` y al modelo de datos de WordPress
- hoy su coste de migracion es mayor que su beneficio inmediato
- la prioridad actual esta en sustituir shell complejo, no en rehacer la capa WordPress

#### Estructura Python acordada
```text
ops/
  __init__.py
  cli/
    __init__.py
  collectors/
    __init__.py
  reporting/
    __init__.py
  runtime/
    __init__.py
  scheduling/
    __init__.py
  util/
    __init__.py
```

#### Politica de dependencias acordada
- Python estandar como base
- se permite dependencia externa minima solo si reduce complejidad real y queda bien justificada
- `jq` no debe seguir siendo requisito para la nueva capa Python
- los wrappers shell existentes deben poder seguir invocando la nueva capa sin romper runbooks

#### Decisiones tomadas
- No se reescriben smokes ni bootstrap lineal en esta fase.
- La primera migracion Python ataca IA-Ops y reporting, no rollover.
- La nueva estructura Python vivira en el repo raiz bajo `ops/`, no como paquete separado todavia.
- Los entrypoints shell existentes se mantienen como interfaz estable durante la migracion.

#### Lecciones aprendidas
- El umbral de complejidad ya se ha cruzado claramente en `collect-*`, `run-*` y `rollover-content-year.sh`.
- La frontera correcta no es shell vs Python por lenguaje, sino wrappers finos vs logica operativa compleja.
- Intentar migrar rollover antes de tener utilidades Python comunes aumentaria el riesgo sin aportar orden.

### Fase 2. Base Python y utilidades comunes
#### Estado
Completada

#### Objetivo
Crear el esqueleto Python comun para configuracion, subprocess, JSON, tiempo, heartbeats y reporting.

#### Tareas
- Crear paquete Python base.
- Definir carga de configuracion no sensible.
- Centralizar helpers de ejecucion segura y salida estructurada.

#### Criterios de cierre
- Los nuevos modulos pueden reemplazar logica repetida de shell sin duplicacion.

#### Progreso actual
- Se crea `pyproject.toml` minimo para formalizar la nueva capa Python sin introducir dependencias externas.
- Se crea el paquete `ops/` con submodulos para configuracion, reporting, subprocess, tiempo y heartbeats.
- La estructura real creada queda en:
  - `ops/config.py`
  - `ops/reporting.py`
  - `ops/runtime/heartbeats.py`
  - `ops/util/jsonio.py`
  - `ops/util/process.py`
  - `ops/util/time.py`
  - paquetes vacios preparados en `ops/cli/`, `ops/collectors/`, `ops/runtime/`, `ops/scheduling/` y `ops/util/`
- `.gitignore` pasa a ignorar `__pycache__/` y `.pytest_cache/` para no contaminar el repo al validar Python.

#### Decisiones tomadas
- La base Python se apoya solo en libreria estandar en esta fase.
- La configuracion sigue cargandose desde `config/ia-ops-sources.env` o su ejemplo, con sobreescritura por variables de entorno.
- Los helpers comunes se centralizan antes de migrar ningun colector concreto.
- `pyproject.toml` queda lo bastante simple para empaquetar `ops*`, sin abrir aun el frente de tooling adicional.

#### Validacion ejecutada
- Import y uso real de `ops.config`, `ops.reporting`, `ops.runtime.heartbeats`, `ops.util.process` y `ops.util.time`.
- Escritura y lectura de heartbeat en directorio temporal.
- Escritura de reportes JSON y Markdown en directorio temporal.
- Ejecucion segura de un subprocess de prueba (`python3 --version`).

#### Lecciones aprendidas
- Merece la pena fijar primero utilidades de configuracion, tiempo, procesos y reporting; sin ellas, la migracion de colectores repetiría patrones otra vez.
- Un `pyproject.toml` minimo ayuda a ordenar la nueva capa Python sin obligarnos todavia a introducir pytest, ruff o tooling adicional.

### Fase 3. Migracion de colectores y reporting IA-Ops
#### Estado
Completada

#### Objetivo
Pasar a Python los `collect-*`, el agregador nocturno y la generacion de informes.

#### Tareas
- Migrar host, runtime, app, elastic y cron.
- Migrar `Nightly Auditor` y `Sentry Agent`.
- Mantener wrappers shell compatibles apuntando a Python.

#### Criterios de cierre
- La salida funcional de IA-Ops es equivalente o mejor que la actual.

#### Progreso actual
- Se migra a Python la capa de colectores y agregacion IA-Ops manteniendo los wrappers shell como interfaz estable del repo.
- La nueva capa queda repartida en:
  - `ops/collectors/host.py`
  - `ops/collectors/cron.py`
  - `ops/collectors/elastic.py`
  - `ops/collectors/runtime.py`
  - `ops/collectors/app.py`
  - `ops/collectors/logs.py`
  - `ops/runtime/drift.py`
  - `ops/cli/ia_ops.py`
  - `ops/util/docker.py`
  - `ops/util/http.py`
  - `ops/util/thresholds.py`
- Los wrappers shell migrados quedan reducidos a `exec python3 -m ops.cli.ia_ops ...`:
  - `scripts/collect-host-health.sh`
  - `scripts/collect-cron-health.sh`
  - `scripts/collect-elastic-health.sh`
  - `scripts/collect-runtime-health.sh`
  - `scripts/collect-app-health.sh`
  - `scripts/collect-service-logs.sh`
  - `scripts/collect-nightly-context.sh`
  - `scripts/report-live-archive-sync-drift.sh`
  - `scripts/run-nightly-auditor.sh`
  - `scripts/run-sentry-agent.sh`

#### Decisiones tomadas
- `ops/reporting.py` se mantiene como modulo simple de escritura de reportes; el drift report se mueve a `ops/runtime/drift.py` para evitar conflicto entre modulo y paquete.
- La capa HTTP degrada timeouts a `http_code=0` en lugar de abortar el flujo, porque en IA-Ops interesa conservar el informe aunque un endpoint tarde demasiado.
- `docker compose exec` se sigue encapsulando en Python, pero las opciones especiales de ejecucion se resuelven dentro del comando del contenedor cuando no merece la pena complicar mas el helper.
- La deteccion de `5xx` recientes en `lb-nginx` se rehace en Python evitando regex POSIX incompatibles con `re`.

#### Validacion ejecutada
- `python3 -m py_compile` sobre la nueva capa Python: OK.
- `python3 -m ops.cli.ia_ops collect-host-health`: OK.
- `python3 -m ops.cli.ia_ops collect-cron-health`: OK.
- `python3 -m ops.cli.ia_ops collect-elastic-health`: OK.
- `python3 -m ops.cli.ia_ops collect-runtime-health`: OK.
- `python3 -m ops.cli.ia_ops collect-app-health`: OK.
- `python3 -m ops.cli.ia_ops collect-nightly-context --write-report`: OK.
- `python3 -m ops.cli.ia_ops report-live-archive-sync-drift`: OK.
- `python3 -m ops.cli.ia_ops run-nightly-auditor --no-write-report`: OK.
- `python3 -m ops.cli.ia_ops run-sentry-agent --service elastic --no-write-report`: OK.
- Compatibilidad de wrappers shell de colectores y drift:
  - `scripts/collect-host-health.sh`
  - `scripts/collect-cron-health.sh`
  - `scripts/collect-elastic-health.sh`
  - `scripts/collect-runtime-health.sh`
  - `scripts/collect-app-health.sh`
  - `scripts/report-live-archive-sync-drift.sh`
  Todo en verde.

#### Lecciones aprendidas
- La frontera correcta es estable: shell para entrypoints finos, Python para parsing, severidades, agregacion y reporting.
- La migracion obliga a revisar detalles que Bash ocultaba, como timeouts HTTP, quoting en `docker compose exec` y diferencias entre regex POSIX y `re`.
- Mantener los wrappers shell como `exec` plano protege los runbooks sin arrastrar mas logica al shell.

### Fase 4. Programacion de flujos
#### Estado
Completada

#### Objetivo
Dejar el auditor nocturno programado y el flujo reactivo listo para disparo.

#### Tareas
- Programar `Nightly Auditor` con `cron` en laboratorio.
- Evaluar `Monit` como supervisor/disparador del flujo reactivo.
- Si `Monit` no aporta suficiente, dejar alternativa simple basada en healthchecks y cron.

#### Criterios de cierre
- Existe una programacion reproducible de `Nightly Auditor`.
- El flujo reactivo tiene un mecanismo de disparo defendible.

#### Progreso actual
- Se crea una capa de scheduling reproducible en `ops/scheduling/cron.py` para renderizar, instalar y retirar un bloque gestionado de `crontab`.
- El bloque baseline programa `Nightly Auditor` a las `02:00` hora local del host con:
  - `IA_OPS_CONFIG_FILE` absoluto
  - `cd` al repo
  - salida persistente a `runtime/reports/ia-ops/nightly-auditor.cron.log`
- Se expone la operacion via:
  - `python3 -m ops.cli.ia_ops render-nightly-crontab`
  - `python3 -m ops.cli.ia_ops install-nightly-crontab`
  - `python3 -m ops.cli.ia_ops remove-nightly-crontab`
  - `scripts/install-nightly-auditor-cron.sh`
- Se actualiza `docs/poc-local-runbook.md` con el flujo operativo para imprimir, instalar y retirar el bloque gestionado.

#### Decisiones tomadas
- En laboratorio se usa `cron` como baseline porque ya existe en el host, no introduce una dependencia nueva y basta para el flujo nocturno.
- `Sentry Agent` no se agenda por `cron`; sigue siendo manual o disparado por una capa reactiva futura.
- `Monit` no se incorpora todavia al repo como dependencia obligatoria del laboratorio.
- La recomendacion actual para el plano reactivo es:
  - `cron` para `Nightly Auditor`
  - `Sentry Agent` manual durante laboratorio
  - decidir `Monit` solo si en el siguiente paso aporta una señal reactiva mejor que Docker healthchecks + logs + wrappers actuales

#### Evaluacion de Monit
- A favor:
  - buen disparador simple para caidas de proceso o checks HTTP/puerto
  - encaja si queremos lanzar `Sentry Agent` por servicio degradado sin meter una plataforma mayor
- En contra en esta POC:
  - el laboratorio ya dispone de healthchecks Docker y wrappers manuales suficientes
  - introducir `Monit` ahora mete otra superficie de configuracion, logs y runbook sin ganar demasiado contexto
  - no resuelve por si solo checks funcionales WordPress/ElasticPress ni drift `live/archive`
- Conclusion:
  - `Monit` queda como opcion razonable para la siguiente fase reactiva
  - no es la baseline defendible del laboratorio; `cron` si lo es

#### Validacion ejecutada
- `python3 -m ops.cli.ia_ops render-nightly-crontab`: OK.
- `python3 -m ops.cli.ia_ops run-nightly-auditor --no-write-report`: OK tras la capa de scheduling.
- `scripts/install-nightly-auditor-cron.sh --print`: OK.
- No se instala automaticamente el `crontab` real del usuario en esta fase; la validacion queda en render, wrapper y ejecucion del job.

#### Lecciones aprendidas
- El valor de esta fase no esta en “instalar cron”, sino en versionar el contrato del job y no depender de notas manuales.
- Un bloque gestionado de `crontab` es suficiente para laboratorio y deja espacio para retirar o sustituir el scheduling sin tocar la logica IA-Ops.

### Fase 5. Canal real de alertas e incidentes
#### Estado
Completada

#### Objetivo
Conectar la salida a un destino util para operacion humana.

#### Tareas
- Definir contrato de salida para webhook, mail o canal de incidencias.
- Implementar un adaptador minimo desacoplado del proveedor.
- Validar entrega y formato de mensajes.

#### Criterios de cierre
- Los informes pueden salir del host hacia un canal real sin exponer datos sensibles.

#### Progreso actual
- Se fija Telegram como primer canal real de alertas.
- La integracion se plantea con un backend propio pequeno en `ops/notifications/telegram.py`, sin acoplar el resto del sistema a la API de Telegram.
- `Nightly Auditor` y `Sentry Agent` pasan a soportar:
  - vista previa local del mensaje
  - envio explicito a Telegram
  - activacion por configuracion si el canal queda habilitado
- Se protege el repo para que `config/ia-ops-sources.env` quede ignorado por Git y pueda alojar `TELEGRAM_BOT_TOKEN` localmente sin contaminar commits.

#### Decisiones tomadas
- Telegram se usa como primer canal real porque es suficiente para laboratorio y no obliga a meter infraestructura adicional.
- El mensaje enviado al canal es un resumen corto y saneado; los informes completos se siguen guardando en `runtime/reports/ia-ops/`.
- La configuracion sensible del canal vive en `config/ia-ops-sources.env` local o variables de entorno, nunca en el repo.
- `Nightly Auditor` y `Sentry Agent` soportan dos modos:
  - envio automatico si el canal queda habilitado por configuracion
  - envio explicito con `--notify-telegram`

#### Validacion ejecutada
- `python3 -m py_compile ops/config.py ops/cli/ia_ops.py ops/notifications/telegram.py`: OK.
- `python3 -m ops.cli.ia_ops send-telegram-test --preview`: OK.
- `python3 -m ops.cli.ia_ops run-nightly-auditor --telegram-preview --no-write-report`: OK.
- `python3 -m ops.cli.ia_ops run-sentry-agent --service elastic --telegram-preview --no-write-report`: OK.
- Envio real a Telegram:
  - `scripts/send-telegram-test.sh`: OK.
  - `scripts/run-nightly-auditor.sh --notify-telegram`: OK.
  - `scripts/run-sentry-agent.sh --service elastic --notify-telegram --no-write-report`: OK.

#### Lecciones aprendidas
- El canal real debe recibir resúmenes cortos; mandar el informe entero al chat degrada la utilidad operativa.
- Ignorar `config/ia-ops-sources.env` en Git no es opcional si vamos a permitir tokens de integracion locales.
- El flag explicito `--notify-telegram` debe prevalecer aunque la activacion automatica del canal este apagada.

### Fase 6. Migracion selectiva de syncs y rollover
#### Estado
Completada

#### Objetivo
Empezar a mover a Python las piezas mas complejas del plano operativo.

#### Tareas
- Priorizar drift/sync sobre rollover, o viceversa, segun complejidad real observada.
- Mantener equivalencia funcional con los scripts actuales.
- Conservar rollback y evidencias persistentes.

#### Criterios de cierre
- La logica operativa deja de depender de Bash en sus partes mas fragiles.

#### Progreso actual
- Se migra a Python la orquestacion de:
  - `sync-editorial-users`
  - `sync-platform-config`
  - `rollover-content-year`
- La nueva capa queda repartida en:
  - `ops/sync/common.py`
  - `ops/sync/editorial.py`
  - `ops/sync/platform.py`
  - `ops/rollover/content_year.py`
- `ops/util/docker.py` gana soporte reusable para:
  - opciones de `docker compose exec`
  - espera de contenedores sanos
  - inspeccion simple de salud
- Los wrappers shell pasan a ser solo:
  - `scripts/sync-editorial-users.sh`
  - `scripts/sync-platform-config.sh`
  - `scripts/rollover-content-year.sh`
  todos como `exec python3 -m ops.cli.ia_ops ...`

#### Decisiones tomadas
- La capa PHP de WordPress no se toca; sigue siendo la fuente de verdad para snapshots, plan, apply, export e import.
- La migracion se limita a la orquestacion: esperas, `wp-cli`, artefactos, reportes, reindexado, alias y heartbeats.
- `render-routing-cutover.sh` y `advance-routing-cutover.sh` permanecen en shell por ahora; son lineales y ya estaban acotados.
- El `execute` del rollover se deja implementado en Python, pero no se revalida en esta pasada sin confirmacion manual porque sigue siendo destructivo.

#### Validacion ejecutada
- `python3 -m py_compile` sobre `ops/sync/*`, `ops/rollover/content_year.py`, `ops/util/docker.py` y `ops/cli/ia_ops.py`: OK.
- `python3 -m ops.cli.ia_ops sync-editorial-users --mode report-only`: OK.
- `python3 -m ops.cli.ia_ops sync-platform-config --mode report-only`: OK.
- `./scripts/sync-editorial-users.sh --mode dry-run`: OK.
- `./scripts/sync-platform-config.sh --mode dry-run`: OK.
- `./scripts/sync-editorial-users.sh --mode apply`: OK.
- `./scripts/sync-platform-config.sh --mode apply`: OK.
- Heartbeats:
  - `runtime/heartbeats/sync-editorial-users.success`: actualizado
  - `runtime/heartbeats/sync-platform-config.success`: actualizado
- `python3 -m ops.cli.ia_ops rollover-content-year --mode report-only --year 2025`: OK.
- `./scripts/rollover-content-year.sh --mode dry-run --year 2025`: OK.
- `python3 -m ops.cli.ia_ops report-live-archive-sync-drift`: OK tras aplicar syncs.

#### Lecciones aprendidas
- En estos flujos el valor de Python no esta en reemplazar `wp-cli`, sino en ordenar la orquestacion y el manejo de artefactos.
- `docker compose exec` necesitaba una capa comun con `exec_args`; sin eso, la migracion habria seguido acumulando hacks locales.
- La parte realmente sensible del rollover no es el snapshot, sino la secuencia import -> reindex -> cutover -> delete -> alias; tenerla en Python mejora mucho su legibilidad.

### Fase 7. Cierre documental y validacion
#### Estado
Completada

#### Objetivo
Cerrar la migracion acordada con documentacion limpia y operacion verificable.

#### Tareas
- Actualizar `docs/` y runbooks.
- Dejar ejemplos de ejecucion manual y programada.
- Ejecutar bateria funcional y checks IA-Ops.

#### Criterios de cierre
- La nueva capa operativa queda mantenible, programable y preparada para alertado real.

#### Progreso actual
- Se actualiza `docs/project.md` para reflejar el estado real tras la migracion: Telegram validado, `cron` operativo y orquestacion compleja ya movida a `ops/`.
- Se actualiza `docs/poc-local-runbook.md` para dejar explicito que los wrappers shell son ya interfaz de compatibilidad sobre la capa Python.
- El plan queda cerrado con todas las fases ejecutadas y con el repo en estado consistente para tag final.

#### Validacion ejecutada
- `./scripts/smoke-functional.sh`: OK.
- `./scripts/run-nightly-auditor.sh --no-write-report`: OK.
- `./scripts/run-sentry-agent.sh --service lb-nginx --no-write-report`: OK.
- La integracion Telegram sigue operativa en laboratorio durante la validacion final.

#### Riesgos residuales
- El host local sigue pudiendo salir en `critical` de memoria durante auditorias; es una caracteristica del laboratorio, no un fallo de la plataforma WordPress.
- `smoke-functional.sh` sigue mostrando el warning de `mysqladmin` por password en CLI; no rompe la POC, pero conviene limpiarlo en una iteracion de hardening de tooling.

#### Lecciones aprendidas
- El cierre documental tiene que explicitar no solo nuevas capacidades, sino tambien que Bash ya no es la fuente de verdad de la logica operativa.
- Una fase final de validacion real evita cerrar el proyecto con “documentacion bonita” pero sin comprobar el stack vivo.

## 9. Criterio de exito global
- La complejidad operativa deja de acumularse en Bash.
- `Nightly Auditor` queda programado de forma reproducible.
- `Sentry Agent` tiene un disparador tecnico claro.
- El sistema puede emitir alertas a un canal real sin acoplarse prematuramente a un proveedor.
- `docs/` y `tasks/` reflejan de forma limpia el estado real del sistema y del trabajo.
