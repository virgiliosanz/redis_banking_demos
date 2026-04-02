# Proyecto: Seguridad, performance y simplificacion ops

## Objetivo
Implementar 10 mejoras de seguridad, performance y simplificacion en la plataforma, incluyendo hardening de compose, debug headers condicionales, paralelizacion de collectors y limpieza de codigo.

## Baseline
- Branch: `main`
- Commit inicial: `a5413a8`

## Fases ejecutadas

### Fase 1: Seguridad — debug headers condicionales y compose hardening
- **S2:** Headers `X-Debug-Site-Context` y `X-Debug-PHP-Upstream` condicionados a `X-Debug-Request: 1` via Nginx map chain
- **S3:** `cap_drop: [ALL]` en los 8 servicios de compose.yaml
- `read_only: true` + tmpfs en lb-nginx
- `tmpfs: /tmp` en todos los servicios
- MySQL con `cap_add` minimo: `DAC_OVERRIDE`, `SETGID`, `SETUID`

### Fase 2: Performance — paralelizar collectors
- **P1:** `context.py:collect_operational_context()` ejecuta 6 collectors en paralelo con `ThreadPoolExecutor`
- **P2:** `runtime.py` inspecciona 8 contenedores en paralelo con `ThreadPoolExecutor`

### Fase 3: Simplificacion — defaults y constantes
- **X1:** URLs base extraidas a `DEFAULT_BASE_URL` y `DEFAULT_ARCHIVE_URL` en `config.py`
- **X2:** `Settings.get()` trata strings vacios como ausencia (devuelve el default). 20 patrones `or "default"` redundantes eliminados

### Fase 4: Limpieza logs.py y paths
- **S4:** `redact-sensitive.sh` invocado con path absoluto via `settings.project_root`
- **P4/X3:** `logs.py` usa `re` nativo en vez de subprocess grep; usa `run_command` en vez de `subprocess.run` directo
- **X4:** Comentarios explicativos sobre `127.0.0.1` como loopback dentro del contenedor en elastic y mysql collectors
- `run_command` ampliado con parametro `input`

## Metricas
| Metrica | Antes | Despues |
|---|---|---|
| cap_drop en compose | 0 | 8 (todos los servicios) |
| Debug headers expuestos | siempre | solo con X-Debug-Request: 1 |
| Collectors en paralelo | no | si (ThreadPoolExecutor) |
| Patrones `or "default"` redundantes | 20 | 0 |
| subprocess en logs.py | 2 (grep + redact) | 0 (re nativo + run_command) |
| Tests | 103 | 104 |

## Decisiones tomadas
- MySQL necesita `cap_add: [DAC_OVERRIDE, SETGID, SETUID]` con `cap_drop: [ALL]` — minimo viable
- No se aplico `read_only` a PHP-FPM (requiere mapear todos los paths de escritura a tmpfs — scope separado)
- Elastic sin `read_only` porque escribe en su directorio de datos (volumen)
- `Settings.get()` con empty-string-as-absent: cambio de semantica aceptable porque ningun setting requiere valor vacio

## Hallazgo descartado
- **S1 (SQL injection en mysql.py):** los parametros SQL vienen de Settings (enteros de config local), no hay input externo. No es un vector de ataque real.

## Lecciones aprendidas
- El patron `or "default"` se propaga por imitacion — una vez que se usa en un sitio, se copia a todos. Mejor corregir el metodo base (`Settings.get()`) que perseguir cada uso.
- `ThreadPoolExecutor` para subprocess calls es seguro y trivial de implementar. El speedup real depende de la latencia de Docker.

## Mejoras pendientes (follow-up)
- Headers HTTP de seguridad: `Strict-Transport-Security`, `Content-Security-Policy`, `Permissions-Policy`
- PHP hardening: `expose_php=Off`, `display_errors=Off`, `DISALLOW_FILE_EDIT`
- `read_only: true` en servicios PHP-FPM (requiere analisis de paths de escritura)
- Tests para 7 modulos sin cobertura: cli, rollover, sync/platform, sync/common, util/docker, util/http, util/jsonio

## Commit final
`e96db99` — refactor: security hardening, parallel collectors, clean defaults, native log filtering

## Estado
**CERRADO**
