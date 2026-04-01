# Checklist de validacion POC

## Routing y salud
- [x] `GET http://nuevecuatrouno.test/healthz`
- [x] `GET http://archive.nuevecuatrouno.test/healthz`
- [x] `GET http://nuevecuatrouno.test/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/`
- [x] `GET http://nuevecuatrouno.test/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/`
- [x] `GET http://nuevecuatrouno.test/wp-admin/`
- [x] `GET http://archive.nuevecuatrouno.test/wp-admin/`
- [x] `GET http://archive.nuevecuatrouno.test/2018/10/mi-articulo/` con `302`

## Servicios
- [x] `lb-nginx` healthy
- [x] `fe-live` healthy
- [x] `fe-archive` healthy
- [x] `be-admin` healthy
- [x] `db-live` healthy
- [x] `db-archive` healthy
- [x] `elastic` healthy
- [x] `cron-master` healthy

## Observaciones
- La validacion actual se hace sobre WordPress real en `live`, `archive`, `admin-live` y `admin-archive`.
- La bateria funcional consolidada se ejecuta con `./scripts/smoke-functional.sh`.
- La busqueda ya se valida sobre Elasticsearch con indices separados por contexto y alias de lectura unificado.
