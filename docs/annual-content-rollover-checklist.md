# Checklist del rollover anual `live -> archive`

## 1. Pre-ejecucion
- Confirmar el anio objetivo.
- Confirmar que el anio objetivo es anterior al anio en curso.
- Confirmar que `docker compose ps` no muestra contenedores degradados.
- Confirmar que `db-live`, `db-archive`, `elastic` y `cron-master` estan sanos.
- Confirmar que la busqueda unificada responde y que el alias `n9-search-posts` existe.
- Ejecutar `report-only` o `dry-run` y revisar el informe antes de continuar.
- Ejecutar `./scripts/smoke-rollover-year.sh --year <YYYY> --state pre` y confirmar que el anio objetivo sigue resolviendo desde `live`.

## 2. Validacion previa al borrado
- El numero de posts seleccionados en `live` coincide con los importados en `archive`.
- Las taxonomias necesarias existen en `archive`.
- Los posts muestreados del anio objetivo resuelven su URL canonica desde `archive`.
- Los adjuntos referenciados siguen disponibles desde `uploads`.
- El reindexado de `archive` ha finalizado correctamente.
- La busqueda unificada devuelve contenido del anio objetivo.
- Los artefactos de rollback ya estan generados y accesibles.

## 3. Borrado en `live`
- El borrado solo se ejecuta tras superar todos los puntos anteriores.
- El borrado queda registrado en el informe de ejecucion.

## 4. Validacion posterior
- El contenido del anio objetivo ya no aparece en `live`.
- El contenido del anio objetivo aparece en `archive`.
- La URL canonica publica sigue respondiendo correctamente.
- La busqueda unificada sigue devolviendo el contenido movido.
- Los smoke tests de routing y busqueda siguen pasando.
- Ejecutar `./scripts/smoke-rollover-year.sh --year <YYYY> --state post` y confirmar que el anio objetivo ya resuelve desde `archive`.

## 5. Rollback
- Si el movimiento falla antes del borrado, restaurar `archive` si fuera necesario y cerrar la ejecucion como fallida.
- Si el movimiento falla despues del borrado, reimportar el subconjunto exportado desde `live`.
- Tras cualquier rollback, reindexar ambos lados y volver a ejecutar los smoke tests.
