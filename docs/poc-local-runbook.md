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
./scripts/bootstrap-local-runtime.sh
docker compose up -d
```

Que hace el bootstrap:
- Genera secretos locales no versionados en `./.secrets/`
- Genera `wp-config.php` por contexto en `./runtime/wp-root/`
- Genera `wp-common.php` compartido
- Genera stubs PHP para validar routing sin core real de WordPress

## 4. Verificacion operativa
### Verificacion de routing
```sh
./scripts/smoke-routing.sh
```

### Verificacion de servicios internos
```sh
./scripts/smoke-services.sh
```

### Comprobaciones manuales utiles
```sh
curl -i http://nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/2018/10/mi-articulo/
docker compose ps
```

## 5. Resultado esperado
- Todos los contenedores en estado `healthy`
- `nuevecuatrouno.test/healthz` responde `200 ok`
- `archive.nuevecuatrouno.test/healthz` responde `200 ok`
- El frontend `archive` por anio cae en `fe-archive`
- El admin `live` cae en `be-admin` con `admin-live`
- El admin `archive` cae en `be-admin` con `admin-archive`
- El host `archive` no admin redirige a `nuevecuatrouno.test`

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
./scripts/bootstrap-local-runtime.sh
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
- El stack actual usa stubs PHP para validar routing; aun no incluye core real de WordPress.
- `xmlrpc.php`, dotfiles y ficheros sensibles comunes quedan bloqueados por Nginx en esta fase.
- La rotacion minima de logs Docker queda definida en `compose.yaml` con `max-size=10m` y `max-file=3`.
