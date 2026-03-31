# Fase 3: Configuracion WordPress por contexto

## Objetivo
Cerrar la configuracion WordPress de `live`, `archive`, `admin-live` y `admin-archive`.

## Estado
- Completada

## Entregables
- Documento tecnico: `docs/wordpress-contexts.md`
- Modelo de `wp-config.php` por contexto
- Variables de entorno recomendadas
- Regla de carga de `BE-Admin`
- Degradacion documentada de `Elastic`

## Checklist de cierre
- [x] `live` definido contra `db-live`
- [x] `archive` definido contra `db-archive`
- [x] `admin-live` definido contra `db-live`
- [x] `admin-archive` definido contra `db-archive`
- [x] Sin logica de particion anual dentro de WordPress
- [x] `BE-Admin` resuelto por `docroot`
- [x] Secretos fuera del repositorio
- [x] `cron-master` con `path` explicito por contexto

## Decisiones tomadas
- Cada contexto tiene su propio `wp-config.php`.
- Se admite un fichero comun compartido para ajustes no contextuales.
- `BE-Admin` no hace autodeteccion del sitio.
- `Elastic` no puede romper el bootstrap completo del sitio.

## Riesgos pendientes
- Aun no existe plantilla real de `docker compose` para inyectar estas variables.
- Falta decidir si `uploads` sera compartido o separado entre contextos.
- Faltan secretos y valores reales de despliegue.

## Siguiente fase
- `Fase 4`: observabilidad y operacion.
