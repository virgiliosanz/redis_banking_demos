# Busqueda unificada live + archive

## Objetivo
Permitir que la busqueda publica consulte contenido de `live` y `archive` sin mezclar sus ciclos de vida operativos.

## Decision de arquitectura
No se usa un unico indice fisico compartido.

Se usa:
- un indice de escritura para `live`
- un indice de escritura para `archive`
- un alias de lectura unificado para las queries de busqueda

## Esquema
- prefijo de indices `live`: `n9-live`
- prefijo de indices `archive`: `n9-archive`
- alias de lectura: `n9-search-posts`

ElasticPress indexa por separado:
- `live` -> indices con prefijo `n9-live`
- `archive` -> indices con prefijo `n9-archive`

La busqueda publica usa el alias:
- `n9-search-posts`

## Por que no un indice fisico unico
- `live` cambia a diario; `archive` no
- reindexar `archive` no debe tocar `live`
- el rollover anual de contenido debe ser limpio
- separar indices simplifica validacion, reconstruccion y rollback

## Como se implementa en la POC
- ElasticPress se instala y activa en `live` y `archive`
- `be-admin` comparte el mismo docroot y recibe el plugin via el core unico
- un mu-plugin compartido redirige las busquedas al alias `n9-search-posts`
- el alias se publica sobre los dos indices reales

## Flujo de indexacion
1. `live` sincroniza su indice con prefijo propio
2. `archive` sincroniza su indice con prefijo propio
3. Elasticsearch publica el alias `n9-search-posts` sobre ambos
4. las queries de busqueda desde WordPress leen del alias

## Rollover anual previsto
Modelo esperado:
- `archive`: 2015-2023
- `live`: 2024 en adelante

Operacion anual prevista:
1. seleccionar en `live` el año ya cerrado
2. exportar contenido, taxonomias y meta
3. importar en `archive`
4. validar conteos y URLs
5. reindexar `archive`
6. borrar en `live` solo despues de validar
7. reindexar `live`

## Degradacion
- si Elasticsearch falla, la busqueda no debe derribar el sitio entero
- el comportamiento esperado es degradar a WordPress/MySQL o perder busqueda avanzada, pero no romper front ni admin

## Rollback
1. desactivar la redireccion de busqueda al alias
2. mantener indices separados sin alias
3. si hace falta, volver a buscar solo contra el indice de `live`
4. revalidar con `smoke-routing`, `smoke-services` y `smoke-search`
