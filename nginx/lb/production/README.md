# Produccion Cloudflare Tunnel

Estos snippets no se usan en la POC local.

Su objetivo es dejar preparada la configuracion de produccion para un origen privado detras de `cloudflared`.

Reglas:
- no copiar a produccion sin revisar rangos, red privada y topologia real
- no confiar headers de cliente si la peticion no viene del conector local o de una red privada controlada
- no abrir el origen a Internet
