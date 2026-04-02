# Proyecto: rollover anual e IA-Ops Bootstrap

## 1. Objetivo
Diseñar e implementar el siguiente salto operativo de la plataforma sobre la POC ya estabilizada:
- un proceso anual, auditable y reversible para mover el anio cerrado desde `live` a `archive`
- una interfaz minima de `IA-Ops Bootstrap` de solo lectura para diagnostico reactivo y auditoria programada

El objetivo no es rehacer la plataforma, sino operar sobre la topologia ya validada.

## 2. Relacion con la documentacion base
- Documento de arquitectura vigente: `docs/project.md`
- Documento funcional del rollover: `docs/annual-content-rollover.md`
- Documento de interfaz IA-Ops: `docs/ia-ops-bootstrap-interface.md`
- Proyecto previo completado: `projects/proyecto-docs-seed-search-y-admin.md`

## 3. Decisiones ya acordadas
- `archive` contiene anios cerrados; `live` contiene el anio en curso y el contenido nuevo.
- El contrato de URL publica de posts es `/%year%/%monthnum%/%day%/%postname%/`.
- `uploads` permanece compartido; la cache sigue aislada por contexto.
- Elasticsearch sigue con indices separados y alias de lectura unificado `n9-search-posts`.
- La busqueda no debe cambiar su punto de lectura al ejecutar el rollover.
- La futura capa IA-Ops debe ser de solo lectura por defecto y con filtrado de datos sensibles antes de salir del host.
- IA-Ops no sustituye a la monitorizacion minima; se apoya en checks de host, servicios, aplicacion y cron para poder diagnosticar con contexto real.
- En esta iteracion no se introduce `cloudflared` en la POC local.

## 4. Alcance acordado
- Implementar un script de rollover anual `live -> archive`.
- Definir y automatizar validaciones previas y posteriores al movimiento.
- Generar un informe legible y persistente por ejecucion.
- Dejar preparado un modo `dry-run` y un modo `report-only`.
- Implementar colectores minimos de contexto para `IA-Ops Bootstrap`.
- Definir prompts/contrato de salida y fuentes permitidas para `Sentry Agent` y `Nightly Auditor`.
- Integrar el bootstrap IA-Ops con los logs, checks y smokes ya existentes.

## 5. Fuera de alcance
- Migracion de contenido historico real desde produccion.
- Remediacion automatica sobre el stack.
- Integracion final con APIs externas o proveedor concreto de modelo.
- Despliegue en produccion del tunnel de Cloudflare.
- Alta disponibilidad, backups reales o pipeline de despliegue productivo completo.

## 6. Principios de trabajo
- El rollover debe ser idempotente, auditable y con rollback documentado.
- Ningun borrado en `live` se ejecuta sin haber pasado antes todas las validaciones.
- Toda automatizacion operativa debe dejar evidencia persistente de entrada, acciones y resultado.
- IA-Ops empieza por lectura, contexto acotado y salida accionable; no por ejecucion.
- La base minima de IA-Ops incluye monitorizacion operativa simple: checks, umbrales iniciales y alertas basicas, aunque no exista aun una plataforma completa de observabilidad.
- Cualquier cambio de runtime debe venir con smoke tests y validacion tecnica real.

## 7. Fases

### Fase 1. Contrato del rollover anual
#### Estado
Completada

#### Objetivo
Cerrar el contrato funcional y tecnico del rollover antes de mover datos reales.

#### Tareas
- Definir que entidades se mueven: posts, meta, taxonomias, relaciones y adjuntos referenciados.
- Definir la clave de seleccion por anio cerrado.
- Definir formato de informe, modo `dry-run` y modo `report-only`.
- Definir validaciones obligatorias antes de borrar en `live`.
- Documentar rollback operativo.

#### Entregables
- Actualizacion de `docs/annual-content-rollover.md` si hiciera falta.
- Contrato tecnico en script o documento operativo.
- Checklist de validacion previa y posterior.

