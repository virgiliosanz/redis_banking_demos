# Fase 2: Docker layout y docroots

## Objetivo
Cerrar el layout de contenedores, mounts, `docroot` y conectividad interna de la POC.

## Estado
- Completada

## Entregables
- Documento tecnico: `docs/docker-layout.md`
- Layout de datos en host
- Layout de contenedores
- Mounts por servicio
- Docroots efectivos por backend

## Checklist de cierre
- [x] Red Docker definida
- [x] Hostnames internos definidos
- [x] Servicios de la POC definidos
- [x] Estructura base en host definida
- [x] Bind mounts por servicio definidos
- [x] `docroot` de `live`, `archive`, `admin-live` y `admin-archive`
- [x] Permisos base de `www-data`
- [x] Directorios de logs recomendados

## Decisiones tomadas
- Se mantiene una sola red Docker `bridge` para la POC.
- `LB-Nginx` monta el contenido en solo lectura.
- `fe-live`, `fe-archive` y `be-admin` son contenedores separados.
- `be-admin` usa dos `docroot`.
- Se aceptan bind mounts directos en host por simplicidad.

## Riesgos pendientes
- El layout aun no define secretos ni variables de entorno.
- La comparticion de `uploads` y `mu-plugins` queda como opcion, no como decision cerrada.
- Sigue faltando traducir esto a `docker compose`.

## Siguiente fase
- `Fase 3`: configuracion WordPress por contexto.
