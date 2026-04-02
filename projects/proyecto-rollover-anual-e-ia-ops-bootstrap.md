# Proyecto: rollover anual e IA-Ops Bootstrap

## 1. Objetivo
Diseñar e implementar el siguiente salto operativo de la plataforma sobre la POC ya estabilizada:
- un proceso anual, auditable y reversible para mover el anio cerrado desde `live` a `archive`
- una sincronizacion frecuente de usuarios/editorial y otra de plataforma compartida entre `live` y `archive`
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
- Los usuarios, roles y passwords editoriales deben sincronizarse entre `live` y `archive` con una frecuencia mayor que el rollover anual.
- El codigo de plataforma compartido no debe sincronizarse de forma artesanal desde WordPress; theme, plugins y `mu-plugins` deben mantenerse coherentes por despliegue declarativo.
- La configuracion visual o funcional persistida en DB que deba ser comun entre `live` y `archive` necesita su propia sincronizacion logica separada del rollover.
- La futura capa IA-Ops debe ser de solo lectura por defecto y con filtrado de datos sensibles antes de salir del host.
- IA-Ops no sustituye a la monitorizacion minima; se apoya en checks de host, servicios, aplicacion y cron para poder diagnosticar con contexto real.
- En esta iteracion no se introduce `cloudflared` en la POC local.

## 4. Alcance acordado
- Implementar un script de rollover anual `live -> archive`.
- Definir y automatizar validaciones previas y posteriores al movimiento.
- Generar un informe legible y persistente por ejecucion.
- Dejar preparado un modo `dry-run` y un modo `report-only`.
- Diseñar la sincronizacion editorial de usuarios, roles y passwords entre `live` y `archive`.
- Diseñar la sincronizacion de configuracion comun de plataforma entre `live` y `archive`.
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
Completada

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

#### Progreso actual
- Se inicia la implementacion del wrapper de rollover desde host usando `cron-master` como punto de ejecucion de lectura y orquestacion.
- La primera entrega tecnica de esta fase se centra en `dry-run` y `report-only`, con informes persistentes y deteccion previa de seleccion/candidatos, antes de habilitar el movimiento real.
- Se crea `scripts/rollover-content-year.sh` como wrapper inicial y los colectores `scripts/rollover-collect-year-summary.php` y `scripts/rollover-detect-archive-collisions.php`.
- El laboratorio ya genera informes reales en `runtime/reports/rollover/` para un anio objetivo, sin modificar `live` ni `archive`.
- Durante esta fase se detecta y corrige una deriva de la semilla: la asignacion de taxonomias estaba creando terminos numericos, lo que invalidaba la futura validacion del rollover.
- Se elimina el hardcode del corte anual en Nginx y se sustituye por una configuracion renderizada desde `config/routing-cutover.env`.
- Se implementa la rama `execute` del wrapper con export logico desde `live`, import controlado en `archive`, reindexado de ambos lados, avance de `routing-cutover` y borrado posterior en origen.
- Se anaden `scripts/rollover-export-year.php`, `scripts/rollover-import-snapshot.php` y `scripts/rollover-delete-source-posts.php` como primitivas de contenido para el movimiento anual.
- La validacion no destructiva ya cubre sintaxis shell/PHP y una nueva ejecucion `report-only` con snapshots persistentes de origen y backup de `archive`.
- Se ejecuta en laboratorio el rollover real de `2024`, con informe persistente en `runtime/reports/rollover/2024-execute-20260402T115117Z.md`.
- Tras la ejecucion, el corte de routing avanza a `archive<=2024` y `live>=2025`, y la validacion funcional general del stack vuelve a verde.
- Durante la validacion posterior se detecta un hueco de orquestacion: el wrapper no republicaba el alias `n9-search-posts` tras reindexar. Ya queda corregido para siguientes ejecuciones y restaurado en el laboratorio actual.
- La secuencia `execute` queda ya demostrada en laboratorio: export, import, reindex, avance de cutover, borrado y republicacion del alias de lectura.