#### Criterios de cierre
- El comportamiento del rollover queda sin ambiguedad y con criterios de exito y rollback claros.

#### Progreso actual
- `docs/annual-content-rollover.md` queda ampliado con alcance de datos, exclusiones, clave de seleccion, modos `dry-run` y `report-only`, formato de informe y estrategia de rollback.
- Se crea `docs/annual-content-rollover-checklist.md` como checklist previa, de borrado, posterior y de rollback.
- Queda fijado que la primera implementacion movera `post` publicados del anio objetivo, con sus taxonomias, meta relevante y adjuntos referenciados, pero no `pages`, usuarios, comentarios ni configuracion global.

#### Decisiones tomadas
- La clave de seleccion del rollover se basa en `post_date`, no en slug, path ni heuristicas de routing.
- La primera implementacion del rollover limita el alcance a contenido editorial tipo `post` ya publicado para reducir riesgo operativo.
- El borrado en `live` queda subordinado a validacion funcional, reindexado de `archive` y existencia previa de artefactos de rollback.
- El informe de ejecucion pasa a ser parte del contrato, no un extra opcional.

#### Lecciones aprendidas
- Sin fijar que se mueve y que no, el riesgo real del rollover no esta en el script sino en las expectativas ambiguas.
- El rollback no puede depender de backups globales inexistentes en la POC; necesita artefactos logicos generados por la propia operacion.

### Fase 2. Implementacion del script de rollover
#### Estado
Pendiente

#### Objetivo
Implementar el script reproducible que mueve un anio cerrado desde `live` a `archive`.

#### Tareas
- Crear script ejecutable desde `cron-master` o wrapper equivalente.
- Soportar `dry-run`, `report-only` y ejecucion real.
- Exportar desde `live`, importar en `archive` y reindexar ambos lados.
- Evitar duplicados o colisiones al reejecutar.
- Persistir logs e informe por ejecucion.

#### Entregables
- Script de rollover.
- Directorio de reportes o logs de ejecucion.
- Parametrizacion del anio a mover.

#### Criterios de cierre
- El script puede ejecutarse varias veces sin dejar estado inconsistente y deja informe legible.

### Fase 3. Validacion funcional y operativa del rollover
#### Estado
Pendiente

#### Objetivo
Demostrar que el movimiento anual no rompe contenido, URLs ni busqueda.

#### Tareas
- Preparar dataset de prueba para un anio cerrado.
- Validar conteos antes y despues.
- Validar URLs, taxonomias y medios compartidos.
- Validar reindexado y resultados de busqueda unificada.
- Ejecutar smoke tests de plataforma tras el movimiento.

#### Entregables
- Smoke o checks especificos del rollover.
- Evidencia documentada de una ejecucion completa en laboratorio.
- Actualizacion del runbook con el flujo anual.

#### Criterios de cierre
- El anio movido desaparece de `live`, aparece en `archive` y sigue siendo accesible por su URL canonica y por busqueda.

### Fase 4. Contrato operativo de IA-Ops Bootstrap
#### Estado
Pendiente

#### Objetivo
Fijar de forma cerrada que puede leer, como lo filtra y como responde el bootstrap IA-Ops.

#### Tareas
- Inventariar fuentes permitidas: Docker, Nginx, PHP, WordPress, MySQL y Elasticsearch.
- Inventariar tambien checks minimos de host: memoria, `load average`, disco, CPU e indicadores simples de I/O wait si son accesibles.
- Definir checks minimos de servicio: estado de contenedores, healthchecks, `5xx`, errores recientes y salud de alias/indices.
- Definir checks minimos de aplicacion: admin/login, busqueda, routing `live/archive` y smokes relevantes.
- Definir checks de cron: ultima ejecucion correcta, retrasos y fallos recientes de tareas criticas.
- Definir comandos permitidos y comandos sensibles.
- Definir la redaccion de emails e IPs publicas.
- Fijar el formato de salida de `Sentry Agent` y `Nightly Auditor`.
- Fijar severidades y umbrales iniciales para alertas basicas reactivas y para la auditoria nocturna.
- Alinear el contrato con `docs/ia-ops-bootstrap-interface.md`.

