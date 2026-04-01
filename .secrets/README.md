# Secretos locales de desarrollo

Este directorio se usa solo para secretos locales no versionados del entorno Docker Compose.

## Ficheros esperados
- `db-live-root-password`
- `db-live-user-password`
- `db-archive-root-password`
- `db-archive-user-password`

## Regla
- No se suben al repositorio.
- No se reutilizan en otros entornos.
- Son solo para levantar la POC local.
