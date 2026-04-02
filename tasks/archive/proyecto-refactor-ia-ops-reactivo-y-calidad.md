# Proyecto: refactor IA-Ops reactivo y calidad base

## 1. Objetivo
Mejorar la capa operativa Python y el plano reactivo del laboratorio sin entrar todavia en backups productivos, endurecimiento serio de plataforma ni automatizacion destructiva.

La meta de esta fase es dejar:
- una base minima de calidad y validacion reproducible
- una arquitectura Python menos concentrada y mas testeable
- una deteccion reactiva mas alineada con el contrato operativo actual
- un directorio `scripts/` mas racionalizado y con fronteras mas claras
- una linea futura documentada para interaccion humana via Telegram

## 2. Decisiones ya tomadas
- Backups y restore verificados se aplazan a la fase de preproduccion.
- Hardening fuerte de plataforma y exposicion real tambien se aplaza para no complicar esta maquina local.
- La idea de Telegram bidireccional no entra en implementacion ahora; queda documentada como roadmap posterior dentro de esta misma POC.
- Este laboratorio no requiere mantener compatibilidad historica por defecto si una limpieza interna mejora claridad, siempre que se actualicen runbooks, smokes y documentacion del propio repo.
- En este host local no se dejan bloques gestionados de `crontab` instalados de forma persistente; esa programacion queda como baseline para preproduccion y produccion.
- La fase actual se centra en los puntos:
  - refactor de Python y arquitectura de `ops/`
  - cierre de brecha entre contrato IA-Ops y deteccion reactiva real
  - baseline de calidad y CI/CD ligera
  - refactor e inventario operativo del directorio `scripts/`

## 3. Alcance de esta fase

### Entra en alcance
- Reorganizar `ops/` para separar CLI, dominio, renderizado, notificaciones y ejecucion.
- Reducir la concentracion de logica en `ops/cli/ia_ops.py`.
- Introducir tests unitarios y de integracion ligeros para la capa Python.
- Formalizar validaciones de calidad reproducibles para Python, shell y Compose.
- Revisar y racionalizar el directorio `scripts/` sin romper runbooks ni wrappers utiles.
- Ampliar el flujo reactivo para cubrir mejor host, app, MySQL, drift y sintomas operativos relevantes.
- Mejorar la calidad del drift report para que sea mas accionable.
- Documentar la futura integracion Telegram con aprobacion humana explicita.

### Queda fuera de alcance
- Backups reales, retencion, cifrado y restore drills.
- Endurecimiento productivo de red, Tunnel, Access o secretos gestionados.
- Remediacion automatica destructiva.
- Conversacion abierta con un agente que ejecute shell libre desde Telegram.
- Pipeline de despliegue a produccion o preproduccion.

## 4. Orden de ejecucion recomendado

### Fase 1. Calidad base y red de seguridad
#### Objetivo
No tocar arquitectura sin una base de validacion minima que detecte regresiones.

#### Tareas
- Definir baseline local de checks para `ops/`, shell y Compose.
- Mantener `py_compile` y ayuda CLI, pero anadir tests automatizados reales.
- Anadir validacion de sintaxis shell y PHP cuando aplique.
- Añadir una CI ligera no destructiva orientada a calidad, no a despliegue.
- Preparar una clasificacion inicial de `scripts/`: entrypoints publicos, wrappers, helpers internos y candidatos a deprecacion.

#### Resultado esperado
- El repositorio dispone de una puerta minima de calidad reproducible.
- Cada refactor posterior puede validarse sin depender solo de smokes manuales.

#### Validacion minima
- ejecucion local de checks de Python
- validacion de sintaxis shell/PHP
- `docker compose config`
- ejecucion de la suite inicial de tests
- inventario de `scripts/` revisado y trazable

#### Rollback
- revertir workflow y scripts de validacion
- mantener el script actual `check-python-tooling.sh` como baseline minimo

### Fase 2. Refactor de arquitectura Python
#### Objetivo
Separar responsabilidades y bajar el acoplamiento actual del plano IA-Ops.

