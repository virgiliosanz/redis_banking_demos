# Secretos locales de desarrollo

Este directorio se usa solo para secretos locales no versionados del entorno Docker Compose.

## Ficheros esperados
- `db-live-root-password`
- `db-live-user-password`
- `db-archive-root-password`
- `db-archive-user-password`
- `wp-live-db-password`
- `wp-archive-db-password`
- `wp-live-admin-password`
- `wp-archive-admin-password`

## Regla
- No se suben al repositorio.
- No se reutilizan en otros entornos.
- Son solo para levantar la POC local.
- `wp-live-db-password` y `wp-archive-db-password` se sincronizan con las credenciales reales de usuario MySQL para evitar deriva local.
