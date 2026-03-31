# Infraestructura WordPress POC (Docker)

## 1. Objetivo
Prueba de concepto de una infraestructura WordPress segmentada por tipo de tráfico y antiguedad del contenido.

Esta documentacion describe una POC funcional, no un diseno de produccion. Se aceptan atajos deliberados siempre que queden explicitos.

## 2. Alcance y Premisas
- Plataforma base: `docker`.
- Carga esperada de la POC: `2-3` usuarios concurrentes como maximo.
- No se disena para `100k usuarios/dia`.
- No habra alta disponibilidad.
- No habra backups en esta fase.
- Elasticsearch no es un componente critico en la POC.
- El objetivo es validar topologia, enrutado y separacion funcional.

## 3. Componentes
| Componente | CPU | RAM | Red interna | Funcion |
| :--- | :--- | :--- | :--- | :--- |
| `LB-Nginx` | 1 | 2 GB | `10.0.0.10` | Entrada publica, TLS, enrutado y FastCGI |
| `FE-Live` | 1 | 2 GB | `10.0.0.11` | `php-fpm` para trafico vivo |
| `FE-Archive` | 1 | 2 GB | `10.0.0.12` | `php-fpm` para trafico historico |
| `BE-Admin` | 1 | 2 GB | `10.0.0.13` | `php-fpm` administrativo con dos `docroot`: `live` y `archive` |
| `DB-Live` | 1 | 4 GB | `10.0.0.20` | MySQL de contenido vivo |
| `DB-Archive` | 1 | 4 GB | `10.0.0.21` | MySQL de contenido historico |
| `Elastic` | 1 | 4 GB | `10.0.0.30` | Busqueda comun no critica |
| `Cron-Master` | 1 | 2 GB | `10.0.0.40` | `WP-CLI` y tareas programadas |

## 4. Enrutado Funcional

### 4.1 Entrada publica
- Solo `LB-Nginx` expone `80/443`.
- El host expone SSH por su puerto configurado.

### 4.2 Regla principal por dominio y ruta
- `nuevecuatrouno.com` sirve trafico general.
- `archive.nuevecuatrouno.com` sirve trafico historico.
- Cualquier peticion a `/wp-admin`, login, `admin-ajax.php` o REST de administracion se enruta a `BE-Admin`.
- Si el primer segmento del path es uno de `2015`, `2016`, `2017`, `2018`, `2019`, `2020`, `2021`, `2022` o `2023`, la peticion se enruta a `FE-Archive`.
- Cualquier otra peticion se enruta a `FE-Live`.

### 4.3 Mecanismo tecnico
- `LB-Nginx` habla `FastCGI` con `php-fpm`.
- La seleccion del backend se hace en el balanceador segun dominio y URL.
- La regla anual solo existe en `LB-Nginx`.
- Los WordPress de `FE-Live` y `FE-Archive` son instancias normales y no contienen logica especifica de particion por anos.
- La peticion FastCGI interna fija el `docroot` y el contexto necesarios para que cada `php-fpm` use su configuracion correspondiente.
- `BE-Admin` no decide nada por si mismo: sirve `live` o `archive` segun el `docroot` que le llega desde `LB-Nginx`.

## 5. Flujos de Red Internos
- `LB-Nginx -> FE-Live` por FastCGI.
- `LB-Nginx -> FE-Archive` por FastCGI.
- `LB-Nginx -> BE-Admin` por FastCGI.
- `FE-Live -> DB-Live` por `3306`.
- `FE-Archive -> DB-Archive` por `3306`.
- `FE-Live -> Elastic` por `9200`.
- `FE-Archive -> Elastic` por `9200`.
- `BE-Admin -> DB-Live` cuando el `docroot` administrativo activo sea `live`.
- `BE-Admin -> DB-Archive` cuando el `docroot` administrativo activo sea `archive`.
- `BE-Admin -> Elastic` cuando aplique.
- `Cron-Master -> DB-Live` y `DB-Archive`.
- `Cron-Master -> Elastic` cuando aplique.

