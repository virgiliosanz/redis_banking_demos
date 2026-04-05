# Proyecto: Lightweight Metrics

## Objetivo
Monitorizacion ligera de metricas de infraestructura (host, MySQL, Nginx, PHP-FPM, red) con almacenamiento en SQLite, recoleccion periodica, agregacion semanal, limpieza automatizada y dashboard visual en el panel de administracion Flask con Chart.js.

## Estado: Completado

## Fases completadas

### Fase 1: Core metrics infrastructure
- Capa de almacenamiento SQLite (`ops/metrics/storage.py`): create/read/prune con retention configurable
- Modulo collector de metricas (`ops/collectors/metrics.py`) con 6 sub-collectors (host, MySQL, Nginx, PHP-FPM, disco, memoria)
- Endpoints Nginx `stub_status` y PHP-FPM status expuestos via configuracion Nginx
- Dashboard UI (`admin/metrics_bp.py` + Chart.js CDN) con selector de rango 1h/6h/24h
- Scheduling via cron: `collect-metrics` cada 1 minuto
- Verifier detecto 4 bugs de integracion: mismatch de nombres de grupo, prefix matching necesario, URL de Nginx incorrecta — todos corregidos

### Fase 2: Cleanup y fixes
- Eliminacion de la seccion history/historial del admin panel (single-user, no necesaria)
- Correccion del collector PHP-FPM: curl via Nginx en vez de `cgi-fcgi` (no disponible en Alpine)
- Creacion del fichero de documentacion inicial del proyecto

### Fase 3: Weekly aggregation, network metrics, automated cleanup
- **Agregacion semanal:** tabla `samples_hourly`, metodo `aggregate()`, `query_extended()`, rango 7d en dashboard
- **Metricas de red:** host (`psutil net_io_counters`) + contenedores (`docker stats NetIO`), almacenadas como contadores cumulativos raw; el dashboard calcula rates (bytes/s)
- **Cron de limpieza automatizada:** comando CLI `cleanup-data` ejecutado diariamente a las 03:00, agrega metricas antiguas + purga muestras + limpia reports antiguos

### Fase 4: PHP-FPM status fix
- Correccion del include path en Dockerfile PHP-FPM: añadido `include=etc/php-fpm.d/zzz-project/*.conf` para que `pm.status_path` se cargue correctamente
- Rebuild de imagen PHP, verificacion de que los 3 pools FPM devuelven status JSON via proxy Nginx
- Recoleccion final `collect-metrics`: 86 metricas por muestra en todos los grupos

### Fase 5: Dashboard UX improvements
- Reorganizacion del dashboard con tabs por grupo de metricas y filtros por servicio
- Reordenacion de la navegacion del admin panel
- Correccion de rangos temporales, filtro de servicios y titulos de graficas

### Fase 6: Dashboard fix + cron monitoring
- Correccion del filtro "Servicios" y seleccion por defecto en el dashboard
- Heartbeats y monitoring para los crons de metricas y limpieza

### Fase 7: Admin panel improvements
- Health landing page reemplazando el index anterior
- Bandas de umbrales de anomalia en graficas de metricas
- Marcadores de incidentes en graficas
- Overlay comparativo temporal (hoy vs ayer)
- Vista de capacity planning
- Toggle de dark mode
- Humanizacion de timestamps de cron en diagnosticos

### Fase 8: Home dashboard fixes + timestamp humanization
- Correccion del layout del home dashboard y problemas de datos
- Humanizacion de todos los timestamps y correccion del toggle de umbrales
- Correccion de items rojos en la tab App de diagnosticos
- Compactacion del layout home + humanizacion de cron + eliminacion del boton de cleanup

### Fase 9: WordPress metrics
- Script PHP interno para recoleccion de metricas WordPress (`scripts/internal/wp-metrics.php`)
- Sub-collector WordPress integrado en el collector de metricas
- Metricas: posts, users, comments, plugins, transients, autoload size, DB size por instancia

### Fase 10: WordPress diagnostics and home indicators
- Tab de diagnosticos WordPress en el admin panel
- Indicadores de salud WordPress en el home dashboard

### Fase 11: Cleanup redundant scripts
- Eliminacion de scripts wrapper redundantes; toda la operacion via CLI `ia_ops.py`

### Fase 12: UI bug fixes + graceful degradation + bootstrap docs
- Correccion de bugs UI: boton de comparacion y timeout de crontab
- Degradacion graceful para servicios no disponibles (metricas parciales sin error)
- Documentacion del procedimiento de bootstrap completo del stack

### Fase 13: WordPress diagnostics fixes + metric reclassification
- Correccion de la salida de diagnosticos persistiendo entre cambios de tab
- Correccion de la tab de diagnosticos WordPress mostrando vacia (mismatch de formato entre collector y template)
- Reclasificacion de actualizaciones WP (plugins, themes, language, core) y eventos cron de metricas SQLite a solo diagnosticos
- Añadidas actualizaciones de idioma y check de version core a `wp-metrics.php` y UI de diagnosticos

### Fase 14: Frontend refactoring + UI fixes
- Extraccion de JS compartido a 3 modulos: `timeago.js` (extendido), `admin-utils.js`, `chart-helpers.js`
- Consolidacion de CSS custom en `admin/static/css/admin.css`, reemplazo de estilos inline por utilidades Bulma
- Split del filtro Servicios por instancias individuales (PHP-FPM ×3, MySQL ×2, WordPress ×2)
- Movidas todas las dependencias CDN (Chart.js, annotation plugin, Bulma, Font Awesome) a `admin/static/vendor/`
- Correccion de la UI de ejecucion de crontab no reseteando tras completar (bug de NodeList DOM stale)
- Eliminado campo "Proyecto" vacio de diagnosticos de host
- Simplificada seccion de cron jobs en home dashboard (resumen OK/warning)


