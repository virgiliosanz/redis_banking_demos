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
./scripts/check-quality.sh
```

### Verificacion IA-Ops minima
```sh
python3 -m ops.cli.ia_ops collect-nightly-context --write-report
python3 -m ops.cli.ia_ops collect-mysql-health
./scripts/run-nightly-auditor.sh
./scripts/run-sentry-agent.sh --service lb-nginx
./scripts/run-sentry-agent.sh --service elastic
./scripts/run-sentry-agent.sh --service db-live --no-notify-telegram
```

### Canal Telegram
Configurar en `config/ia-ops-sources.env` local o via entorno:

```sh
TELEGRAM_NOTIFY_ENABLED=1
TELEGRAM_NOTIFY_ON_NIGHTLY=1
TELEGRAM_NOTIFY_ON_SENTRY=1
TELEGRAM_BOT_TOKEN=<token-del-bot>
TELEGRAM_CHAT_ID=<chat-id>
TELEGRAM_MESSAGE_THREAD_ID=
```

Vista previa sin enviar nada:

```sh
python3 -m ops.cli.ia_ops send-telegram-test --preview --message "IA-Ops Telegram test"
./scripts/run-nightly-auditor.sh --telegram-preview --no-write-report
./scripts/run-sentry-agent.sh --service lb-nginx --telegram-preview --no-write-report
```

Notas de semantica:
- `--no-write-report` ya no dispara notificaciones por defecto
- `--notify-telegram` fuerza el envio aunque no se escriba informe
- `--no-notify-telegram` silencia Telegram aunque la configuracion local lo tenga activado

Envio real:

```sh
python3 -m ops.cli.ia_ops send-telegram-test --message "IA-Ops Telegram test"
./scripts/run-nightly-auditor.sh --notify-telegram
./scripts/run-sentry-agent.sh --service elastic --notify-telegram
./scripts/run-nightly-auditor.sh --no-notify-telegram
```

Notas:
- el canal recibe un resumen corto, no el informe completo
- los informes completos siguen quedando en `runtime/reports/ia-ops/`
- el token del bot no debe guardarse en el repositorio

### Programacion gestionada con cron
Previsualizar el bloque gestionado:

```sh
./scripts/install-nightly-auditor-cron.sh --print
./scripts/install-reactive-watch-cron.sh --print
./scripts/install-sync-jobs-cron.sh --print
```

Instalarlo en el `crontab` del usuario actual:

```sh
./scripts/install-nightly-auditor-cron.sh
./scripts/install-reactive-watch-cron.sh
./scripts/install-sync-jobs-cron.sh
```

Eliminar solo el bloque gestionado del proyecto:

```sh
./scripts/install-nightly-auditor-cron.sh --remove
./scripts/install-reactive-watch-cron.sh --remove
./scripts/install-sync-jobs-cron.sh --remove
```

Notas:
- en este host de laboratorio no se deja ningun bloque gestionado instalado por defecto; aqui se usa sobre todo `--print` y ejecucion manual
- en preproduccion y produccion si deberian instalarse los bloques gestionados de `nightly`, `reactive` y `sync`, porque forman parte del baseline operativo esperado
- el `Nightly Auditor` queda programado a las `05:15` hora local del host
- el bloque se instala con marcador gestionado `NUEVECUATROUNO_IA_OPS_NIGHTLY`
- el flujo reactivo se programa cada `5` minutos con el bloque `NUEVECUATROUNO_IA_OPS_REACTIVE`
- la sync editorial queda programada a las `04:15` con el bloque `NUEVECUATROUNO_IA_OPS_SYNC`
- la sync de plataforma queda programada a las `04:45` dentro del mismo bloque gestionado
- el `crontab` previo se respalda en `./runtime/reports/ia-ops/`
- la salida del job se anexa en `./runtime/reports/ia-ops/nightly-auditor.cron.log`
- la salida del flujo reactivo se anexa en `./runtime/reports/ia-ops/reactive-watch.cron.log`
- la salida de sync editorial se anexa en `./runtime/reports/sync/editorial-sync.cron.log`
- la salida de sync de plataforma se anexa en `./runtime/reports/sync/platform-sync.cron.log`
- el flujo reactivo aplica cooldown y deduplicacion por incidente antes de relanzar `Sentry Agent`
- ambos sync jobs se ejecutan en `apply` por defecto porque son los que actualizan heartbeats y cierran el baseline operativo del laboratorio
- los jobs diarios se dejan fuera de la franja `02:00-04:00` para evitar ambiguedades en cambios de hora locales

### URLs principales
- Front `live`: `http://nuevecuatrouno.test/`
- Front `archive` por host admin: `http://archive.nuevecuatrouno.test/`
- Admin `live`: `http://nuevecuatrouno.test/wp-admin/`
- Admin `archive`: `http://archive.nuevecuatrouno.test/wp-admin/`
- Login `live`: `http://nuevecuatrouno.test/wp-login.php`
- Login `archive`: `http://archive.nuevecuatrouno.test/wp-login.php`
- Admin Panel: `http://localhost:9941`

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