## 6. Seguridad y Exposicion
- La restriccion documentada se refiere a exposicion desde red externa, no a trafico saliente general.
- Bases de datos y servicios internos no exponen puertos a Internet.
- Solo `LB-Nginx` recibe trafico HTTP/HTTPS desde fuera.
- `BE-Admin`, bases de datos, Elastic y `Cron-Master` quedan en red interna.

## 7. Datos y Almacenamiento
- Host path compartido: `/tank/data/wp-root`.
- Punto de montaje en contenedores FE/BE: `/var/www/html`.
- Usuario esperado: `www-data` (`UID/GID 33`).

### 7.1 Shortcut deliberado de la POC
- Se usa almacenamiento compartido simple mediante bind mount.
- No se introduce una capa adicional de storage distribuido.
- Este enfoque prioriza simplicidad sobre aislamiento, resiliencia o escalabilidad.

## 8. Criterio de Datos
- `FE-Live` trabaja contra `DB-Live`.
- `FE-Archive` trabaja contra `DB-Archive`.
- La separacion funcional no la resuelve WordPress de forma automatica: la impone `LB-Nginx` y la configuracion del backend seleccionado.
- La regla de anos no se replica dentro de WordPress ni en los backends PHP.
- Elasticsearch se usa como servicio comun de busqueda para ambos frontends.
- `BE-Admin` usa un unico contenedor PHP administrativo, pero con dos `docroot` separados: uno para `live` y otro para `archive`.

## 9. Monitorizacion Propuesta para la POC

### 9.1 LB-Nginx
- Disponibilidad del proceso.
- Tasa de `5xx`.
- Latencia hacia upstreams FastCGI.
- Fallos de conexion FastCGI.

### 9.2 FE-Live, FE-Archive y BE-Admin
- Disponibilidad de `php-fpm`.
- Saturacion de workers.
- Memoria consumida.
- Tiempo de respuesta medio.

### 9.3 DB-Live y DB-Archive
- Disponibilidad de MySQL.
- Numero de conexiones.
- `Threads_running`.
- Slow queries.
- Crecimiento basico del tamano de datos.

### 9.4 Elastic
- Disponibilidad del servicio.
- Tiempo de respuesta.
- Estado basico del nodo.

### 9.5 Cron-Master
- Ultima ejecucion correcta.
- Duracion de tareas.
- Fallo/no fallo de jobs criticos.

### 9.6 Politica de alertas para la POC
- `critical`: servicio caido, MySQL inaccesible, `php-fpm` sin capacidad de atender, Elastic caido.
- `warning`: memoria alta sostenida, slow queries repetidas, incremento de `5xx`, latencia anomala.
- En esta POC se prioriza alertar antes que reiniciar automaticamente por consumo alto de RAM.

## 10. Limitaciones Asumidas
- Sin backups.
- Sin HA.
- Sin Redis ni cache avanzada especifica.
- Sin endurecimiento completo de produccion.
- Sin validacion de capacidad real para trafico masivo.
- Elasticsearch como punto unico de fallo aceptado.

## 11. Matriz de Enrutado de LB-Nginx
| Condicion | Backend | Base de datos esperada | Notas |
| :--- | :--- | :--- | :--- |
| Host `nuevecuatrouno.com` y path `/wp-admin` | `BE-Admin` | `DB-Live` | `docroot` admin `live` |
| Host `nuevecuatrouno.com` y path `/wp-login.php` | `BE-Admin` | `DB-Live` | Login de `live` |
| Host `nuevecuatrouno.com` y path `/wp-json/*` administrativo | `BE-Admin` | `DB-Live` | Solo rutas de administracion |
| Host `nuevecuatrouno.com` y path `/wp-admin/admin-ajax.php` | `BE-Admin` | `DB-Live` | Trafico administrativo |
| Host `archive.nuevecuatrouno.com` y path administrativo | `BE-Admin` | `DB-Archive` | `docroot` admin `archive` |
| Host `archive.nuevecuatrouno.com` | `FE-Archive` | `DB-Archive` | Dominio dedicado |
| Primer segmento del path entre `2015` y `2023` | `FE-Archive` | `DB-Archive` | Regla por URL |
| Cualquier otro caso | `FE-Live` | `DB-Live` | Regla por defecto |

