# Runbook local de la POC

## 1. Objetivo
Describir el flujo minimo para levantar, verificar y revertir la POC WordPress Docker en local sin depender de conocimiento implicito.

Este runbook sustituye a checklists operativas separadas de validacion local.

## 2. Prerrequisitos
- Docker Desktop o daemon Docker operativo
- Entradas en `/etc/hosts`

```txt
127.0.0.1 nuevecuatrouno.test
127.0.0.1 archive.nuevecuatrouno.test
```

## 3. Bootstrap inicial
Desde la raiz del repositorio:

```sh
./scripts/bootstrap-local-stack.sh
```

Que hace el bootstrap:
- Genera secretos locales no versionados en `./.secrets/`
- Prepara layout compartido y aislado en `./runtime/wp-root/`
- Genera `wp-config.php` por contexto y `wp-common.php` compartido
- Levanta el stack Docker
- Instala WordPress real en `live` y `archive`
- Siembra contenido inicial reproducible para `live` y `archive`
- Activa e indexa ElasticPress con alias de lectura unificado

## 4. Verificacion operativa
### Verificacion funcional completa
```sh
./scripts/smoke-functional.sh
```

### Verificacion IA-Ops minima
```sh
./scripts/collect-nightly-context.sh --write-report
./scripts/run-nightly-auditor.sh
./scripts/run-sentry-agent.sh --service lb-nginx
./scripts/run-sentry-agent.sh --service elastic
```

### URLs principales
- Front `live`: `http://nuevecuatrouno.test/`
- Front `archive` por host admin: `http://archive.nuevecuatrouno.test/`
- Admin `live`: `http://nuevecuatrouno.test/wp-admin/`
- Admin `archive`: `http://archive.nuevecuatrouno.test/wp-admin/`
- Login `live`: `http://nuevecuatrouno.test/wp-login.php`
- Login `archive`: `http://archive.nuevecuatrouno.test/wp-login.php`

### Accesos administrativos de laboratorio
- `live`: `http://nuevecuatrouno.test/wp-admin/`
- `archive`: `http://archive.nuevecuatrouno.test/wp-admin/`
- Usuario `live`: `n9liveadmin`
- Password `live`: `cat ./.secrets/wp-live-admin-password`
- Usuario `archive`: `n9archiveadmin`
- Password `archive`: `cat ./.secrets/wp-archive-admin-password`

### Comprobaciones manuales utiles
```sh
curl -i http://nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/2018/10/mi-articulo/
curl -i "http://nuevecuatrouno.test/?s=rioja-laboratorio"
curl -I http://nuevecuatrouno.test/wp-admin/
curl -I http://archive.nuevecuatrouno.test/wp-admin/
curl -sD - "http://nuevecuatrouno.test/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/" -o /dev/null | grep X-Origin-Cache-Policy
curl -sD - "http://nuevecuatrouno.test/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/" -o /dev/null | grep X-Origin-Cache-Policy
docker compose ps
```

### Contenido inicial actual
- Contrato de URL para posts: `/%year%/%monthnum%/%day%/%postname%/`
- `archive`: `http://nuevecuatrouno.test/2015/02/03/logrono-revive-la-noche-de-san-mateo-en-su-casco-antiguo/`
- `archive`: `http://nuevecuatrouno.test/2016/07/14/el-ebro-marca-un-verano-de-contrastes-en-logrono/`
- `archive`: `http://nuevecuatrouno.test/2017/09/09/las-cuadrillas-vuelven-a-llenar-de-musica-las-calles-del-centro/`
- `archive`: `http://nuevecuatrouno.test/2018/10/21/la-vendimia-abre-una-nueva-etapa-para-el-rioja-metropolitano/`
- `archive`: `http://nuevecuatrouno.test/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/`
- `archive`: `http://nuevecuatrouno.test/2020/08/27/el-comercio-local-resiste-un-verano-marcado-por-la-incertidumbre/`
- `archive`: `http://nuevecuatrouno.test/2021/06/07/la-agenda-cultural-recupera-el-pulso-con-una-temporada-expandida/`
- `archive`: `http://nuevecuatrouno.test/2022/11/18/la-redonda-cierra-un-ano-de-reformas-con-mas-actividad-vecinal/`
- `archive`: `http://nuevecuatrouno.test/2023/12/29/el-archivo-municipal-consolida-2023-como-ano-de-transicion-digital/`
- `live`: `http://nuevecuatrouno.test/2024/04/11/logrono-impulsa-2024-con-nuevas-rutas-peatonales-y-comercio-abierto/`
- `live`: `http://nuevecuatrouno.test/2025/09/19/la-programacion-cultural-de-2025-lleva-el-teatro-a-todos-los-barrios/`
- `live`: `http://nuevecuatrouno.test/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/`

