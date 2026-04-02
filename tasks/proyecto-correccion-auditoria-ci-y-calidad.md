# Proyecto: Corrección Auditoría CI y Calidad

**Estado:** En progreso
**Branch:** `full-project-audit`
**Baseline:** commit `6289550`
**Fecha inicio:** 2026-04-02

## Origen

Auditoría completa del repositorio que identificó 22 hallazgos.
Tras el refactor `6289550`, 7 fueron resueltos. Este proyecto aborda los 11 pendientes + 2 nuevos.

## Fases

### Fase 0 — Preparación ✅
- [x] Merge de `master` en branch de trabajo (fast-forward)
- [x] Crear fichero de proyecto

### Fase 1 — CI/CD funcional (N1 + N3) ✅
- [x] `quality.yml`: ya apuntaba a `main` (correcto)
- [x] `check-quality.sh`: añadida ejecución de `python3 -m unittest discover`

### Fase 2 — README raíz y documentación (H1 + M1 + N5) ✅
- [x] Crear `README.md` raíz con prerrequisitos, bootstrap rápido y mapa del repo
- [x] Eliminar párrafo duplicado en `config/README.md`
- [x] Documentar umbrales de cron por job con comentarios explicativos

### Fase 3 — Seguridad y hardening (H3 + H6 + H4) ✅
- [x] Comentario `POC ONLY` en `compose.yaml` (Elasticsearch xpack.security)
- [x] Debug headers condicionados via maps Nginx (desactivables cambiando default a "")
- [x] Entry point `ia-ops` y `dependencies = []` en `pyproject.toml`

### Fase 4 — Bug fix PHP (M4) ✅
- [x] `global $n9_ep_resolve_search_permalink` → `use ($n9_ep_resolve_search_permalink)` en `the_permalink` y `render_block`

### Fase 5 — Limpieza menor (L8p + L5) ✅
- [x] `_run_smoke` recibe `cwd` como parámetro, usa `settings.project_root.resolve()`
- [x] `import subprocess` movido al inicio de `content_year.py`

### Fase 6 — Validación final ✅
- [x] `check-python-tooling.sh` pasa (py_compile + cli help + 35 tests)
- [x] `check-shell-syntax.sh` pasa
- [x] `check-scripts-layout.sh` pasa
- [x] 35 tests unitarios pasan en 0.022s
- [x] Fichero de proyecto actualizado

## Decisiones

| Decisión | Motivo |
|----------|--------|
| Branch principal debe ser `main` | Confirmado por usuario. Rename `master` → `main` es tarea administrativa separada (requiere actualización de remote y GitHub settings). El workflow `quality.yml` ya apunta a `main`. |
| N1 ya no es un bug | El workflow apunta a `main`, que es el nombre correcto. Solo falta renombrar la branch local/remota. |
| Debug headers via maps | `if` en server context de Nginx es fragile. Usamos maps que producen valores vacíos cuando se desactivan. |
| `check-wordpress-entrypoints.sh` no pasa en clean checkout | Requiere `bootstrap-local-stack.sh` previo. Pre-existente, fuera de scope. |

## Lecciones aprendidas

- El refactor `6289550` resolvió 7/22 hallazgos de la auditoría — evidencia de que los refactors bien planificados producen mejoras colaterales.
- `check-wordpress-entrypoints.sh` en `check-quality.sh` rompe CI en clean checkout. Necesita un guard `[ -d runtime/wp-root ] || exit 0`.
- El mu-plugin PHP tenía un bug silencioso por usar `global` en closures de archivo — PHP no da error, simplemente la variable es `null`.

## Validación

- `check-python-tooling.sh`: ✅ exit 0
- `check-shell-syntax.sh`: ✅ exit 0
- `check-scripts-layout.sh`: ✅ exit 0
- `python3 -m unittest discover -s tests`: ✅ 35 tests, 0 failures
- `check-wordpress-entrypoints.sh`: ⚠️ falla (pre-existente, requiere runtime bootstrapped)
- `check-compose-config.sh`: no validado (Docker no corriendo localmente)
- `php -l`: no validado (PHP CLI no instalado localmente)
