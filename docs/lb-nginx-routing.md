# LB-Nginx: configuracion concreta para la POC

## Objetivo
Dejar una configuracion concreta de `LB-Nginx` para enrutar `live`, `archive` y `admin` usando `FastCGI` contra `php-fpm`.

## Criterios cerrados
- `LB-Nginx` es el unico punto de entrada HTTP/HTTPS.
- `FE-Live` sirve WordPress normal sobre `DB-Live`.
- `FE-Archive` sirve WordPress normal sobre `DB-Archive`.
- `BE-Admin` es un backend administrativo pasivo con dos `docroot`: `admin-live` y `admin-archive`.
- La regla anual existe solo en `LB-Nginx`.

## Decision operativa para esta POC
- `LB-Nginx` monta el mismo arbol de contenido en modo lectura para poder servir estaticos y calcular `SCRIPT_FILENAME` con el mismo layout que los backends PHP.
- `LB-Nginx` resuelve tres cosas antes del `fastcgi_pass`:
- si la peticion es administrativa,
- si el contexto del sitio es `live` o `archive`,
- y que `docroot` corresponde.

## Matriz funcional resumida
| Caso | Upstream | Docroot |
| :--- | :--- | :--- |
| `nuevecuatrouno.com/wp-admin` | `be_admin` | `/var/www/html/admin-live` |
| `archive.nuevecuatrouno.com/wp-admin` | `be_admin` | `/var/www/html/admin-archive` |
| `archive.nuevecuatrouno.com/*` | `fe_archive` | `/var/www/html/archive` |
| `/2015..2023/*` | `fe_archive` | `/var/www/html/archive` |
| resto | `fe_live` | `/var/www/html/live` |

## Configuracion de referencia

### 1. Upstreams
```nginx
upstream fe_live {
    server fe-live:9000;
    keepalive 16;
}

upstream fe_archive {
    server fe-archive:9000;
    keepalive 16;
}

upstream be_admin {
    server be-admin:9000;
    keepalive 16;
}
```

### 2. Maps de decision
```nginx
map $host $host_context {
    default live;
    archive.nuevecuatrouno.com archive;
}

map $uri $path_context {
    default live;
    ~^/(2015|2016|2017|2018|2019|2020|2021|2022|2023)(/|$) archive;
}

map $uri $is_admin_path {
    default 0;
    ~^/wp-admin(/|$) 1;
    =/wp-login.php 1;
    =/wp-admin/admin-ajax.php 1;
    ~^/wp-json(/|$) 1;
}

map "$is_admin_path|$host_context|$path_context" $site_context {
    default live;
    "1|live|live" live;
    "1|live|archive" live;
    "1|archive|live" archive;
    "1|archive|archive" archive;
    "0|live|live" live;
    "0|live|archive" archive;
    "0|archive|live" archive;
    "0|archive|archive" archive;
}

map "$is_admin_path|$site_context" $php_upstream {
    default fe_live;
    "0|live" fe_live;
    "0|archive" fe_archive;
    "1|live" be_admin;
    "1|archive" be_admin;
}

map "$is_admin_path|$site_context" $site_docroot {
    default /var/www/html/live;
    "0|live" /var/www/html/live;
    "0|archive" /var/www/html/archive;
    "1|live" /var/www/html/admin-live;
    "1|archive" /var/www/html/admin-archive;
}
```

### 3. Servidor HTTP
```nginx
server {
    listen 80;
    listen [::]:80;
    server_name nuevecuatrouno.com archive.nuevecuatrouno.com;

    return 301 https://$host$request_uri;
}
```

### 4. Servidor HTTPS
```nginx
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name nuevecuatrouno.com archive.nuevecuatrouno.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;

    root $site_docroot;
    index index.php;

    access_log /var/log/nginx/poc-access.log;
    error_log  /var/log/nginx/poc-error.log warn;

    location = /healthz {
        access_log off;
        add_header Content-Type text/plain;
        return 200 "ok\n";
    }

    location = /favicon.ico {
        access_log off;
        log_not_found off;
        expires 5m;
        try_files $uri =204;
    }

    location ~* \.(css|js|jpg|jpeg|gif|png|svg|webp|ico|woff|woff2)$ {
        access_log off;
        expires 10m;
        try_files $uri =404;
    }

    location / {
        try_files $uri $uri/ /index.php?$args;
    }

    location ~ \.php$ {
        include fastcgi_params;
        fastcgi_pass $php_upstream;
        fastcgi_index index.php;

        fastcgi_param SCRIPT_FILENAME $site_docroot$fastcgi_script_name;
        fastcgi_param DOCUMENT_ROOT $site_docroot;
        fastcgi_param HTTPS on;
        fastcgi_param HTTP_X_FORWARDED_PROTO https;
        fastcgi_param HTTP_X_FORWARDED_HOST $host;
        fastcgi_param HTTP_X_REQUEST_ID $request_id;
        fastcgi_param APP_SITE_CONTEXT $site_context;
        fastcgi_param APP_DOCROOT $site_docroot;

        fastcgi_read_timeout 60s;
        fastcgi_send_timeout 60s;
        fastcgi_connect_timeout 5s;

        fastcgi_buffer_size 32k;
        fastcgi_buffers 8 16k;
        fastcgi_busy_buffers_size 64k;
    }

    location ~* /(xmlrpc\.php|readme\.html|license\.txt)$ {
        deny all;
    }
}
```

## Observaciones tecnicas

### `/wp-json/`
En esta POC se enruta todo `/wp-json/` a `BE-Admin` para no romper el admin moderno de WordPress, incluido Gutenberg y llamadas autenticadas.

Si mas adelante se necesita un uso publico intensivo de REST en frontend, esta regla debera refinarse para separar REST administrativo de REST publico.

### Estaticos
Esta configuracion asume que `LB-Nginx` puede ver el mismo contenido montado bajo `/var/www/html`. Si no se cumple, hay dos alternativas:
- montar el mismo arbol en `LB-Nginx` en lectura,
- o reenviar tambien los estaticos a un backend HTTP dedicado, lo cual cambia el diseno actual.

### `BE-Admin`
`BE-Admin` no decide el contexto del sitio. El balanceador le entrega ya el `docroot` correcto:
- `/var/www/html/admin-live`
- `/var/www/html/admin-archive`

### Logging recomendado
Conviene ampliar `access_log` con un formato que incluya:
- `$request_id`
- `$host`
- `$uri`
- `$php_upstream`
- `$site_context`
- `$status`
- `$request_time`
- `$upstream_response_time`

## Casos de prueba minimos
- `https://nuevecuatrouno.com/wp-admin/` debe usar `be_admin` + `/var/www/html/admin-live`
- `https://archive.nuevecuatrouno.com/wp-admin/` debe usar `be_admin` + `/var/www/html/admin-archive`
- `https://nuevecuatrouno.com/2019/05/noticia/` debe usar `fe_archive` + `/var/www/html/archive`
- `https://archive.nuevecuatrouno.com/cultura/post/` debe usar `fe_archive` + `/var/www/html/archive`
- `https://nuevecuatrouno.com/actualidad/post/` debe usar `fe_live` + `/var/www/html/live`

## Resultado esperado de la fase
Con este documento la POC deja de depender de pseudocodigo para el balanceador y ya tiene una base concreta para implementacion o traduccion a `docker compose` y plantillas de despliegue.
