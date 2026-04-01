# Runbook local de la POC

## 1. Objetivo
Describir el flujo minimo para levantar, verificar y revertir la POC WordPress Docker en local sin depender de conocimiento implicito.

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

### Referencia rapida
- Ver tambien `docs/poc-manual-testing-reference.md` para URLs, credenciales y comprobaciones manuales.

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
curl -i "http://nuevecuatrouno.test/?s=Archive+sample+page"
curl -I http://nuevecuatrouno.test/wp-admin/
curl -I http://archive.nuevecuatrouno.test/wp-admin/
docker compose ps
```

### Contenido inicial actual
- `live`: pagina jerarquica en `http://nuevecuatrouno.test/actualidad/post/`
- `live`: `http://nuevecuatrouno.test/cultura/agenda-local/`
- `live`: `http://nuevecuatrouno.test/servicios/contacto-redaccion/`
- `archive`: `http://nuevecuatrouno.test/2019/05/noticia/`
- `archive`: `http://nuevecuatrouno.test/2018/10/memoria-2018/`
- `archive`: `http://nuevecuatrouno.test/2021/06/archivo-cultural-2021/`
- Busqueda manual de referencia:
  - `http://nuevecuatrouno.test/?s=Agenda+local+laboratorio`
  - `http://nuevecuatrouno.test/?s=Memoria+hemeroteca+2018`
  - `http://nuevecuatrouno.test/?s=rioja-laboratorio`

## 5. Resultado esperado
- Todos los contenedores en estado `healthy`
- `nuevecuatrouno.test/healthz` responde `200 ok`
- `archive.nuevecuatrouno.test/healthz` responde `200 ok`
- El frontend `archive` por anio cae en `fe-archive`
- El admin `live` cae en `be-admin` con `admin-live`
- El admin `archive` cae en `be-admin` con `admin-archive`
- `wp-admin/` redirige a `wp-login.php` sin loop de `302`
- El host `archive` no admin redirige a `nuevecuatrouno.test`
- La busqueda en `live` encuentra contenido de `live` y `archive`
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
- `xmlrpc.php`, dotfiles y ficheros sensibles comunes quedan bloqueados por Nginx en esta fase.
- La rotacion minima de logs Docker queda definida en `compose.yaml` con `max-size=10m` y `max-file=3`.