#### Decisiones tomadas
- No se forzara aun la rama `execute` destructiva hasta cerrar el frente de sincronizacion editorial y de plataforma, porque el drift de usuarios y configuracion puede invalidar una importacion aparentemente correcta.
- La fase actual prioriza prechequeo, inventario y evidencia persistente antes de habilitar la rama destructiva.
- La frontera `archive/live` deja de vivir fija en `poc-routing.conf` y pasa a ser gobernable por configuracion versionada, requisito necesario para el rollover anual real.
- La secuencia operativa del `execute` queda fijada como: exportar, importar en `archive`, reindexar `archive`, avanzar routing, borrar en `live` y reindexar `live`.
- El borrado fisico en `live` sigue requiriendo confirmacion manual previa aunque la rama tecnica ya exista.

#### Lecciones aprendidas
- El rollover de contenido no puede tratarse como el unico mecanismo de consistencia entre `live` y `archive`; habia un hueco real en usuarios y configuracion de plataforma.
- Los checks de pre-ejecucion solo son utiles si el dataset de laboratorio mantiene taxonomias y terminos coherentes con el comportamiento editorial esperado.
- Sin corte anual configurable en Nginx, el `execute` del rollover puede mover contenido correctamente y aun asi romper el frontend por enrutado obsoleto.
- Tener la rama `execute` codificada no equivale a darla por segura: el riesgo real sigue estando en la validacion end-to-end del movimiento y en el orden de rollback si falla despues del cambio de routing.
- El rollover debe republishar explicitamente el alias de lectura de Elasticsearch despues del reindexado; asumir que ElasticPress lo conserva por si solo no es seguro.
- En local, los cambios sobre `wordpress/mu-plugins` no entran en runtime hasta resincronizar `runtime/wp-root/shared/mu-plugins`, porque ese directorio compartido es la fuente que realmente monta Compose.

### Fase 3. Validacion funcional y operativa del rollover
#### Estado
Completada

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

#### Progreso actual
- Se crea `scripts/smoke-rollover-year.sh` como verificador especifico por anio, con modos `pre` y `post`.
- La validacion `pre` del anio `2024` ya pasa en laboratorio: corte anual, distribucion de contenido, URL canonica y busqueda unificada siguen coherentes antes del movimiento real.
- La checklist operativa del rollover incorpora ya el uso explicito de este smoke antes y despues del `execute`.
- La validacion `post` del anio `2024` ya pasa completa: distribucion de contenido, routing, URL canonica, busqueda unificada y smokes generales.
- Se corrige un bug de la UI de busqueda del laboratorio: algunos resultados movidos renderizaban `href=""` porque `live` consultaba IDs que ya solo existian en `archive`. La correccion queda en `wordpress/mu-plugins/n9-elasticpress-alias.php`.

### Fase 4. Contrato de sincronizacion editorial y de plataforma
#### Estado
Completada

#### Objetivo
Fijar de forma cerrada que debe sincronizarse con alta frecuencia entre `live` y `archive`, y que debe seguir gestionandose por despliegue declarativo.

#### Tareas
- Inventariar entidades editoriales a sincronizar: usuarios, hashes de password, emails, roles y capacidades.
- Determinar que metadata editorial si debe sincronizarse y cual debe excluirse.
- Fijar que partes de theme/plugins se mantienen por despliegue declarativo y cuales requieren sincronizacion logica.
- Inventariar configuraciones de DB que deben permanecer alineadas: widgets, menus, opciones de tema o de plugins compartidos.
- Definir frecuencia prevista: diaria o bajo demanda para editorial; por despliegue o cambio para plataforma.
- Documentar rollback operativo de ambas sincronizaciones.

#### Entregables
- Documento operativo definitivo de sincronizacion editorial y de plataforma.
- Inventario de entidades sincronizables y exclusiones.
- Criterios de rollback y de validacion.

