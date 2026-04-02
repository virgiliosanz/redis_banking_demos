# Origen detras de Cloudflare Tunnel

## Objetivo
Definir el modelo correcto de produccion para que el origen no quede expuesto a Internet y toda la entrada llegue a traves de Cloudflare Tunnel.

## Regla base
En produccion, el origen no debe aceptar trafico entrante publico directo.

El modelo esperado es:
- usuario -> Cloudflare edge
- Cloudflare edge -> Tunnel
- `cloudflared` -> origen privado

En esta POC no se monta `cloudflared`.

## Diferencia clave con proxy tradicional
Con Cloudflare Tunnel, el origen no recibe conexiones desde rangos IP publicos de Cloudflare.

Lo normal es que el origen vea como cliente a:
- `127.0.0.1` si `cloudflared` corre en la misma maquina
- una IP privada si `cloudflared` corre en otro host o nodo interno

Por eso:
- no basta con allowlist de IPs de Cloudflare en el origen
- la proteccion real debe ser de red y topologia
- el origen debe quedar cerrado a Internet y escuchar solo en red privada o loopback

## Modelo esperado por entorno

### POC local
- sin `cloudflared`
- acceso directo solo desde el Mac local
- puertos publicados para laboratorio
- no expuesto a Internet

### Produccion
- `cloudflared` como punto de entrada al origen
- origen sin puertos publicos accesibles desde Internet
- acceso administrativo protegido en edge con Cloudflare Access, reglas de IP o equivalente
- origen aceptando solo trafico desde loopback o red privada del conector

## Trust boundaries

### Edge
Responsabilidad de Cloudflare:
- publicacion del dominio
- control de acceso administrativo
- politicas WAF y rate limiting
- cache edge
- cabeceras `CF-Connecting-IP`, `CF-Ray` y similares

### Origen
Responsabilidad del origen:
- no quedar expuesto publicamente
- confiar solo en el proxy local o privado que entrega el trafico del Tunnel
- tratar `CF-Connecting-IP` como IP real solo si la peticion viene de un origen confiable
- mantener hardening propio aunque exista Cloudflare

## Recomendacion de red
- `lb-nginx` escuchando solo en red privada o en loopback
- `cloudflared` conectando internamente al balanceador
- firewall o security groups negando entrada publica directa
- salida a Internet permitida para updates y necesidades operativas

## Admin
El admin no debe depender solo de WordPress.

Capas esperadas:
- acceso restringido en Cloudflare Access o politica equivalente
- no cache en origen
- superficie reducida en Nginx
- origen no expuesto directamente

## Real IP
Si `cloudflared` entrega `CF-Connecting-IP`, Nginx debe confiar en ese header solo cuando la conexion venga del conector local o de una red privada definida.

No se debe:
- confiar ciegamente en `CF-Connecting-IP` desde cualquier cliente
- abrir el origen a Internet pensando que Cloudflare ya “lo protege”

## Modo de fallo
Si el Tunnel cae:
- el origen debe seguir sin ser publico
- el sitio quedara inaccesible externamente
- debe existir un procedimiento de acceso de emergencia solo para operacion

## Rollback de endurecimiento
1. quitar la configuracion de confianza de `real_ip` si se ha aplicado mal
2. revertir cualquier restriccion de origen que bloquee al conector legitimo
3. validar con `nginx -t`
4. comprobar acceso a traves del conector
5. comprobar que el origen sigue sin exposicion publica directa

## Checklist resumida

### POC local
- `cloudflared` no desplegado
- acceso solo desde el Mac local
- puertos publicados solo para laboratorio
- `nginx -t` y smoke tests en verde

### Produccion con Tunnel
- origen sin entrada publica directa desde Internet
- `cloudflared` desplegado en el host o red privada prevista
- admin protegido en Cloudflare Access, reglas de IP o equivalente
- `CF-Connecting-IP` confiado solo desde proxy local o red privada
- logs del origen capturando `CF-Connecting-IP`, `X-Forwarded-For`, `CF-Ray` y `realip_remote_addr`
- politica de emergencia definida si cae el Tunnel

### Verificaciones operativas
- acceso publico solo a traves de Cloudflare
- origen no accesible directamente desde exterior
- rutas admin sin cache
- front y busqueda siguen operativos
- rollback documentado
