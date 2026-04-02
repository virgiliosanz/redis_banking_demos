# Proyecto: racionalizacion de docs y hardening operativo

## 1. Objetivo
Ordenar la documentacion viva del repositorio para que deje de mezclar arquitectura global, contratos de dominio y notas operativas, y cerrar el siguiente escalon operativo de IA-Ops: definir el plano reactivo real, pulir deuda tecnica pequena y dejar una base mas mantenible antes de pensar en produccion.

## 2. Relacion con la documentacion vigente
- Documentacion viva actual: `docs/`
- Runbook operativo local: `docs/poc-local-runbook.md`
- Contrato IA-Ops actual: `docs/ia-ops-bootstrap-contract.md`
- Proyecto previo completado: `tasks/archive/proyecto-refactor-shell-a-python-y-operacion-ia-ops.md`

## 3. Motivacion
- `docs/` sigue teniendo demasiada fragmentacion para el tamano real del proyecto.
- `docs/project.md` duplica el papel que deberia tener `docs/README.md`.
- El dominio `live/archive` esta repartido entre rollover, checklist y sync contract, cuando en realidad pertenece al mismo ciclo de vida de contenido.
- El plano reactivo de IA-Ops sigue abierto: `Nightly Auditor` ya tiene `cron`, pero el disparo de `Sentry Agent` aun necesita una decision de arquitectura.
- Queda deuda operativa pequena pero molesta: warnings evitables, semantica de flags y tooling Python minimo.

## 4. Alcance acordado
- Redefinir la arquitectura documental viva de `docs/`.
- Eliminar `docs/project.md` y promover `docs/README.md` como documento principal.
- Fusionar la documentacion del ciclo de vida `live/archive` cuando exista solapamiento real.
- Revisar si `docs/ia-ops-bootstrap-contract.md` debe mantenerse separado o reducirse e integrarse mejor con el runbook.
- Decidir el disparo reactivo de IA-Ops: `Monit` u otra alternativa mas simple.
- Pulir deuda tecnica operativa pequena del stack actual.
- Actualizar `tasks/` para que refleje solo el plan activo y el historico archivado.

## 5. Fuera de alcance
- Rehacer toda la documentacion tecnica desde cero.
- Cambiar la topologia funcional `live/archive/admin`.
- Introducir observabilidad pesada tipo Prometheus/Grafana.
- Automatizar remediacion destructiva.
- Abrir un proyecto nuevo de produccion real o despliegue externo.

## 6. Principios de trabajo
- `docs/` debe contener documentacion viva por dominio, no historico de fases.
- `tasks/` debe contener planes y ejecucion de trabajo, no arquitectura del sistema.
- Cada documento debe tener un unico papel claro y no competir con otro.
- Si dos documentos describen el mismo dominio con distinto nivel de detalle, deben fusionarse.
- El plano reactivo debe ser simple, auditable y acoplado al minimo a software externo.
- Toda decision operativa nueva debe mantener runbooks y rollback claros.

## 7. Hipotesis de reorganizacion

### 7.1 Arquitectura documental objetivo
- `docs/README.md`
  - documento principal de arquitectura, estado actual, componentes, routing y mapa documental
- `docs/poc-local-runbook.md`
  - operacion local, pruebas manuales y mantenimiento de laboratorio
- `docs/content-lifecycle-live-archive.md`
  - rollover anual
  - sincronizacion editorial
  - sincronizacion de plataforma
  - checklist y rollback
- `docs/ia-ops-bootstrap-contract.md` o renombre equivalente si sigue mereciendo documento propio
- `docs/search-architecture-live-archive.md`
- `docs/cache-policy-by-context.md`
- `docs/wordpress-persistence-layout.md`
- `docs/origin-behind-cloudflare-tunnel.md`

### 7.2 Eliminaciones y fusiones previstas
- eliminar `docs/project.md`
- fusionar `docs/annual-content-rollover.md` y `docs/annual-content-rollover-checklist.md`
- integrar `docs/live-archive-sync-contract.md` dentro del documento de ciclo de vida `live/archive`
- revisar si partes de `docs/ia-ops-bootstrap-contract.md` deberian pasar al runbook o quedarse como contrato autonomo

### 7.3 Plano reactivo a decidir
Opciones a evaluar:
- `Monit` como disparador de `Sentry Agent`
- alternativa mas simple con `cron` + chequeo reactivo ligero
- alternativa basada en logs/healthchecks del propio repo sin dependencia nueva

Criterios:
- simplicidad real
- observabilidad del trigger
- facilidad de rollback
- coste de operacion en laboratorio y cercania a produccion

## 8. Fases

### Fase 1. Contrato de arquitectura documental
#### Estado
Completada

#### Objetivo
Cerrar que papel tiene cada documento vivo y que fusiones se van a ejecutar.