#### Tareas
- Extraer logica de ensamblado de contexto fuera de la CLI.
- Separar generacion de informes, reglas de severidad, notificaciones y ejecucion reactiva.
- Introducir modelos de incidente y de auditoria mas explicitos.
- Reducir dependencias duras con nombres de contenedor hardcodeados donde sea razonable.
- Preparar puntos de extension para nuevos canales y futuros playbooks controlados.
- Mover la logica de `Nightly Auditor` y `Sentry Agent` fuera de `ops/cli/ia_ops.py` a modulos testeables de `ops/runtime/`.

#### Resultado esperado
- `ops/cli/ia_ops.py` queda como capa de entrada, no como modulo dominante.
- Los collectors y evaluadores se pueden probar de forma aislada.

#### Validacion minima
- tests de unidad sobre reglas y ensamblado de contexto
- `python3 -m ops.cli.ia_ops --help`
- ejecucion real de `run-nightly-auditor` y `run-sentry-agent` tras el refactor

#### Rollback
- revertir el refactor manteniendo CLI y contratos actuales
- conservar wrappers shell como interfaz estable

### Fase 2b. Refactor del directorio `scripts/`
#### Objetivo
Reducir ruido operativo y dejar claro que scripts son interfaz humana estable y cuales son helpers internos del plano WordPress o IA-Ops.

#### Tareas
- Inventariar todos los ficheros de `scripts/` y clasificarlos por rol real.
- Identificar scripts legacy o casi sin uso y decidir si se eliminan.
- Evitar wrappers de compatibilidad innecesarios mientras el entorno siga siendo de laboratorio.
- Separar mejor los scripts orientados a operador de los scripts auxiliares consumidos por Python/PHP.
- Revisar nombres, coherencia y documentacion de entrypoints visibles en runbooks.
- Cuando no aporten valor real, eliminar compatibilidad heredada en lugar de preservarla por inercia, porque el entorno sigue siendo de laboratorio y no de produccion.

#### Resultado esperado
- `scripts/` deja de ser un cajon mezclado de bootstrap, wrappers, helpers internos y restos de fases anteriores.
- El operador entiende mejor que entrada usar y que piezas no debe invocar directamente.

#### Validacion minima
- los runbooks actuales siguen funcionando con los entrypoints acordados
- los scripts internos necesarios para sync y rollover siguen accesibles desde Python/PHP

#### Rollback
- restaurar estructura y nombres anteriores del directorio `scripts/`
- restaurar entrypoints borrados solo si reapareciese una necesidad operativa real

### Fase 3. Cierre de brecha del plano reactivo
#### Objetivo
Alinear mejor la deteccion reactiva con el contrato operativo ya documentado.

#### Tareas
- Revisar que checks del contrato ya existen, cuales faltan y cuales son demasiado debiles.
- Extender el evaluador reactivo para incorporar señales de aplicacion, MySQL, drift y smokes relevantes.
- Hacer que el drift report deje de ser solo binario cuando aporte valor operativo.
- Mejorar evidencia, severidad y contexto del `Sentry Agent`.
- Mantener el principio de solo lectura y confirmacion humana para cualquier accion sensible.

#### Resultado esperado
- El plano reactivo deja de depender casi solo de runtime, Elastic y cron.
- Los avisos contienen mejor evidencia y menor ruido.

#### Validacion minima
- simulacion controlada de incidentes sobre servicios y checks
- ejecucion manual de `run-sentry-agent`, `run-nightly-auditor` y `run-reactive-watch`
- revision de cooldown, deduplicacion y reportes generados

#### Rollback
- volver al evaluador reactivo actual
- mantener cooldown, lock y salida a Telegram unidireccional tal como estan

## 5. Roadmap posterior documentado: Telegram con aprobacion humana

### Objetivo futuro
Permitir interaccion humana desde Telegram sobre alertas ya emitidas, con conversacion acotada, plan propuesto y aprobacion explicita antes de ejecutar acciones permitidas.

### Modelo propuesto
- `Nightly Auditor` o `Sentry Agent` emite alerta con identificador de incidente.
- Un proceso local escucha actualizaciones de Telegram y enlaza respuestas a ese incidente.
- El agente solo puede:
  - ampliar contexto permitido
  - generar un diagnostico mas extenso
  - proponer acciones allowlisted
  - pedir confirmacion explicita antes de ejecutar