## 5. Panel de administracion

El panel de administracion web se levanta con:

```sh
python3 -m admin
```

- Accesible en `http://localhost:9941`
- Funcionalidades: dashboard de salud, metricas operativas, diagnosticos por servicio, capacity planning, crontabs, sync, rollover, reportes
- No requiere autenticacion (acceso restringido por firewall/red local)
- Endpoint de salud: `GET /health`
- Dark mode con persistencia en localStorage
- Dependencias frontend locales (zero CDN)

### Metricas operativas

El sistema recoge 104 metricas por minuto desde 12 fuentes, almacenadas en SQLite con agregacion horaria y cleanup automatico.

Recogida manual:
```sh
python3 -m ops.cli.ia_ops collect-metrics
```

Dashboard de metricas: `http://localhost:9941/metrics` — tabs Sistema/Servicios, filtros por instancia, rangos 5m-7d, bandas de umbrales, comparativa temporal y marcadores de incidentes.

### Capacity planning

Tendencias de disco, memoria, Elastic y MySQL con proyecciones lineales. Accesible en `http://localhost:9941/capacity`.

### Diagnostico WordPress

Semaforos para cron events, BD bloat, actualizaciones pendientes y errores PHP. Accesible desde la tab WordPress en diagnosticos.

## 6. Resultado esperado
- Todos los contenedores en estado `healthy`
- `nuevecuatrouno.test/healthz` responde `200 ok`
- `archive.nuevecuatrouno.test/healthz` responde `200 ok`
- Los posts `2015-2023` caen en `fe-archive`
- Los posts `2024+` caen en `fe-live`
- El admin `live` cae en `be-admin` con `N9_SITE_CONTEXT=live`
- El admin `archive` cae en `be-admin` con `N9_SITE_CONTEXT=archive`
- `wp-admin/` redirige a `wp-login.php` sin loop de `302`
- El host `archive` no admin redirige a `nuevecuatrouno.test`
- La busqueda en `live` encuentra contenido de `live` y `archive` con enlaces canonicos, no `?p=<id>`
- `uploads` se comparte y la cache queda aislada por contexto

## 7. Rollback local
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

## 8. Notas operativas
- `./.secrets/` no se versiona y es solo para esta POC local.
- `./runtime/` es descartable y se puede regenerar con los scripts de bootstrap.
- El stack actual usa WordPress real, no stubs PHP.
- La semilla de laboratorio puede regenerarse sin reinstalar WordPress con `./scripts/bootstrap-wordpress-seed.sh`.
- ElasticPress indexa `live` y `archive` por separado y consulta mediante el alias `n9-search-posts`.
- Los heartbeats de jobs criticos viven en `./runtime/heartbeats/`.
- Los informes JSON y Markdown de IA-Ops viven en `./runtime/reports/ia-ops/`.
- La programacion baseline de `Nightly Auditor` se resuelve con `cron`; `Monit` queda evaluado pero no es requisito del laboratorio.
- La orquestacion compleja de IA-Ops, syncs y rollover ya vive en `ops/`; los scripts shell son wrappers de compatibilidad.
- `xmlrpc.php`, dotfiles y ficheros sensibles comunes quedan bloqueados por Nginx en esta fase.
- La rotacion minima de logs Docker queda definida en `compose.yaml` con `max-size=10m` y `max-file=3`.
- Este runbook centraliza la operacion manual de la POC; ya no hace falta una referencia rapida separada.
