# Politica de cache por contexto

## Objetivo
Definir una politica de cache coherente con el uso esperado de `live`, `archive` y `admin`, separando claramente lo que hace el origen de lo que hara Cloudflare en produccion.

## Decision de esta fase
En esta iteracion no se introduce page cache persistente ni microcache en origen.

La POC usa:
- cabeceras de cache emitidas desde Nginx
- bypass explicito para flujos editoriales o sensibles
- distinta agresividad entre `live` y `archive`

Esto deja preparada la arquitectura sin meter una capa opaca de cache dentro de WordPress.

## Principio rector
- **Origen:** protege PHP, define cacheabilidad y no debe romper flujos autenticados.
- **Cloudflare en produccion:** absorbe trafico y aplica cache edge mas agresiva.
- **POC local:** no usa `cloudflared` ni simula el edge; solo valida politica y cabeceras.

## Reglas de bypass
Quedan fuera de cache de pagina en origen:
- `wp-admin`
- `wp-login.php`
- `wp-json`
- peticiones con cookies de sesion o privacidad (`wordpress_logged_in_`, `wordpress_sec_`, `wp-postpass_`, `comment_author_`)
- peticiones con `preview`
- busquedas con `?s=...`
- metodos distintos de `GET` y `HEAD`

## Politica por contexto

### Live publico
- `Cache-Control: public, max-age=60, s-maxage=300, stale-while-revalidate=30`
- `Surrogate-Control: max-age=300, stale-while-revalidate=30, stale-if-error=600`
- Objetivo: proteger picos y mantener frescura alta para portada y contenido reciente.

### Archive publico
- `Cache-Control: public, max-age=300, s-maxage=86400, stale-while-revalidate=600`
- `Surrogate-Control: max-age=86400, stale-while-revalidate=600, stale-if-error=86400`
- Objetivo: asumir contenido casi inmutable y exprimir cache mucho mas agresiva.

### Flujos bypass
- `Cache-Control: private, no-store`
- `Surrogate-Control: no-store`
- Objetivo: evitar cache en admin, login, sesiones, preview y busquedas.

### Assets estaticos
- `Cache-Control: public, max-age=3600, stale-while-revalidate=300`
- Aplicado a `uploads`, `themes`, `plugins` y `wp-includes` para tipos estaticos comunes.

## Lo que no hacemos aun
- no se activa `fastcgi_cache`
- no se activa microcache
- no se instala plugin de page cache
- no se implementa invalidacion automatica por purge

## Justificacion
- `live` necesita margen para contenido reciente y trabajo editorial.
- `archive` es ideal para politicas mas agresivas porque su churn esperado es mucho menor.
- meter page cache persistente en origen ahora complicaria el diagnostico y el debugging de la POC.
- la cache edge de Cloudflare sera el sitio natural para endurecer TTL en produccion.

## Validacion minima
- una URL publica de `live` debe devolver politica `live-public`
- una URL publica de `archive` debe devolver politica `archive-public`
- `wp-login.php` debe devolver `bypass`
- una peticion publica con cookie de sesion simulada debe devolver `bypass`
- una busqueda `?s=...` debe devolver `bypass`

## Rollback
1. Revertir cambios en `nginx/lb/conf.d/poc-routing.conf`
2. Recrear `lb-nginx`: `docker compose up -d --force-recreate lb-nginx`
3. Validar con `docker compose exec -T lb-nginx nginx -t`
4. Ejecutar `./scripts/smoke-routing.sh`, `./scripts/smoke-services.sh` y `./scripts/smoke-cache-policy.sh`