- La ejecucion real se limita a comandos o playbooks declarados y auditables.
- Tras aplicar una accion, el sistema debe verificar resultado y dejar informe.

### Restricciones obligatorias
- nada de shell libre desde Telegram
- nada de acciones destructivas sin confirmacion explicita
- todo debe quedar trazado con incidente, usuario, comando/plan aprobado y validacion posterior
- el canal de chat no sustituye backups, rollback ni observabilidad

### Dependencias previas
- mejor arquitectura Python
- mejor calidad de señales
- baseline de tests y validacion
- catalogo de acciones permitidas y rollback asociado

## 6. Riesgos residuales
- Seguimos en laboratorio local; la confianza en rendimiento y seguridad sigue siendo limitada.
- Sin backups verificados, cualquier futura automatizacion de remediacion debe mantenerse conservadora.
- Sin endurecimiento de edge/origin, no conviene extrapolar el comportamiento de esta maquina a produccion.

## 7. Siguiente paso recomendado
Abrir la siguiente iteracion ya como proyecto separado:
- mantener Telegram bidireccional como roadmap posterior con aprobacion humana explicita
- decidir si el siguiente frente es la simplificacion de `runtime/wp-root` o una nueva fase de endurecimiento al pasar a preproduccion

## 8. Estado
Cerrado

## 9. Cambios implementados
- Se crea este plan activo para fijar alcance, orden de ejecucion y limites de la fase.
- Se amplia el plan para incluir el refactor del directorio `scripts/` como parte explicita del proyecto actual.
- Se implementa una primera reduccion de acoplamiento en `ops/` mediante una capa comun de servicios y contexto operativo.
- `ops/cli/ia_ops.py` deja de concentrar la construccion de `Nightly Auditor` y `Sentry Agent`; esa logica pasa a modulos dedicados en `ops/runtime/`.
- Se introducen modelos mas explicitos para auditoria nocturna y diagnostico reactivo en `ops/runtime/nightly.py` y `ops/runtime/sentry.py`.
- Los helpers PHP internos de `sync` y `rollover` salen de la raiz de `scripts/` y pasan a `scripts/internal/`.
- La raiz de `scripts/` queda mas enfocada en entrypoints de operador y wrappers visibles.
- Se anade una comprobacion automatica de layout para evitar que vuelvan helpers PHP internos a la raiz de `scripts/`.
- `bootstrap-wordpress-stubs.sh` se elimina del flujo vigente por no aportar valor operativo real en el laboratorio actual.
- El plano reactivo amplía cobertura y ya considera sintomas de host, login/admin, smokes publicos, MySQL, errores recientes de cron y drift `live/archive`.
- `Sentry Agent` mejora diagnostico para `host`, `be-admin`, `lb-nginx`, `elastic` y `cron-master`.
- Se anaden tests unitarios especificos para el renderizado y la severidad de `nightly` y `sentry`.
- Se corrigen los docroots WordPress del laboratorio para que vuelvan a servir el front controller real en lugar de stubs de diagnostico.
- Se ajusta el collector de memoria en macOS para evitar falsos criticos usando `memory_pressure` cuando esta disponible.
- Los checks de cron recuperan los defaults documentados para sync editorial y de plataforma cuando la configuracion local es incompleta.
- Los checks de `lb-nginx` pasan a evaluar `4xx/5xx` sobre una ventana temporal real en lugar de contar cualquier codigo antiguo dentro del tail.
- Se anade una comprobacion ligera de entrypoints WordPress para detectar otra vez `index.php` stubados en `runtime/wp-root`.
- Se anade capacidad reproducible para programar `sync-editorial-users` y `sync-platform-config` via bloque gestionado de `crontab`, con horarios, modo y logs configurables.
- Se corrige el borrado de bloques gestionados cuando el `crontab` contiene solo entradas del proyecto, evitando que quede cron residual en este host.
- Se deja documentado que este host local no mantiene cron gestionado persistente, aunque en preproduccion y produccion si deberian instalarse `nightly`, `reactive` y `sync`.
- El drift `live/archive` deja de ser casi binario y pasa a generar resumentes accionables para editorial y plataforma con diffs acotados reutilizables por `Nightly Auditor`, `Sentry Agent` y el payload reactivo.
- Los collectors de `host`, `app`, `runtime`, `cron` y `elastic` quedan mas homogeneos en metadatos operativos (`source`, thresholds, expected codes y severidad normalizada donde aplica) para reducir inferencia en reportes y diagnostico.

