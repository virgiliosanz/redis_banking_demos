# Fase 1: LB-Nginx y routing

## Objetivo
Cerrar la configuracion concreta del balanceador para la POC.

## Estado
- Completada

## Entregables
- Documento tecnico: `docs/lb-nginx-routing.md`
- Matriz de routing definida
- Prioridad de reglas definida
- Contrato FastCGI traducido a configuracion concreta

## Checklist de cierre
- [x] Upstreams `fe_live`, `fe_archive` y `be_admin`
- [x] Routing por dominio `archive`
- [x] Routing por path anual `2015-2023`
- [x] Routing administrativo a `BE-Admin`
- [x] Seleccion de `docroot` por contexto
- [x] Variables FastCGI obligatorias
- [x] Casos minimos de prueba definidos

## Decisiones tomadas
- Toda la logica de particion vive en `LB-Nginx`.
- `BE-Admin` es pasivo y depende del `docroot` recibido.
- En la POC todo `/wp-json/` se considera administrativo para no romper el admin de WordPress.
- `LB-Nginx` debe ver el mismo arbol de contenido para servir estaticos y resolver scripts.

## Riesgos pendientes
- La regla global de `/wp-json/` puede ser demasiado amplia si luego hay REST publico intensivo.
- El diseno sigue dependiendo de bind mounts compartidos.
- Aun no esta traducido a `docker compose` ni a archivos de despliegue reales.

## Siguiente fase
- `Fase 2`: layout de contenedores y `docroot`.
