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
- 237 tests unitarios pasando (173 originales + 64 nuevos)
- `./scripts/check-quality.sh` sin errores
- Dashboard verificado con datos reales de recoleccion (86 muestras por collect)
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
- **Chart.js via CDN:** en produccion deberia servirse localmente para evitar dependencia de terceros.

## Siguiente paso recomendado
- Considerar retencion mas larga para agregados horarios (30d?)
- Mejoras UX en dashboard (sparklines, highlighting de anomalias)
- Integracion con Sentry Agent para deteccion de anomalias basada en metricas