## 10. Validacion ejecutada
- Revision manual de documentacion, `compose`, capa `ops/` y scripts asociados.
- Confirmacion de que `./scripts/check-python-tooling.sh` sigue pasando en el estado actual del repositorio.
- Ejecucion completa de `./scripts/check-quality.sh` con tests Python, sintaxis shell/PHP y `docker compose config`.
- Ejecucion real de `python3 -m ops.cli.ia_ops run-nightly-auditor --telegram-preview --no-write-report`.
- Ejecucion real de `python3 -m ops.cli.ia_ops run-sentry-agent --service host --telegram-preview --no-write-report`.
- Comprobacion de que las referencias vivas a helpers PHP antiguos en raiz ya no existen tras el movimiento a `scripts/internal/`.
- Eliminacion del script heredado de stubs para evitar compatibilidad innecesaria en el laboratorio actual.
- Ejecucion real de `python3 -m ops.cli.ia_ops run-reactive-watch --write-report` con incidencias emitidas y reporte JSON generado.
- Ejecucion completa de `./scripts/smoke-routing.sh` y `./scripts/smoke-search.sh` tras restaurar los entrypoints WordPress.
- Ejecucion real de `python3 -m ops.cli.ia_ops collect-host-health`, `collect-runtime-health` y `collect-cron-health` con severidades saneadas.
- Nueva ejecucion de `python3 -m ops.cli.ia_ops run-reactive-watch --write-report` con `incidents_seen: []`.
- Validacion de renderizado del nuevo bloque gestionado de sync y cobertura unitaria del scheduling baseline.
- Instalacion real y retirada posterior de los bloques gestionados de `sync` y `nightly` en este host para validar tanto alta como baja.
- Verificacion final de `crontab -l` alineada con la politica local de no dejar cron gestionado persistente.
- Ejecucion real de `python3 -m ops.cli.ia_ops report-live-archive-sync-drift`, `run-nightly-auditor --telegram-preview --no-write-report` y `run-reactive-watch --write-report` con el nuevo resumen de drift operativo.
- Ejecucion completa de `./scripts/check-quality.sh` con 35 tests y nueva ejecucion real de `run-nightly-auditor --telegram-preview --no-write-report` tras la homogeneizacion de collectors.

## 11. Lecciones aprendidas
- La siguiente mejora util no es meter mas canales, sino reducir deuda de arquitectura y subir el liston de validacion.
- El valor de Telegram bidireccional depende mas de la gobernanza de acciones que del canal en si.
- El numero de scripts no es por si solo el problema; el problema real es mezclar interfaces humanas, compatibilidad y helpers internos sin una frontera visible.
- Mover helpers internos fuera de la raiz aporta orden real sin romper runbooks si los entrypoints publicos se mantienen estables.
- En este repo conviene evitar la compatibilidad heredada innecesaria: si un wrapper o script ya no aporta valor operativo en laboratorio, la opcion preferida es simplificarlo o borrarlo y actualizar la documentacion asociada.
- En una POC local sin consumidores externos reales, borrar legado muerto suele ser mejor que encapsularlo.
- En laboratorio, varios sintomas aparentemente graves venian de dos cosas distintas: stubs de docroot cometidos por error y collectors con semantica demasiado agresiva para macOS o para logs antiguos.
- La ruta de borrado de `crontab` necesita contemplar el caso de bloque unico; un fichero vacio no garantiza por si solo dejar el usuario sin cron en todos los entornos.
- El drift aporta mucho mas valor cuando se resume por dominio del negocio del repo que cuando se intenta presentar como igualdad binaria o diff generico de JSON.
- Si se normaliza una severidad interna de un collector, conviene no pisar ciegamente el `status` bruto de la fuente si ese valor tambien se usa como baseline operativo o evidencia humana.
