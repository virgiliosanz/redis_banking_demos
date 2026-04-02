# NueveCuatroUno — Plataforma WordPress Alto Tráfico

Plataforma WordPress orientada a alto tráfico con arquitectura reproducible,
separación `live`/`archive`, búsqueda unificada con Elasticsearch y capa
IA-Ops para diagnóstico reactivo y auditoría programada.

## Prerrequisitos

- Docker y Docker Compose v2
- Python 3.11+
- PHP 8.2+ CLI (para validación y wp-cli)
- Bash 5+

## Bootstrap rápido (laboratorio local)

```sh
# 1. Generar secretos locales de desarrollo
./scripts/bootstrap-local-secrets.sh

# 2. Levantar el stack completo
./scripts/bootstrap-local-stack.sh

# 3. Verificar servicios
./scripts/smoke-routing.sh
./scripts/smoke-functional.sh
```

## Estructura del repositorio

| Directorio | Contenido |
|------------|-----------|
| `docs/` | Documentación viva de arquitectura, runbooks y contratos |
| `config/` | Configuración no sensible y ejemplos de env |
| `nginx/` | Configuración de LB-Nginx (routing, FastCGI) |
| `php/` | Dockerfiles para PHP-FPM y PHP-CLI |
| `wordpress/` | Templates wp-config, mu-plugins y contextos |
| `ops/` | Paquete Python: IA-Ops, collectors, sync, rollover |
| `scripts/` | Bootstrap, smoke tests, checks de calidad |
| `tasks/` | Ficheros de proyecto activos y archivo |
| `tests/` | Tests unitarios del paquete `ops/` |

## Documentación

- **[Documentación principal](docs/README.md)** — vista global de arquitectura y estado
- **[Runbook local](docs/poc-local-runbook.md)** — bootstrap, pruebas manuales, cron y Telegram
- **[Contrato IA-Ops](docs/ia-ops-bootstrap-contract.md)** — checks, severidades y formato de salida

## Checks de calidad

```sh
./scripts/check-quality.sh   # sintaxis shell, PHP, Python, compose + tests unitarios
```

## Estado

Laboratorio local funcional. No es producción.
Ver [docs/README.md](docs/README.md) para límites actuales.
