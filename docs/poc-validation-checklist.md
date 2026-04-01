# Checklist de validacion POC

## Routing y salud
- [x] `GET http://nuevecuatrouno.test/healthz`
- [x] `GET http://archive.nuevecuatrouno.test/healthz`
- [x] `GET http://nuevecuatrouno.test/actualidad/post/`
- [x] `GET http://nuevecuatrouno.test/2019/05/noticia/`
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