#### Tareas
- Inventariar `docs/` y clasificar cada fichero por dominio y nivel de autoridad.
- Decidir el documento principal de arquitectura.
- Decidir la fusion del dominio `live/archive`.
- Decidir si `ia-ops-bootstrap-contract` sigue como documento propio.
- Decidir la ubicacion correcta del proyecto previo cerrado dentro de `tasks/`.

#### Criterios de cierre
- Existe un mapa documental objetivo aprobado, con lista explicita de ficheros a mantener, fusionar, mover o eliminar.

#### Progreso actual
- Se inventaria `docs/` completo y se confirma que el problema principal no es volumen sino solapamiento de autoridad documental.
- Se valida que `docs/project.md` actua como documento principal de facto, compitiendo con `docs/README.md`.
- Se confirma que el dominio `live/archive` esta fragmentado entre:
  - `docs/annual-content-rollover.md`
  - `docs/annual-content-rollover-checklist.md`
  - `docs/live-archive-sync-contract.md`
- Se confirma que `docs/ia-ops-bootstrap-contract.md` pertenece a otro dominio distinto y no debe mezclarse ciegamente con el ciclo de vida de contenido.
- Se detecta que `tasks/` aun mantiene en raiz un proyecto ya cerrado:
  - `tasks/proyecto-refactor-shell-a-python-y-operacion-ia-ops.md`

#### Mapa documental objetivo aprobado
- `docs/README.md`
  - documento principal de arquitectura, estado actual, topologia, routing, operacion global y mapa del resto de documentos
- `docs/poc-local-runbook.md`
  - runbook local de laboratorio, bootstrap, validacion manual, cron y notificaciones
- `docs/content-lifecycle-live-archive.md`
  - documento unico para:
    - rollover anual
    - sincronizacion editorial
    - sincronizacion de plataforma
    - validaciones previas y posteriores
    - rollback y checklist operativa
- `docs/ia-ops-bootstrap-contract.md`
  - contrato operativo de checks, saneado, severidades, fuentes y salida de agentes
- `docs/search-architecture-live-archive.md`
  - arquitectura de indices, alias y degradacion de busqueda
- `docs/cache-policy-by-context.md`
  - politica de cache en origen y frontera con Cloudflare
- `docs/wordpress-persistence-layout.md`
  - mounts, persistencia compartida y aislamiento por contexto
- `docs/origin-behind-cloudflare-tunnel.md`
  - modelo de origen privado y frontera edge/origen para produccion

#### Decisiones tomadas
- `docs/project.md` deja de tener sentido y debe ser absorbido por `docs/README.md` en la siguiente fase.
- `docs/annual-content-rollover.md` y `docs/annual-content-rollover-checklist.md` deben fusionarse.
- `docs/live-archive-sync-contract.md` no debe sobrevivir como documento aislado; su contenido pasa al documento unico de ciclo de vida `live/archive`.
- `docs/ia-ops-bootstrap-contract.md` se mantiene como documento propio porque define un dominio distinto: observabilidad, checks, alertas y consumo por agentes.
- `docs/README.md` pasa a ser la autoridad documental global del sistema.
- `tasks/` debe contener solo el plan activo y el historico archivado; el proyecto previo cerrado debe moverse a `tasks/archive/` durante la Fase 2.

#### Lista explicita de ficheros por destino

##### Se mantienen
- `docs/README.md`
- `docs/poc-local-runbook.md`
- `docs/ia-ops-bootstrap-contract.md`
- `docs/search-architecture-live-archive.md`
- `docs/cache-policy-by-context.md`
- `docs/wordpress-persistence-layout.md`
- `docs/origin-behind-cloudflare-tunnel.md`

##### Se fusionan
- `docs/annual-content-rollover.md`
- `docs/annual-content-rollover-checklist.md`
- `docs/live-archive-sync-contract.md`

Destino:
- `docs/content-lifecycle-live-archive.md`

##### Se eliminan tras absorcion
- `docs/project.md`

##### Se mueven en `tasks/`
- `tasks/proyecto-refactor-shell-a-python-y-operacion-ia-ops.md`

Destino:
- `tasks/archive/proyecto-refactor-shell-a-python-y-operacion-ia-ops.md`

#### Lecciones aprendidas
- El caos documental no viene de falta de documentos, sino de mezclar capas: arquitectura global, dominio funcional y runbook.
- El dominio `live/archive` ya ha madurado lo suficiente como para merecer un unico documento de ciclo de vida.
- IA-Ops no debe diluirse dentro del resto de la plataforma; su contrato sigue siendo suficientemente especifico como para vivir aparte.

### Fase 2. Implementacion de la racionalizacion de docs
#### Estado
Completada

