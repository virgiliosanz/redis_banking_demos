# Referencia rapida de pruebas manuales

## URLs principales
- Front `live`: `http://nuevecuatrouno.test/`
- Front `archive` por host admin: `http://archive.nuevecuatrouno.test/`
- Admin `live`: `http://nuevecuatrouno.test/wp-admin/`
- Admin `archive`: `http://archive.nuevecuatrouno.test/wp-admin/`
- Login `live`: `http://nuevecuatrouno.test/wp-login.php`
- Login `archive`: `http://archive.nuevecuatrouno.test/wp-login.php`

## Credenciales de laboratorio
- Usuario admin `live`: `n9liveadmin`
- Password admin `live`: `cat ./.secrets/wp-live-admin-password`
- Usuario admin `archive`: `n9archiveadmin`
- Password admin `archive`: `cat ./.secrets/wp-archive-admin-password`

## Contenido actual de referencia
- Contrato de URL para posts: `/%year%/%monthnum%/%day%/%postname%/`
- `live`: `http://nuevecuatrouno.test/cultura/agenda-local/`
- `live`: `http://nuevecuatrouno.test/servicios/contacto-redaccion/`
- `archive`: `http://nuevecuatrouno.test/2015/02/03/logrono-revive-la-noche-de-san-mateo-en-su-casco-antiguo/`
- `archive`: `http://nuevecuatrouno.test/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/`
- `archive`: `http://nuevecuatrouno.test/2023/12/29/el-archivo-municipal-consolida-2023-como-ano-de-transicion-digital/`
- `live`: `http://nuevecuatrouno.test/2024/04/11/logrono-impulsa-2024-con-nuevas-rutas-peatonales-y-comercio-abierto/`
- `live`: `http://nuevecuatrouno.test/2025/09/19/la-programacion-cultural-de-2025-lleva-el-teatro-a-todos-los-barrios/`
- `live`: `http://nuevecuatrouno.test/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/`

## Busquedas manuales de referencia
- El frontend publico muestra un buscador visible en cabecera.
- `http://nuevecuatrouno.test/?s=Cristo+del+Santo+Sepulcro`
- `http://nuevecuatrouno.test/?s=rioja+metropolitano`
- `http://nuevecuatrouno.test/?s=rioja-laboratorio`

## Comprobaciones manuales recomendadas
- `GET /healthz` en ambos hosts devuelve `200`
- `GET /cultura/agenda-local/` cae en `live`
- `GET /2015/02/03/...` cae en `archive`
- `GET /2024/04/11/...` cae en `live`
- `GET /wp-admin/` redirige a `wp-login.php` sin loop
- `GET /wp-login.php` responde `200`
- `GET /?s=rioja-laboratorio` devuelve resultados mixtos con permalinks canonicos

## Comandos utiles
```sh
curl -i http://nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/healthz
curl -I http://nuevecuatrouno.test/wp-admin/
curl -I http://archive.nuevecuatrouno.test/wp-admin/
curl -sD - "http://nuevecuatrouno.test/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/" -o /dev/null | grep X-Origin-Cache-Policy
curl -sD - "http://nuevecuatrouno.test/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/" -o /dev/null | grep X-Origin-Cache-Policy
curl -i "http://nuevecuatrouno.test/?s=rioja-laboratorio"
./scripts/bootstrap-wordpress-seed.sh
./scripts/smoke-functional.sh
```
