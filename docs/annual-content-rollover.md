# Rollover anual de contenido `live -> archive`

## Objetivo
Definir la operacion anual que mueve el anio ya cerrado desde `live` a `archive` sin romper URLs, taxonomias, medios ni busqueda.

## Modelo previsto
- `archive` contiene los anios cerrados
- `live` contiene el anio actual y el contenido nuevo
- `uploads` sigue compartido, por lo que mover contenido no implica mover medios

## Regla operativa
El rollover se ejecuta una vez al anio, al cerrar el anio natural.

Ejemplo:
- el `1 de enero de 2025` se mueve `2024` desde `live` a `archive`

## Flujo recomendado
1. ejecutar en modo `dry-run`
2. seleccionar posts, adjuntos relacionados, taxonomias y meta del anio a mover
3. exportar desde `live`
4. importar en `archive`
5. validar conteos, slugs, taxonomias y URLs
6. reindexar `archive`
7. validar busqueda unificada
8. borrar en `live` solo si la validacion anterior ha pasado
9. reindexar `live`
10. emitir informe de ejecucion

## Requisitos del script futuro
- idempotente
- con `dry-run`
- con `report-only`
- con validacion antes de borrar
- con rollback documentado
- con logs legibles para auditoria

## Riesgos a controlar
- colision de slugs
- taxonomias o meta incompletas
- borrado prematuro en `live`
- alias de busqueda sin reindexado posterior
- tiempos de ejecucion y locks sobre DB

## Rol de Elasticsearch
- `live` y `archive` siguen indexando por separado
- el alias `n9-search-posts` evita cambios en la capa de lectura
- tras mover un anio, hay que reindexar ambos lados

## Siguiente proyecto recomendado
`proyecto-rollover-anual-e-ia-ops-bootstrap.md`

Alcance recomendado:
- script de rollover anual
- validaciones automáticas y dry-run
- informe operativo
- interfaz minima para IA-Ops sobre logs, backups y salud del stack
