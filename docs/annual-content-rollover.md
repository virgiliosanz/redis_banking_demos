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

## Contrato funcional inicial

### Alcance de datos que si se mueven
- posts del tipo `post` cuyo `post_date` pertenezca al anio cerrado objetivo
- estado inicial soportado: `publish`
- meta de post necesaria para render, taxonomias y busqueda
- relaciones con categorias y tags utilizadas por los posts movidos
- referencia a imagen destacada y adjuntos de imagen realmente usados por esos posts

### Alcance de datos que no se mueven en la primera implementacion
- `page`
- borradores, revisiones, autosaves o papelera
- usuarios, roles o credenciales
- comentarios
- menus, widgets y opciones globales
- configuracion de plugins o tablas auxiliares no ligadas directamente a los posts movidos

### Clave de seleccion
- el anio objetivo se determina por `post_date`
- solo se seleccionan posts cuya fecha este entre `YYYY-01-01 00:00:00` y `YYYY-12-31 23:59:59`
- el anio a mover debe ser siempre anterior al anio natural en curso

### Invariantes que deben seguir siendo ciertas tras el movimiento
- la URL canonica publica del post no cambia
- `uploads` no se mueve ni se duplica; sigue compartido
- la lectura de busqueda sigue yendo al alias `n9-search-posts`
- el contenido movido deja de existir en `live` solo cuando la validacion en `archive` ya ha pasado
- el corte anual del balanceador debe avanzar de forma coherente con el anio movido

## Modos de ejecucion

### `dry-run`
- no modifica `live` ni `archive`
- calcula seleccion, conteos, slugs potencialmente conflictivos y artefactos que se generarian
- debe producir informe persistente

### `report-only`
- no mueve ni borra datos
- inspecciona el estado actual para un anio objetivo y devuelve informe operativo
- sirve para prechequeo o auditoria

### `execute`
- exporta, importa, valida, reindexa y solo borra en `live` si toda la validacion ha pasado
- debe dejar informe y artefactos de rollback

## Flujo recomendado
1. ejecutar en modo `dry-run`
2. validar que el anio objetivo es cerrado y elegible
3. validar que el anio objetivo coincide con el `LIVE_MIN_YEAR` vigente en el corte anual del balanceador
4. exportar un snapshot logico del subconjunto objetivo de `live`
5. exportar un snapshot logico previo de `archive` para rollback local
6. seleccionar posts, meta, taxonomias y adjuntos relacionados del anio a mover
7. importar en `archive`
8. validar conteos, slugs, taxonomias, adjuntos y URLs
9. reindexar `archive`
10. validar busqueda unificada contra `n9-search-posts`
11. avanzar el corte anual de routing y recargar el balanceador
12. borrar en `live` solo si las validaciones anteriores han pasado
13. reindexar `live`
14. emitir informe de ejecucion y artefactos de rollback

## Requisitos del script futuro
- idempotente
- con `dry-run`
- con `report-only`
- con validacion antes de borrar
- con rollback documentado
- con logs legibles para auditoria
- ejecutable via `cron-master` o wrapper equivalente
- parametrizable por anio objetivo
- con salida estructurada reutilizable por IA-Ops

## Validaciones obligatorias antes de borrar en `live`
- el anio objetivo no es el anio en curso
- el anio objetivo coincide con el `LIVE_MIN_YEAR` configurado en el corte anual del balanceador
- los posts seleccionados en `live` coinciden con los importados en `archive`
- no hay colisiones de slug o, si existen, quedan resueltas y documentadas
- las categorias y tags usadas por los posts movidos existen en `archive`
- los posts movidos resuelven su URL canonica en `archive`
- los adjuntos referenciados por los posts movidos siguen disponibles desde `uploads`
- el indice de `archive` se ha actualizado correctamente
- la busqueda unificada devuelve contenido del anio movido tras el reindexado
- los smoke tests de routing y busqueda siguen pasando

## Formato minimo del informe de ejecucion
- `target_year`
- `mode`
- `started_at`
- `finished_at`
- `selected_posts`
- `selected_terms`
- `selected_attachments`
- `imported_posts`
- `validation_status`
- `deleted_from_live`
- `archive_reindex_status`
- `live_reindex_status`
- `rollback_artifacts`
- `warnings`
- `errors`

## Rollback operativo

### Regla base
- no se borra nada en `live` hasta haber generado artefactos suficientes para reconstruir el estado anterior

### Artefactos minimos de rollback
- export logico del subconjunto del anio objetivo desde `live`
- export logico previo del estado de `archive` afectado por la importacion
- snapshot del corte anual de routing antes del cambio
- informe de ejecucion con conteos y IDs afectados

### Estrategia de rollback
1. si falla antes del borrado en `live`, se limpia el contenido importado en `archive`, se restaura `archive` desde su export previo si hiciera falta y se revierte el corte anual del balanceador
2. si falla despues del borrado en `live`, se reimporta el subconjunto exportado desde `live`, se revierte el corte anual del balanceador y se reindexan ambos lados
3. tras el rollback, se vuelven a ejecutar los smoke tests de routing y busqueda

## Checklist operativa
- La checklist previa y posterior de la operacion vive en `docs/annual-content-rollover-checklist.md`.

## Rol de Elasticsearch
- `live` y `archive` siguen indexando por separado
- el alias `n9-search-posts` evita cambios en la capa de lectura
- tras mover un anio, hay que reindexar ambos lados

## Siguiente proyecto recomendado
`proyecto-rollover-anual-e-ia-ops-bootstrap.md`

Alcance recomendado:
- script de rollover anual
- validaciones automaticas, `dry-run` y `report-only`
- informe operativo
- interfaz minima para IA-Ops sobre logs, checks, cron y salud del stack
