# Configuracion y secretos

Este directorio documenta convenciones de configuracion no sensible.

## Regla
- Los secretos no se guardan en el repositorio.
- `.env.example` solo define nombres de recursos, puertos, rutas locales y versiones.
- Los valores sensibles deben inyectarse externamente o mediante ficheros locales no versionados.
- Para la POC local, Compose consume secretos desde `./.secrets/` y ese directorio queda ignorado por Git salvo sus ejemplos y README.

## Tipos de valores esperados fuera del repo
- Password de root de MySQL
- Usuarios y passwords de WordPress
- Claves y salts de WordPress
- Certificados TLS
- `TELEGRAM_BOT_TOKEN` y cualquier otro token de integracion externa

## Bootstrap local
- `scripts/bootstrap-local-secrets.sh` genera secretos locales de desarrollo bajo `./.secrets/`.
- `scripts/bootstrap-wordpress-config.sh` genera `wp-config.php` y `wp-common.php` dentro de `./runtime/wp-root/`.
- `config/ia-ops-sources.env.example` documenta las fuentes permitidas y umbrales no sensibles del futuro `IA-Ops Bootstrap`.
- Si necesitas activar Telegram, crea `config/ia-ops-sources.env` local no versionado o exporta variables de entorno; ese fichero queda ignorado por Git.
- El mismo fichero de IA-Ops documenta tambien el baseline local de scheduling y alertas:
  - auditoria nocturna
  - watch reactivo cada `5` minutos
  - cooldown de alertas
  - umbrales de `4xx` y `5xx` recientes en `lb-nginx`
- El mismo fichero de IA-Ops documenta tambien el baseline de scheduling local:
  - auditoria nocturna
  - watch reactivo cada `5` minutos
  - cooldown de alertas
  - umbrales de `4xx` y `5xx` recientes en `lb-nginx`
