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
#### Objetivo
Decidir que scripts se migran, en que orden y con que compatibilidad.

#### Tareas
- Inventariar scripts actuales y clasificarlos por complejidad.
- Definir estructura Python objetivo y politica de dependencias.
- Decidir wrappers shell que se conservaran por ergonomia.

#### Criterios de cierre
- Existe una matriz clara de migracion y una estructura base aprobada.

### Fase 2. Base Python y utilidades comunes
#### Objetivo
Crear el esqueleto Python comun para configuracion, subprocess, JSON, tiempo, heartbeats y reporting.

#### Tareas
- Crear paquete Python base.
- Definir carga de configuracion no sensible.
- Centralizar helpers de ejecucion segura y salida estructurada.

#### Criterios de cierre
- Los nuevos modulos pueden reemplazar logica repetida de shell sin duplicacion.

### Fase 3. Migracion de colectores y reporting IA-Ops
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
