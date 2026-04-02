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
Pendiente

#### Objetivo
Pasar a Python los `collect-*`, el agregador nocturno y la generacion de informes.

#### Tareas
- Migrar host, runtime, app, elastic y cron.
- Migrar `Nightly Auditor` y `Sentry Agent`.
- Mantener wrappers shell compatibles apuntando a Python.

#### Criterios de cierre
- La salida funcional de IA-Ops es equivalente o mejor que la actual.

### Fase 4. Programacion de flujos
#### Objetivo
Dejar el auditor nocturno programado y el flujo reactivo listo para disparo.

#### Tareas
- Programar `Nightly Auditor` con `cron` en laboratorio.
- Evaluar `Monit` como supervisor/disparador del flujo reactivo.
- Si `Monit` no aporta suficiente, dejar alternativa simple basada en healthchecks y cron.

#### Criterios de cierre
- Existe una programacion reproducible de `Nightly Auditor`.
- El flujo reactivo tiene un mecanismo de disparo defendible.

### Fase 5. Canal real de alertas e incidentes
#### Objetivo
Conectar la salida a un destino util para operacion humana.

#### Tareas
- Definir contrato de salida para webhook, mail o canal de incidencias.
- Implementar un adaptador minimo desacoplado del proveedor.
- Validar entrega y formato de mensajes.

#### Criterios de cierre
- Los informes pueden salir del host hacia un canal real sin exponer datos sensibles.

### Fase 6. Migracion selectiva de syncs y rollover
#### Objetivo
Empezar a mover a Python las piezas mas complejas del plano operativo.

#### Tareas
- Priorizar drift/sync sobre rollover, o viceversa, segun complejidad real observada.
- Mantener equivalencia funcional con los scripts actuales.
- Conservar rollback y evidencias persistentes.

#### Criterios de cierre
- La logica operativa deja de depender de Bash en sus partes mas fragiles.

### Fase 7. Cierre documental y validacion
#### Objetivo
Cerrar la migracion acordada con documentacion limpia y operacion verificable.

#### Tareas
- Actualizar `docs/` y runbooks.
- Dejar ejemplos de ejecucion manual y programada.
- Ejecutar bateria funcional y checks IA-Ops.

#### Criterios de cierre
- La nueva capa operativa queda mantenible, programable y preparada para alertado real.

## 9. Criterio de exito global
- La complejidad operativa deja de acumularse en Bash.
- `Nightly Auditor` queda programado de forma reproducible.
- `Sentry Agent` tiene un disparador tecnico claro.
- El sistema puede emitir alertas a un canal real sin acoplarse prematuramente a un proveedor.
- `docs/` y `tasks/` reflejan de forma limpia el estado real del sistema y del trabajo.
