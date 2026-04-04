# Proyecto: Lightweight Metrics

## Objetivo
Monitorizacion ligera de metricas de infraestructura (host, MySQL, Nginx, PHP-FPM) con almacenamiento en SQLite, recoleccion periodica y dashboard visual en el panel de administracion Flask con Chart.js.

## Estado: Completado

## Fases completadas

### Fase 1: Storage, collectors, endpoints y dashboard
- Capa de almacenamiento SQLite (`ops/metrics/storage.py`): create/read/prune con retention configurable
- Collector de metricas (`ops/metrics/collector.py`): recoleccion de host, MySQL, Nginx y PHP-FPM
- Endpoints Nginx stub_status y PHP-FPM status expuestos via configuracion Nginx
- Dashboard UI en Flask admin con Chart.js (CDN): graficas de CPU, memoria, disco, MySQL connections/queries, Nginx active/requests, PHP-FPM active/queue
- Scheduling via cron (cada 5 minutos)
- Bug fixes detectados por verifier: correccion de nombres de grupo, imports, y flujo de recoleccion

### Fase 2: Limpieza y correccion de coleccion
- Eliminacion de la seccion History del admin panel (redundante con metrics dashboard)
- Correccion de recoleccion Nginx: curl a stub_status via localhost en vez de acceso directo al socket
- Correccion de recoleccion PHP-FPM: curl via Nginx proxy en vez de cgi-fcgi (no disponible en Alpine)
- Validacion completa post-cambios

## Decisiones tecnicas

| Decision | Alternativas consideradas | Razon |
|----------|--------------------------|-------|
| SQLite para almacenamiento | JSON flat files, RRD | Consultas SQL nativas, sin dependencias extra, rotation facil con DELETE + VACUUM |
| Chart.js desde CDN | Plotly, D3.js, bundle local | Ligero, suficiente para graficas de series temporales, sin build tooling |
| Prefix matching para sub-grupos | Exact match, regex | Simple, extensible, consistente con el patron de nombres de metricas |
| curl via Nginx para PHP-FPM | cgi-fcgi directo | cgi-fcgi no disponible en imagen Alpine; Nginx ya expone el endpoint |

## Validacion
- 210+ tests unitarios pasando
- `./scripts/check-quality.sh` sin errores
- Dashboard verificado con datos reales de recoleccion
- Endpoints Nginx y PHP-FPM probados end-to-end

## Lecciones aprendidas
- **Nombres de grupo inconsistentes:** el verifier detecto mismatches entre los nombres de grupo usados en el collector y los esperados en el dashboard (e.g. `nginx_` vs `nginx.`). Los tests de integracion son esenciales para detectar este tipo de bugs.
- **Nginx config reload:** tras cambios en bind-mounts o ficheros de configuracion de Nginx, es necesario recargar la configuracion (`nginx -s reload`). Sin reload, los nuevos endpoints no son accesibles.
- **cgi-fcgi no disponible en Alpine:** la imagen Alpine no incluye `fcgi` tools. La solucion fue usar curl a traves de un location block en Nginx que proxea al socket PHP-FPM.
- **Verifier como red de seguridad:** delegar verificacion a un agente especializado detecto bugs que el implementador no vio. Patron recomendable para cambios complejos.

## Riesgos residuales
- **Dependencia de config Nginx:** las metricas de Nginx y PHP-FPM dependen de que la configuracion de Nginx tenga los location blocks correctos (`/nginx_status`, `/phpfpm_status`). Si la config se regenera sin ellos, la recoleccion falla silenciosamente.
- **Sin agregacion semanal:** los datos se almacenan con granularidad de 5 minutos. Sin agregacion, el volumen crece linealmente. Mitigado parcialmente por retention con prune automatico.
- **Chart.js via CDN:** en produccion deberia servirse localmente para evitar dependencia de terceros.

## Siguiente paso recomendado
- Agregacion semanal de metricas (promedios horarios/diarios para datos antiguos)
- Integracion con alerting: umbrales configurables que disparen notificacion Telegram
- Servir Chart.js localmente en produccion
