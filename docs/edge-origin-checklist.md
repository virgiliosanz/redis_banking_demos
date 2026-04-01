# Checklist edge y origen

## POC local
- `cloudflared` no desplegado
- acceso solo desde el Mac local
- puertos publicados solo para laboratorio
- `nginx -t` y smoke tests en verde

## Produccion con Tunnel
- origen sin entrada publica directa desde Internet
- `cloudflared` desplegado en el host o red privada prevista
- admin protegido en Cloudflare Access, reglas de IP o equivalente
- `CF-Connecting-IP` confiado solo desde proxy local o red privada
- logs del origen capturando `CF-Connecting-IP`, `X-Forwarded-For`, `CF-Ray` y `realip_remote_addr`
- politica de emergencia definida si cae el Tunnel

## Verificaciones operativas
- acceso publico solo a traves de Cloudflare
- origen no accesible directamente desde exterior
- rutas admin sin cache
- front y busqueda siguen operativos
- rollback documentado
