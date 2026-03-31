# Fase 4: Observabilidad y operacion

## Objetivo
Cerrar el minimo operativo de healthchecks, logs, alertas y smoke tests de la POC.

## Estado
- Completada

## Entregables
- Documento tecnico: `docs/observability-and-operations.md`
- Healthchecks por contenedor
- Logs minimos por servicio
- Umbrales `warning` y `critical`
- Pruebas de humo minimas

## Checklist de cierre
- [x] `LB-Nginx` con `/healthz`
- [x] `php-fpm` con `ping` y `status`
- [x] MySQL con `mysqladmin ping`
- [x] Elastic con `_cluster/health`
- [x] `cron-master` con criterio de ultima ejecucion
- [x] logs minimos por servicio
- [x] bateria minima de smoke tests

## Decisiones tomadas
- La POC no monta una plataforma completa de observabilidad.
- Se priorizan checks que detecten rapido caidas y routing roto.
- Elastic puede degradar funciones, pero no deberia tumbar el sitio entero.
- Los reinicios automaticos no sustituyen al diagnostico.

## Riesgos pendientes
- Aun no existe implementacion real de healthchecks en `docker compose`.
- Aun no existe formato final de logs ni rotacion configurada.
- Faltan scripts reales de smoke tests.

## Siguiente fase
- `Fase 5`: criterios de paso a produccion.