### Busquedas manuales de referencia
- El frontend publico ya muestra un buscador visible en cabecera.
- `http://nuevecuatrouno.test/?s=Cristo+del+Santo+Sepulcro`
- `http://nuevecuatrouno.test/?s=rioja+metropolitano`
- `http://nuevecuatrouno.test/?s=rioja-laboratorio`

### Comprobaciones manuales recomendadas
- `GET /healthz` en ambos hosts devuelve `200`.
- `GET /2015/02/03/...` cae en `archive`.
- `GET /2024/04/11/...` cae en `live`.
- `GET /wp-admin/` redirige a `wp-login.php` sin loop.
- `GET /wp-login.php` responde `200`.
- `GET /?s=rioja-laboratorio` devuelve resultados mixtos con permalinks canonicos.

## 5. Resultado esperado
- Todos los contenedores en estado `healthy`
- `nuevecuatrouno.test/healthz` responde `200 ok`
- `archive.nuevecuatrouno.test/healthz` responde `200 ok`
- Los posts `2015-2023` caen en `fe-archive`
- Los posts `2024+` caen en `fe-live`
- El admin `live` cae en `be-admin` con `admin-live`
- El admin `archive` cae en `be-admin` con `admin-archive`
- `wp-admin/` redirige a `wp-login.php` sin loop de `302`
- El host `archive` no admin redirige a `nuevecuatrouno.test`
- La busqueda en `live` encuentra contenido de `live` y `archive` con enlaces canonicos, no `?p=<id>`
- `uploads` se comparte y la cache queda aislada por contexto

## 6. Rollback local
### Rollback de configuracion del repo
Volver al ultimo commit estable deseado y recrear el stack:

```sh
git switch <rama-o-commit-estable>
docker compose up -d --force-recreate
```

### Rollback de runtime local
Si solo quieres regenerar artefactos locales sin cambiar Git:

```sh
./scripts/bootstrap-local-stack.sh
docker compose up -d --force-recreate
```

### Reinicio limpio del stack
```sh
docker compose down
docker compose up -d
```

## 7. Notas operativas
- `./.secrets/` no se versiona y es solo para esta POC local.
- `./runtime/` es descartable y se puede regenerar con los scripts de bootstrap.
- El stack actual usa WordPress real, no stubs PHP.
- La semilla de laboratorio puede regenerarse sin reinstalar WordPress con `./scripts/bootstrap-wordpress-seed.sh`.
- ElasticPress indexa `live` y `archive` por separado y consulta mediante el alias `n9-search-posts`.
- Los heartbeats de jobs criticos viven en `./runtime/heartbeats/`.
- Los informes JSON y Markdown de IA-Ops viven en `./runtime/reports/ia-ops/`.
- `xmlrpc.php`, dotfiles y ficheros sensibles comunes quedan bloqueados por Nginx en esta fase.
- La rotacion minima de logs Docker queda definida en `compose.yaml` con `max-size=10m` y `max-file=3`.
- Este runbook centraliza la operacion manual de la POC; ya no hace falta una referencia rapida separada.