#### Entregables
- Documento operativo definitivo del bootstrap IA-Ops.
- Configuracion o plantilla de fuentes permitidas.
- Inventario de checks, umbrales y severidades iniciales.
- Ejemplos de salida esperada.

#### Criterios de cierre
- El sistema IA-Ops tiene un contrato claro de entrada y salida sin ambiguedad operativa.

### Fase 5. Colectores y wrappers de solo lectura
#### Estado
Pendiente

#### Objetivo
Implementar la base local que permitira a IA-Ops reunir contexto sin tocar estado persistente.

#### Tareas
- Crear wrappers para `docker compose ps`, logs acotados y checks internos.
- Crear wrappers de host para memoria, carga, disco, CPU y estado del daemon Docker.
- Crear recoleccion de salud de Nginx, PHP-FPM, MySQL, Elasticsearch y WordPress.
- Crear recoleccion del estado de cron mediante logs o heartbeats de ultima ejecucion correcta.
- Aplicar filtrado de datos sensibles antes de emitir contexto.
- Dejar salida normalizada para consumo posterior por prompts o automatizacion.
- Preparar evaluacion simple de umbrales para producir alertas basicas sin necesidad de una plataforma externa completa.

#### Entregables
- Scripts o wrappers de recoleccion.
- Redaccion de datos sensibles integrada.
- Checks operativos minimos y alertas basicas documentadas.
- Validacion de lectura sobre el stack local.

#### Criterios de cierre
- Los colectores devuelven contexto util, acotado y seguro sin modificar el runtime.

### Fase 6. Flujos minimos Sentry/Nightly y cierre del proyecto
#### Estado
Pendiente

#### Objetivo
Dejar operativos los flujos minimos del diagnostico reactivo y de la auditoria diaria sobre la plataforma ya instrumentada.

#### Tareas
- Definir flujo de incidente reactivo con contexto minimo.
- Definir flujo programado nocturno con resumen de salud, crecimiento y riesgos.
- Conectar ambos flujos con los colectores y los smokes existentes.
- Incluir en el flujo nocturno el resumen de checks de host, servicios, aplicacion y cron.
- Incluir en el flujo reactivo severidades para caida de servicio, `5xx` repetidos, DB no accesible, alias de busqueda ausente o cron critico retrasado.
- Documentar limites respecto a produccion y siguientes pasos.
- Cerrar documentacion, commit final y tag del proyecto.

#### Entregables
- Flujo minimo `Sentry Agent`.
- Flujo minimo `Nightly Auditor`.
- Actualizacion de `docs/` y runbooks.

#### Criterios de cierre
- El proyecto deja una operacion anual reproducible y una interfaz IA-Ops minima utilizable en laboratorio.

## 8. Riesgos a vigilar
- Mover taxonomias o meta de forma incompleta y dejar contenido inconsistente.
- Borrar en `live` antes de completar validaciones e indexacion.
- Introducir dependencias ocultas entre WordPress, ElasticPress y el script de rollover.
- Filtrar mal el contexto y exponer datos sensibles al salir del host.
- Diseñar IA-Ops demasiado pronto como automatismo de accion en vez de diagnostico de solo lectura.

## 9. Criterio de exito global
- Existe un rollover anual repetible, con `dry-run`, informe y rollback documentado.
- La busqueda sigue unificada tras mover un anio de `live` a `archive`.
- IA-Ops puede leer el estado del stack con contexto acotado, filtrado y accionable.
- IA-Ops dispone de una base minima de monitorizacion operativa: checks de host, servicios, aplicacion y cron, con alertas basicas y severidades iniciales.
- La documentacion deja claro que esta implementado, que esta validado y que sigue siendo limite de la POC.