### 11.1 Orden de evaluacion
- Primero se evalua si la peticion es administrativa.
- Despues se evalua el host `archive.nuevecuatrouno.com`.
- Despues se evalua el primer segmento del path.
- Si no coincide nada anterior, la peticion va a `FE-Live`.

### 11.2 Casos de ejemplo
- `https://nuevecuatrouno.com/wp-admin/` -> `BE-Admin`
- `https://nuevecuatrouno.com/wp-login.php` -> `BE-Admin`
- `https://archive.nuevecuatrouno.com/wp-admin/` -> `BE-Admin`
- `https://archive.nuevecuatrouno.com/2018/10/mi-articulo/` -> `FE-Archive`
- `https://nuevecuatrouno.com/2019/05/otro-articulo/` -> `FE-Archive`
- `https://nuevecuatrouno.com/actualidad/noticia/` -> `FE-Live`

## 12. Contrato FastCGI para la POC

### 12.1 Principios
- `LB-Nginx` es el unico punto de entrada HTTP.
- Los contenedores PHP no exponen HTTP publico, solo `php-fpm`.
- El balanceador decide el backend antes de ejecutar `fastcgi_pass`.
- Cada backend recibe el `docroot` y el contexto FastCGI que le corresponde.

### 12.2 Variables FastCGI a fijar
- `SCRIPT_FILENAME` debe resolver contra el `docroot` del backend seleccionado.
- `DOCUMENT_ROOT` debe coincidir con el `docroot` activo.
- `HTTP_HOST` debe preservarse para que WordPress vea el host original.
- `REQUEST_URI` debe preservarse integra.
- `SERVER_NAME` debe reflejar el host solicitado.
- `HTTPS` debe marcarse cuando la entrada publica sea TLS.

### 12.3 Docroots esperados
- `FE-Live`: `docroot` de la instancia WordPress viva.
- `FE-Archive`: `docroot` de la instancia WordPress historica.
- `BE-Admin`: `docroot` administrativo `live` o `archive` segun el host y la ruta ya resueltos por `LB-Nginx`.

### 12.4 Estructura logica recomendada
- `/var/www/html/live`
- `/var/www/html/archive`
- `/var/www/html/admin-live`
- `/var/www/html/admin-archive`

Esta estructura es una recomendacion de POC. Puede ajustarse si se mantiene la separacion funcional y el balanceador fija correctamente `DOCUMENT_ROOT` y `SCRIPT_FILENAME`.

### 12.5 Esqueleto orientativo de configuracion
```nginx
map $request_uri $is_archive_path {
    default 0;
    ~^/(2015|2016|2017|2018|2019|2020|2021|2022|2023)(/|$) 1;
}

map $host $host_backend {
    default fe_live;
    archive.nuevecuatrouno.com fe_archive;
}

map $request_uri $is_admin_path {
    default 0;
    ~^/wp-admin(/|$) 1;
    =/wp-login.php 1;
    =/wp-admin/admin-ajax.php 1;
}
```

```nginx
location / {
    include fastcgi_params;

    if ($is_admin_path) {
        fastcgi_pass be_admin:9000;
    }

    if ($host_backend = fe_archive) {
        fastcgi_pass fe_archive:9000;
    }

    if ($is_archive_path) {
        fastcgi_pass fe_archive:9000;
    }

    fastcgi_pass fe_live:9000;
}
```

### 12.6 Nota tecnica importante
- El bloque anterior es solo orientativo y no debe copiarse tal cual a produccion.
- En la implementacion real conviene evitar `if` en `location` y resolver la seleccion del upstream con `map`, variables y bloques mas limpios.
- Para esta POC, lo importante es dejar cerrado el contrato funcional: quien decide el backend, con que prioridad y que variables deben preservarse.
- `BE-Admin` sigue el mismo principio: el contenedor no decide si sirve `live` o `archive`; recibe el `docroot` administrativo correcto desde el balanceador.

## 13. Siguientes Pasos Recomendados
- Bajar a detalle la configuracion final de `LB-Nginx` sin pseudocodigo.
- Definir configuraciones separadas de WordPress para `live`, `archive` y `admin`.
- Especificar que operaciones administrativas pueden tocar `DB-Live`, `DB-Archive` o ambas.
- Documentar el comportamiento degradado cuando `Elastic` no este disponible.
