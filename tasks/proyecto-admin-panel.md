# Proyecto: Admin Panel

## Objetivo
Panel de administracion web para la plataforma nuevecuatrouno. Permite operar, diagnosticar y monitorizar la infraestructura desde el navegador (incluido movil).

## Estado: Completado (POC)

## Fases completadas

### Fase 1: Skeleton y funcionalidad base
- Flask skeleton con Bulma CSS dark theme + Font Awesome icons
- Secciones: Sincronizacion, Contenedores, Diagnosticos, Rollover, Crontab
- Ejecucion de comandos CLI via subprocess con output en tiempo real
- Modal de confirmacion para acciones destructivas
- Tips y descripciones de riesgo en cada accion
- Auto-display de reportes generados (.md/.json)
- Tests unitarios del admin panel

### Fase 2: Mejoras operativas
- Dashboard con estado real (contenedores up/down, ultimo nightly, drift status)
- Dashboard con findings: causas de warning/error visibles directamente
- Fechas clickables que abren reportes en el explorador
- Explorador de reportes (/reports/) agrupados por tipo con visualizacion inline
- Historial de ejecuciones (/history/) con timestamp, comando, resultado, duracion
- Filtro de historial: solo registra comandos ops.cli.ia_ops
- Favicon SVG inline (sin 404)
- Endpoint /health con estado del panel + servicios Docker
- Auto-refresh en contenedores (checkbox 30s con timestamp)
- Run Now en crontab para ejecucion bajo demanda

### Fase 3: Collectors con templates ricos
- Refactor de subprocess+JSON crudo a invocacion directa de ops/collectors/ con templates Jinja2
- Templates ricos para: Host, Runtime, MySQL, Elastic, App, Cron
- Barras de progreso coloreadas por umbral (CPU, memoria, disco)
- Tablas de contenedores con health status
- MySQL processlist con slow queries coloreadas
- Elastic cluster health con tag verde/amarillo/rojo
- Nightly y Sentry integrados como tabs con resultado de ultima ejecucion
- Navegacion por tabs con carga lazy y cache
- URLs clickables en vez de bloques <code>
- Causas de warning/error explicadas en cada check

### Fase 4: UX y arquitectura
- Sincronizacion unificada: 3 secciones en layout horizontal (Editorial, Platform, Drift)
- Sync page: layout horizontal en 3 columnas (Editorial | Platform | Drift) con outputs independientes
- Fix de botones de sincronizacion que no funcionaban (modal de confirmacion faltante)
- Crontab rediseñado: deteccion de estado instalado/no, toggle install/uninstall, dos secciones (host + contenedor)
- Descripciones humanas para cada cron job
- Monitorizacion de crons del contenedor (cron-master) en solo lectura
- Tab Cron en diagnosticos
- UI toda en español (codigo en ingles)
- Navbar reordenado: Diagnosticos | Contenedores | Crontab | Sincronizacion | Rollover | Reportes | Historial

### Fase 5: Auditoria de codigo y documentacion
- Refactor SOLID de app.py: 679 → 184 lineas
- Modulos extraidos: report_parser.py, crontab_bp.py, rollover_bp.py, dashboard_bp.py
- Collectors movidos a diagnostics_bp.py
- Import circular eliminado
- Codigo duplicado eliminado (parsing crontab, timestamps, settings)
- Documentacion sincronizada: docs/README.md, docs/poc-local-runbook.md, AGENTS.md
- Fix de bug post-refactor: collect_non_ok_checks() sin return statement

### Fase 6: Pulido final
- Reportes: cards colapsados por defecto con contador de ficheros
- Reportes: organizacion por sub-tipo dentro de cada categoria (editorial report-only, drift, platform dry-run, etc.)
- Reportes: retencion automatica de 30 dias con limpieza al arrancar + boton manual + configurable via REPORT_RETENTION_DAYS
- Navbar: reordenado a Diagnosticos | Contenedores | Crontab | Sincronizacion | Rollover | Reportes | Historial
- Crontab: descripcion "Tarea de WordPress" para crons del contenedor
- UI: idioma unificado a español en toda la interfaz
- Reportes: agrupacion por sub-tipo basada en patrones de nombre de fichero (Nightly Auditor, Sentry por servicio, Reactive Watch, Crontab por tipo, Editorial/Platform por modo, Drift, Rollover por año)
- Reportes: 3 niveles colapsables (categoria → sub-tipo → ficheros)
- Reportes: mapeo de prefijos a nombres legibles en español (SUBTYPE_NAMES)

## Arquitectura final

### Estructura admin/
| Fichero | Responsabilidad |
|---------|----------------|
| app.py | Factory create_app(), /api/run, /api/read-file, /health |
| config.py | AdminConfig, rutas, puertos |
| runner.py | run_cli() — ejecucion de comandos CLI |
| report_parser.py | Parsing de reportes nightly/drift, helpers compartidos |
| containers.py | Blueprint /containers/ |
| diagnostics_bp.py | Blueprint /diagnostics/ + todos los collectors (host, runtime, mysql, elastic, app, cron, nightly, sentry) |
| dashboard_bp.py | Blueprint API /api/latest-nightly, /api/latest-drift |
| crontab_bp.py | Blueprint /crontab/ + API status/container-crons |
| rollover_bp.py | Blueprint /rollover/ |
| reports.py | Blueprint /reports/, API, retencion 30 dias, agrupacion por sub-tipo |
| history.py | Almacenamiento JSON del historial |
| history_bp.py | Blueprint /history/ |
| static/js/admin.js | JS compartido: auto-display reportes |
| templates/ | Jinja2: base, index, 7 paginas + 8 partials de collectors |
| retention | Limpieza automatica de reportes >30 dias (configurable) |

### Decisiones tecnicas
- Flask sin build tooling (Bulma CDN + vanilla JS)
- Plano de control fuera del plano de datos (no en Docker)
- Collectors invocados directamente como libreria Python (no subprocess)
- Sync/rollover siguen usando subprocess (necesitan docker compose exec)
- Sin autenticacion (firewall/Cloudflare en produccion)
- Historial limitado a 100 entradas FIFO

## Validacion
- 173 tests unitarios pasando
- ./scripts/check-quality.sh sin errores
- 17 endpoints verificados (todos HTTP 200)
- Todas las secciones probadas desde navegador

## Riesgos residuales
- Sin autenticacion: depende de restriccion de red
- Sin HTTPS: para produccion necesita Cloudflare o cert local
- Sin rate limiting en /api/run: un usuario podria saturar con ejecuciones concurrentes
- Bulma via CDN: en produccion servir local para no depender de terceros
- Retencion de reportes solo por mtime del fichero; si el reloj del sistema cambia, podria borrar reportes recientes

## Siguiente paso recomendado
- Configuracion de produccion: systemd unit + Gunicorn + Cloudflare Zone Lockdown
- Rate limiting en /api/run
- Servir Bulma/Font Awesome localmente