#### Criterios de cierre
- El sistema tiene un contrato claro de consistencia frecuente entre `live` y `archive`, separado del rollover anual.

#### Progreso actual
- Se crea `docs/live-archive-sync-contract.md` con la separacion formal entre rollover anual, sincronizacion editorial y sincronizacion de plataforma.
- Quedan definidas las cuentas bootstrap excluidas por diseno: `n9liveadmin` y `n9archiveadmin`.
- Se crean snapshots de solo lectura para estado editorial y de plataforma, y un primer informe de drift `live/archive`.
- El primer informe de drift del laboratorio confirma `editorial_drift: no` y `platform_drift: no` bajo las reglas actuales y excluyendo las cuentas bootstrap.

#### Decisiones tomadas
- Los admins bootstrap de cada contexto no forman parte de la sincronizacion editorial; se preservan como cuentas locales de emergencia.
- La sincronizacion editorial tendra `live` como fuente de verdad y no copiara sesiones ni metadata efimera.
- El codigo de theme/plugins/mu-plugins sigue gobernado por despliegue declarativo; la sincronizacion logica se limita a configuracion persistida en DB y a una allowlist controlada.

#### Lecciones aprendidas
- Sin excluir cuentas bootstrap, la sincronizacion editorial generaria falsos positivos permanentes de drift.
- La consistencia de plataforma no puede medirse solo por plugins activos; hay que incluir tambien opciones comunes como widgets, menus y `theme_mods`.
- Un informe de drift de solo lectura da una base mucho mas fiable para abrir la sync real que asumir consistencia por inspeccion manual.

### Fase 5. Implementacion de sincronizacion editorial
#### Estado
Completada

#### Objetivo
Implementar la base reproducible para sincronizar usuarios, hashes de password, roles y capacidades entre `live` y `archive`.

#### Tareas
- Crear sincronizacion idempotente de usuarios y roles.
- Preservar coherencia de hashes de password sin exponer credenciales en claro.
- Excluir sesiones, tokens y metadata efimera.
- Dejar informe y validacion de altas, bajas y cambios aplicados.

#### Entregables
- Script de sincronizacion editorial.
- Informe de ejecucion y validacion.
- Documentacion de frecuencia y rollback.

#### Criterios de cierre
- `archive` mantiene usuarios y permisos editoriales alineados con `live` sin copiar DB completas.

#### Progreso actual
- Se crean `scripts/sync-editorial-users.sh`, `scripts/sync-editorial-source-snapshot.php`, `scripts/sync-editorial-plan.php` y `scripts/sync-editorial-apply.php`.
- La herramienta soporta `report-only`, `dry-run` y `apply`.
- En esta primera iteracion, las bajas quedan reportadas como `stale_users`, pero no se eliminan automaticamente.
- La validacion funcional se realiza con un usuario editorial de laboratorio `redactor-lab` creado en `live` y sincronizado correctamente hacia `archive`.
- Los informes operativos dejan de exponer hashes de password reales; solo muestran digest de comparacion.
- Tras la aplicacion de la sync, el informe de drift vuelve a `editorial_drift: no`, confirmando alineacion efectiva entre `live` y `archive` para usuarios editoriales.

#### Decisiones tomadas
- La sincronizacion editorial usara `live` como fuente de verdad y `archive` como destino.
- La aplicacion real preserva hashes de password, roles y capacidades, sin exponer passwords en claro.
- La primera version no borra usuarios sobrantes en `archive`; los informa para revision.

#### Lecciones aprendidas
- Para este frente, el riesgo principal no es crear o actualizar usuarios, sino borrar demasiado pronto una cuenta que aun tenga valor operativo en `archive`.
- Los informes de sincronizacion no deben incluir hashes reales aunque vivan bajo `runtime/`; la salida util para auditoria debe estar saneada por defecto.

