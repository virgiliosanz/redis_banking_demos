# Inventario tecnico de implementacion POC

## 1. Objetivo
Traducir `docs/project.md` a una base reproducible de infraestructura versionada, dejando claras las responsabilidades de cada servicio, sus artefactos en el repositorio y la separacion entre configuracion no sensible y secretos.

## 2. Formato de despliegue elegido
- Formato base: `compose.yaml`
- Orquestacion objetivo de la POC: Docker Compose
- Ambito: laboratorio o staging tecnico, no produccion

## 3. Layout de repositorio adoptado
- `compose.yaml`: definicion base del stack
- `nginx/`: configuracion versionada del balanceador
- `php/`: configuracion compartida de `php-fpm`
- `wordpress/`: convenciones y plantillas de configuracion por contexto
- `scripts/`: validaciones y smoke tests
- `config/`: documentacion de fuentes y convenciones no sensibles
- `docs/`: arquitectura, inventario y operacion

## 4. Mapeo de servicios a artefactos
| Servicio | Rol | Artefacto principal |
| :--- | :--- | :--- |
| `lb-nginx` | Entrada publica, routing y FastCGI | `compose.yaml`, `nginx/lb/` |
| `fe-live` | PHP-FPM para `live` | `compose.yaml`, `php/common/` |
| `fe-archive` | PHP-FPM para `archive` | `compose.yaml`, `php/common/` |
| `be-admin` | PHP-FPM administrativo pasivo | `compose.yaml`, `php/common/` |
| `db-live` | MySQL de `live` | `compose.yaml` |
| `db-archive` | MySQL de `archive` | `compose.yaml` |
| `elastic` | Busqueda comun no critica | `compose.yaml` |
| `cron-master` | `WP-CLI` y tareas programadas | `compose.yaml`, `scripts/` |

## 5. Redes, aliases y convenciones
- Red base: `wp-poc-net`
- Driver: `bridge`
- Los aliases internos reflejan los hostnames documentados en `docs/project.md`
- La resolucion DNS interna depende del nombre de servicio y alias de Compose

## 6. Bind mounts de laboratorio
El layout objetivo del host sigue siendo `/tank/data/...` en la documentacion de arquitectura, pero para la primera implementacion del repo se parametriza un layout local de laboratorio bajo `./runtime/...`.

| Mount logical | Path local por defecto | Path en contenedor |
| :--- | :--- | :--- |
| WordPress `live` | `./runtime/wp-root/live/current/public` | `/var/www/html/live` |
| WordPress `archive` | `./runtime/wp-root/archive/current/public` | `/var/www/html/archive` |
| WordPress `admin-live` | `./runtime/wp-root/admin-live/current/public` | `/var/www/html/admin-live` |
| WordPress `admin-archive` | `./runtime/wp-root/admin-archive/current/public` | `/var/www/html/admin-archive` |
| Config compartida | `./runtime/wp-root/shared/config` | `/var/www/shared/config` |
| MySQL `live` | `./runtime/mysql/live` | `/var/lib/mysql` |
| MySQL `archive` | `./runtime/mysql/archive` | `/var/lib/mysql` |
| Elastic data | `./runtime/elasticsearch` | `/usr/share/elasticsearch/data` |

## 7. Convencion de variables y secretos
### Se versiona
- Puertos publicos
- Nombres de red y proyecto Compose
- Rutas locales no sensibles
- Versiones de imagen
- Zona horaria

### No se versiona
- Passwords de MySQL
- Credenciales WordPress
- Claves y salts
- Certificados TLS
- Tokens o credenciales de despliegue

### Regla operativa
- `.env.example` solo contiene variables no sensibles.
- Los secretos se inyectaran por mecanismo externo o fichero local no versionado.
- No se usaran secretos reales en `compose.yaml`.

## 8. Resultado esperado de Fase 1
- El repositorio ya refleja la topologia de la POC.
- Existe una base de Compose valida para seguir con Nginx y servicios.
- Se ha cerrado la separacion entre estructura versionada y datos sensibles.