## Bootstrap procedure

Procedimiento completo para levantar el stack con metricas operativas.

### Prerrequisitos
- Docker y Docker Compose instalados
- Python 3.x con `psutil` disponible en el host
- Repositorio clonado con `.env` configurado (ver `config/`)

### Pasos

**1. Levantar los contenedores:**
```bash
docker compose up -d
```

**2. Esperar a que todos los servicios esten healthy:**
```bash
docker compose ps
```
Verificar que todos los contenedores muestran estado `healthy` antes de continuar.

**3. Bootstrap de WordPress (instalacion, contenido, ElasticPress):**
```bash
./scripts/bootstrap-local-stack.sh
```
Este script instala WordPress en todas las instancias, crea contenido de prueba y configura ElasticPress con los indices de Elasticsearch.

**4. Primera recoleccion de metricas:**
```bash
python3 -m ops.cli.ia_ops collect-metrics
```
Genera una muestra en `runtime/metrics/metrics.db`. Con el stack completo se recolectan **104 metricas** por ejecucion (host, containers, nginx, phpfpm×3, mysql×2, elastic, wordpress×2). Sin WordPress disponible, se recolectan las metricas de infraestructura.

**5. Instalar cron de recoleccion periodica (cada minuto):**
```bash
python3 -m ops.cli.ia_ops install-metrics-crontab
```

**6. Instalar cron de limpieza automatizada (diario a las 03:00):**
```bash
python3 -m ops.cli.ia_ops install-cleanup-crontab
```
Agrega metricas antiguas a tablas horarias, purga muestras con mas de 24h y limpia reports antiguos.

**7. Arrancar el panel de administracion:**
```bash
ADMIN_PORT=9941 python3 -m admin.app
```
Accesible en `http://localhost:9941`. Incluye:
- **Home:** dashboard de salud con indicadores de todos los servicios
- **Metricas:** graficas Chart.js con rangos 1h/6h/24h/7d, filtros por servicio, umbrales de anomalia, overlay comparativo y capacity planning
- **Diagnosticos:** tabs por servicio incluyendo WordPress
- **API JSON:** `http://localhost:9941/api/metrics?range=1h&group=host`

### Verificacion rapida
```bash
# Comprobar que la DB de metricas existe y tiene datos
python3 -m ops.cli.ia_ops collect-metrics
# Comprobar el dashboard
curl -s http://localhost:9941/api/metrics?range=1h | python3 -m json.tool | head -20
```


## Decisiones tecnicas

| Decision | Alternativas consideradas | Razon |
|----------|--------------------------|-------|
| SQLite para almacenamiento | JSON flat files, RRD, InfluxDB | stdlib, sin dependencias extra, consultas SQL nativas |
| Chart.js desde CDN | Plotly, D3.js, bundle local | Ligero, consistente con Bulma CDN en admin panel |
| Prefix matching en `storage.query()` | Exact match, regex | Simple, extensible para sub-grupos (`mysql.db-live`, `phpfpm.fe-live`) |
| curl via Nginx para PHP-FPM | cgi-fcgi directo | `cgi-fcgi` no disponible en imagen Alpine; Nginx ya expone el endpoint |
| Contadores cumulativos raw para red | Rates pre-calculados | Mas flexible, calculo de rates en dashboard JS |
| Cron unico de limpieza | Crons separados para metricas y reports | Simplifica scheduling, un solo punto de entrada |

## Validacion
- 302 tests unitarios pasando (173 originales + 129 nuevos)
- `./scripts/check-quality.sh` sin errores
- 104 metricas por recoleccion (host, containers, nginx, phpfpm×3, mysql×2, elastic, wordpress×2)
- 0 dependencias CDN (todo servido desde `admin/static/vendor/`)
- 38 tareas completadas en 14 fases
- Dashboard verificado con datos reales de recoleccion
- Endpoints Nginx y PHP-FPM probados end-to-end con los 3 pools FPM
- Agregacion semanal y limpieza automatizada verificadas

## Lecciones aprendidas
- **Bugs de integracion detectados por verifier:** mismatches de nombres de grupo entre collector y API. Los tests de integracion son esenciales para detectar este tipo de inconsistencias.
- **Nginx bind-mount config:** requiere recreacion del contenedor para tomar efecto, no basta con reload.
- **cgi-fcgi no disponible en Alpine:** la imagen Alpine no incluye `fcgi` tools. Solucion: curl a traves de location block en Nginx que proxea al socket PHP-FPM.
- **PHP-FPM include path:** PHP-FPM solo escanea el directorio raiz configurado, no subdirectorios. Necesita `include` explicito para directorios de config montados.
- **Verifier como red de seguridad:** delegar verificacion a un agente especializado detecto bugs que el implementador no vio. Patron recomendable para cambios complejos.

## Riesgos residuales
- **Sin alerting basado en metricas:** el watch reactivo existente cubre alertas por separado, pero no hay umbrales configurables sobre las metricas recolectadas.
- **Retencion de agregacion semanal:** por defecto 7d, sin tuning adicional aun.
- **Dependencia de config Nginx:** las metricas de Nginx y PHP-FPM dependen de los location blocks correctos. Si la config se regenera sin ellos, la recoleccion falla silenciosamente.

## Siguiente paso recomendado
- Considerar retencion mas larga para agregados horarios (30d?)
- Integracion con Sentry Agent para deteccion de anomalias basada en metricas
- Alerting configurable basado en umbrales de metricas