### Fase 6. Implementacion de sincronizacion de plataforma compartida
#### Estado
Completada

#### Objetivo
Mantener la consistencia funcional y visual entre `live` y `archive` en aquello que no debe divergir.

#### Tareas
- Formalizar que codigo de theme/plugins se despliega igual en ambos contextos.
- Implementar o documentar sincronizacion de widgets, menus y opciones compartidas de DB.
- Evitar que `archive` derive funcionalmente respecto a `live` salvo en aquello que se quiera separar de forma explicita.
- Dejar validacion e informe de drift o de sincronizacion aplicada.

#### Entregables
- Script o procedimiento de sincronizacion de plataforma.
- Validacion de consistencia de configuracion compartida.
- Documentacion de exclusiones y divergencias permitidas.

#### Criterios de cierre
- `archive` mantiene el mismo plano funcional/visual que `live` alli donde el proyecto lo exige.

#### Progreso actual
- Se crea una allowlist inicial para sync de plataforma: `sidebars_widgets`, `nav_menu_locations` y `theme_mods_<theme-activo>`.
- Se crean `scripts/sync-platform-source-snapshot.php`, `scripts/sync-platform-plan.php`, `scripts/sync-platform-apply.php` y `scripts/sync-platform-config.sh`.
- El drift de `active_plugins`, `template` y `stylesheet` queda reportado, pero no se corrige desde la sync de plataforma porque sigue siendo responsabilidad del despliegue declarativo.
- La validacion funcional se realiza generando drift controlado en `live` mediante una `theme_mod` de laboratorio (`n9_lab_banner`) y cerrandolo despues en `archive` con la sync.
- Tras aplicar la sync de plataforma, el informe global vuelve a `platform_drift: no`.

#### Decisiones tomadas
- La primera version de la sync de plataforma solo aplica opciones persistidas en DB con una allowlist corta y revisable.
- El codigo y la activacion base de plugins/tema permanecen fuera del `apply` y solo entran en reporting.

#### Lecciones aprendidas
- Si la sync de plataforma intenta corregir tambien el codigo o la activacion de plugins, mezcla dos planos operativos distintos y se vuelve mas fragil.
- Una allowlist corta pero validada es mucho mas defendible que intentar sincronizar opciones de plugins sin contrato previo.

### Fase 7. Contrato operativo de IA-Ops Bootstrap
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

### Fase 8. Colectores y wrappers de solo lectura
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

### Fase 9. Flujos minimos Sentry/Nightly y cierre del proyecto
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
- El proyecto deja una operacion anual reproducible, sincronizaciones frecuentes coherentes y una interfaz IA-Ops minima utilizable en laboratorio.

## 8. Riesgos a vigilar
- Mover taxonomias o meta de forma incompleta y dejar contenido inconsistente.
- Borrar en `live` antes de completar validaciones e indexacion.
- Introducir dependencias ocultas entre WordPress, ElasticPress y el script de rollover.
- Dejar fuera del plan el drift de usuarios o de configuracion compartida y descubrirlo tarde cuando `archive` ya no sea administrable de forma consistente.
- Filtrar mal el contexto y exponer datos sensibles al salir del host.
- Diseñar IA-Ops demasiado pronto como automatismo de accion en vez de diagnostico de solo lectura.

## 9. Criterio de exito global
- Existe un rollover anual repetible, con `dry-run`, informe y rollback documentado.
- La busqueda sigue unificada tras mover un anio de `live` a `archive`.
- `archive` mantiene consistencia editorial y de plataforma con `live` mediante flujos separados del rollover anual.
- IA-Ops puede leer el estado del stack con contexto acotado, filtrado y accionable.
- IA-Ops dispone de una base minima de monitorizacion operativa: checks de host, servicios, aplicacion y cron, con alertas basicas y severidades iniciales.
- La documentacion deja claro que esta implementado, que esta validado y que sigue siendo limite de la POC.