#### Objetivo
Ejecutar la fusion y simplificacion de `docs/` sin perder informacion activa.

#### Tareas
- Promover `docs/README.md` a documento principal.
- Absorber `docs/project.md` en `docs/README.md`.
- Fusionar rollover, checklist y sync en el documento final de ciclo de vida.
- Corregir enlaces cruzados y referencias desde runbooks, tareas y scripts.
- Mover a `tasks/archive/` cualquier plan cerrado que siga en `tasks/`.

#### Criterios de cierre
- `docs/` queda con menos documentos, sin duplicidad funcional evidente y con referencias internas correctas.

#### Progreso actual
- `docs/README.md` deja de ser un simple indice y pasa a ser el documento principal de arquitectura, estado y mapa documental.
- `docs/project.md` queda absorbido y eliminado.
- Se crea `docs/content-lifecycle-live-archive.md` como documento unico del dominio `live/archive`.
- Se eliminan por absorcion:
  - `docs/annual-content-rollover.md`
  - `docs/annual-content-rollover-checklist.md`
  - `docs/live-archive-sync-contract.md`
- El proyecto previo cerrado se mueve de `tasks/` a `tasks/archive/`.

#### Resultado documental tras la fase
- `docs/README.md`
- `docs/poc-local-runbook.md`
- `docs/content-lifecycle-live-archive.md`
- `docs/ia-ops-bootstrap-contract.md`
- `docs/search-architecture-live-archive.md`
- `docs/cache-policy-by-context.md`
- `docs/wordpress-persistence-layout.md`
- `docs/origin-behind-cloudflare-tunnel.md`

#### Decisiones tomadas
- El detalle operativo global vive ahora en `docs/README.md`; ya no existe un segundo documento competidor para arquitectura y estado.
- El dominio `live/archive` se consolida en un solo documento porque rollover, sync editorial y sync de plataforma ya forman un mismo ciclo de vida operativo.
- El contrato de IA-Ops se mantiene separado porque define otro bounded context.
- `tasks/` vuelve a reflejar mejor su papel: plan activo en raiz e historico en `tasks/archive/`.

#### Lecciones aprendidas
- Eliminar `project.md` simplifica mas de lo que parece porque obliga a que `README.md` tenga una responsabilidad clara.
- Fusionar checklist y contrato en el dominio `live/archive` mejora la operabilidad: el flujo, las validaciones y el rollback quedan juntos.
- No conviene fusionar por fusionar: `ia-ops-bootstrap-contract.md` sigue mereciendo su propio documento.

### Fase 3. Decision del plano reactivo
#### Estado
Completada

#### Objetivo
Decidir el mecanismo de disparo reactivo de `Sentry Agent` mas simple y defendible para el estado actual del proyecto.

#### Tareas
- Evaluar `Monit` frente a alternativas mas ligeras.
- Definir eventos minimos de disparo.
- Definir como se integra con Telegram.
- Documentar la decision y el por que de las opciones descartadas.

#### Decision en curso
- Se descarta `Monit` para esta iteracion.
- Se implementa un plano reactivo ligero del propio repo:
  - `cron` cada `5` minutos
  - evaluador Python de incidentes
  - deduplicacion y cooldown por incidente
  - disparo de `Sentry Agent` con salida a Telegram
- Eventos minimos previstos:
  - contenedor `unhealthy`, `exited` o `dead`
  - alias `n9-search-posts` ausente
  - cron critico o fuera de ventana
  - `5xx` repetidos en ventana corta
  - `4xx` repetidos en ventana corta

#### Criterios de cierre
- Existe una decision clara del trigger reactivo y un contrato operativo para implementarlo o aplazarlo conscientemente.

#### Progreso actual
- Se descarta `Monit` para esta iteracion por aportar mas complejidad operativa que valor real en el laboratorio actual.
- Se implementa un plano reactivo ligero propio del repo con:
  - `cron` cada `5` minutos
  - evaluador Python de incidentes
  - lock para evitar solapamiento
  - estado persistente para deduplicacion
  - cooldown por incidente
  - disparo de `Sentry Agent` con notificacion a Telegram
- Se crean:
  - `ops/runtime/reactive.py`
  - `scripts/install-reactive-watch-cron.sh`
- Se amplia el runtime collector para contar `4xx` y `5xx` recientes de `lb-nginx`.

#### Decisiones tomadas
- El baseline reactivo queda resuelto con `cron`, no con `Monit`.
- Los disparadores minimos aceptados pasan a ser:
  - contenedor `unhealthy`, `exited` o `dead`
  - alias `n9-search-posts` ausente
  - cron critico o fuera de ventana
  - `5xx` repetidos en ventana corta
  - `4xx` repetidos en ventana corta
