# Scripts operativos

Este directorio mezcla interfaces humanas, wrappers finos y helpers internos. La regla operativa es no invocar cualquier fichero por intuicion: primero usar los entrypoints documentados en runbooks o en este inventario.

## 1. Entry points de operador

Estos son los scripts pensados para operacion manual o validacion local:

- bootstrap local:
  - `bootstrap-local-runtime.sh`
  - `bootstrap-local-stack.sh`
  - `bootstrap-local-secrets.sh`
- bootstrap WordPress:
  - `bootstrap-wordpress-layout.sh`
  - `bootstrap-wordpress-core.sh`
  - `bootstrap-wordpress-config.sh`
  - `bootstrap-wordpress-install.sh`
  - `bootstrap-wordpress-seed.sh`
  - `bootstrap-elasticpress.sh`
- smokes:
  - `smoke-routing.sh`
  - `smoke-services.sh`
  - `smoke-search.sh`
  - `smoke-cache-policy.sh`
  - `smoke-cache-isolation.sh`
  - `smoke-persistence.sh`
  - `smoke-rollover-year.sh`
  - `smoke-functional.sh`
- IA-Ops y scheduling:
  - `collect-cron-health.sh`
  - `run-nightly-auditor.sh`
  - `run-sentry-agent.sh`
  - `run-reactive-watch.sh`
  - `install-nightly-auditor-cron.sh`
  - `install-reactive-watch-cron.sh`
  - `install-sync-jobs-cron.sh`
- mantenimiento de calidad:
  - `check-python-tooling.sh`
  - `check-shell-syntax.sh`
  - `check-scripts-layout.sh`
  - `check-wordpress-entrypoints.sh`
  - `check-php-syntax.sh`
  - `check-compose-config.sh`
  - `check-quality.sh`
- routing y utilidades lineales:
  - `render-routing-cutover.sh`
  - `advance-routing-cutover.sh`
  - `redact-sensitive.sh`

## 2. Helpers internos

Estos ficheros existen para ser llamados desde Python o mediante `wp-cli eval-file`; no son la interfaz principal de operador:

- rollover:
  - `internal/rollover/collect-year-summary.php`
  - `internal/rollover/export-year.php`
  - `internal/rollover/import-snapshot.php`
  - `internal/rollover/delete-source-posts.php`
  - `internal/rollover/detect-archive-collisions.php`
- sync editorial:
  - `internal/sync/editorial/source-snapshot.php`
  - `internal/sync/editorial/snapshot.php`
  - `internal/sync/editorial/plan.php`
  - `internal/sync/editorial/apply.php`
- metricas:
  - `internal/wp-metrics.php`
- sync de plataforma:
  - `internal/sync/platform/source-snapshot.php`
  - `internal/sync/platform/snapshot.php`
  - `internal/sync/platform/plan.php`
  - `internal/sync/platform/apply.php`

## 3. Wrappers de compatibilidad

Los `run-*`, `install-*`, `sync-*.sh`, `collect-cron-health.sh` y `rollover-content-year.sh` son wrappers finos sobre `ops.cli.ia_ops`. Se mantienen porque reducen friccion en runbooks y porque la interfaz shell actual sigue siendo util para la operacion diaria.

Los antiguos wrappers de collectors (`collect-app-health.sh`, `collect-host-health.sh`, etc.), `report-live-archive-sync-drift.sh` y `send-telegram-test.sh` fueron eliminados por ser redundantes con el panel de administracion y la CLI directa `python3 -m ops.cli.ia_ops`.

La raiz de `scripts/` debe reservarse preferentemente para entrypoints de operador. Los helpers PHP internos viven bajo `scripts/internal/` para dejar mas clara esa frontera.

## 4. Candidato claro a legado eliminado

- `bootstrap-wordpress-stubs.sh`

Este script pertenecia al periodo de stubs PHP y ya no forma parte del flujo del repositorio. Se elimina para evitar mantener compatibilidad innecesaria en una POC no productiva.

## 5. Regla de evolucion

Cuando un script cambie de rol, esta clasificacion debe actualizarse:

- si es interfaz humana estable, debe quedar documentado aqui y en runbooks
- si es helper interno, no debe venderse como entrypoint publico
- si es legado, debe marcarse antes de eliminarlo
- en esta POC no se debe conservar compatibilidad heredada por defecto: si un script o wrapper ya no aporta valor real, se puede simplificar o eliminar siempre que se actualicen documentacion, smokes y entrypoints vigentes

## 6. Politica de cron por entorno

- en este host de laboratorio no se dejan bloques gestionados de `crontab` instalados de forma persistente
- en este repo los instaladores `install-*-cron.sh` se usan aqui sobre todo para `--print`, validacion o pruebas puntuales
- en preproduccion y produccion si deberian instalarse los bloques gestionados de `nightly`, `reactive` y `sync`, porque forman parte del baseline operativo previsto
