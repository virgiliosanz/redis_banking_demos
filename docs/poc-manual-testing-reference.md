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
- `live`: `http://nuevecuatrouno.test/actualidad/post/`
- `live`: `http://nuevecuatrouno.test/cultura/agenda-local/`
- `live`: `http://nuevecuatrouno.test/servicios/contacto-redaccion/`
- `archive`: `http://nuevecuatrouno.test/2019/05/noticia/`
- `archive`: `http://nuevecuatrouno.test/2018/10/memoria-2018/`
- `archive`: `http://nuevecuatrouno.test/2021/06/archivo-cultural-2021/`

## Busquedas manuales de referencia
- `http://nuevecuatrouno.test/?s=Agenda+local+laboratorio`
- `http://nuevecuatrouno.test/?s=Memoria+hemeroteca+2018`
- `http://nuevecuatrouno.test/?s=rioja-laboratorio`

## Comprobaciones manuales recomendadas
- `GET /healthz` en ambos hosts devuelve `200`
- `GET /actualidad/post/` cae en `live`
- `GET /cultura/agenda-local/` cae en `live`
- `GET /2019/05/noticia/` cae en `archive`
- `GET /2018/10/memoria-2018/` cae en `archive`
- `GET /wp-admin/` redirige a `wp-login.php` sin loop
- `GET /wp-login.php` responde `200`
- `GET /?s=Archive+sample+page` devuelve resultados de busqueda

## Comandos utiles
```sh
curl -i http://nuevecuatrouno.test/healthz
curl -i http://archive.nuevecuatrouno.test/healthz
curl -I http://nuevecuatrouno.test/wp-admin/
curl -I http://archive.nuevecuatrouno.test/wp-admin/
curl -i "http://nuevecuatrouno.test/?s=rioja-laboratorio"
./scripts/bootstrap-wordpress-seed.sh
./scripts/smoke-functional.sh
```