- El evaluador reactivo no ejecuta remediacion; solo dispara diagnostico con `Sentry Agent`.
- La integracion con Telegram se hace a traves del propio flujo de `Sentry Agent`, no desde un notificador paralelo.

#### Validacion ejecutada
- `python3 -m py_compile ops/cli/ia_ops.py ops/collectors/runtime.py ops/scheduling/cron.py ops/runtime/reactive.py`
- `python3 -m ops.cli.ia_ops collect-runtime-health`
- `python3 -m ops.cli.ia_ops render-reactive-crontab`
- `python3 -m ops.cli.ia_ops run-reactive-watch --write-report`
- segunda ejecucion inmediata de `run-reactive-watch --write-report` para confirmar deduplicacion

#### Resultado de la validacion
- El bloque de `cron` reactivo se renderiza correctamente.
- La primera ejecucion real emitio incidentes del laboratorio actual y disparo `Sentry Agent`.
- La segunda ejecucion inmediata no reenvi o incidentes gracias al cooldown y al estado persistente.

#### Lecciones aprendidas
- En esta POC, `Monit` duplicaria supervision sin aportar una mejora proporcional.
- El evaluador reactivo propio permite acoplar triggers, reporting y Telegram al contrato real del repo.
- Los `4xx` repetidos merecen trigger propio, pero con umbral mas alto que `5xx` para no confundir ruido con regresiones reales.

### Fase 4. Hardening operativo pequeno
#### Estado
Completada

#### Objetivo
Eliminar fricciones pequenas del tooling actual antes de abrir otro frente mayor.

#### Tareas
- Revisar la semantica de `--no-write-report` frente a notificaciones.
- Eliminar el warning de `mysqladmin` por password en CLI en smokes o checks.
- Definir tooling Python minimo de calidad (`compile`, lint o tests basicos) sin sobredimensionar el repo.
- Revisar si el flujo cron/Telegram necesita guardas adicionales.

#### Progreso actual
- `--no-write-report` deja de implicar envio por defecto; solo se notifica si se fuerza con `--notify-telegram`.
- Se anade `--no-notify-telegram` para ejecucion silenciosa aunque la config local tenga Telegram activado.
- `scripts/smoke-services.sh` deja de pasar passwords de MySQL por linea de comandos.
- Se crea `scripts/check-python-tooling.sh` como baseline reproducible de calidad minima para `ops/`.

#### Validacion ejecutada
- `./scripts/check-python-tooling.sh`
- `./scripts/smoke-services.sh`
- `python3 -m ops.cli.ia_ops run-nightly-auditor --no-write-report --no-notify-telegram`
- validacion indirecta de `Sentry Agent` silencioso reutilizando los mismos colectores y confirmando ausencia de proceso residual

#### Resultado de la validacion
- El baseline Python compila y la CLI principal responde.
- El smoke de servicios ya no muestra el warning de password en `mysqladmin`.
- `Nightly Auditor` ejecuta sin escribir informe y sin enviar Telegram cuando se usa `--no-notify-telegram`.
- La semantica de flags queda mas defendible:
  - `--no-write-report` no implica ya notificar por defecto
  - `--notify-telegram` permite forzar envio
  - `--no-notify-telegram` permite silenciar ejecuciones locales con config activa

#### Lecciones aprendidas
- En herramientas operativas, la semantica por defecto importa mas que la comodidad del laboratorio.
- Separar “persistir informe” de “notificar” evita sorpresas y ruido en canales reales.
- Un baseline minimo de calidad vale mas si se puede ejecutar siempre sin dependencias nuevas.

#### Criterios de cierre
- El tooling actual queda mas limpio, menos ruidoso y con comportamiento menos ambiguo.

### Fase 5. Cierre del proyecto
#### Estado
Pendiente

#### Objetivo
Cerrar el proyecto con documentacion viva actualizada, historico ordenado y plan siguiente sugerido.

#### Tareas
- Actualizar `docs/` con el estado final.
- Actualizar el fichero del proyecto con decisiones y lecciones aprendidas.
- Hacer commit y tag de cierre.
- Proponer el siguiente proyecto recomendado.

#### Criterios de cierre
- `docs/` y `tasks/` reflejan una estructura limpia y el proyecto queda cerrado formalmente.

## 9. Riesgos y atencion especial
- Fusionar documentos sin definir autoridad puede esconder informacion en vez de simplificar.
- `Monit` puede anadir mas complejidad que valor si el trigger reactivo no se mantiene pequeno.
- Cambiar documentacion sin revisar referencias puede romper runbooks y tareas existentes.
- La deuda pequena puede quedarse cronica si no se acota en esta iteracion.

## 10. Siguiente paso recomendado
Abrir la Fase 3 y decidir el plano reactivo de `Sentry Agent` antes de seguir con deuda tecnica menor o software adicional.
