# Proyecto: simplificacion de runtime wp-root y docroots WordPress

## 1. Objetivo
Revisar y simplificar en una fase posterior la estructura de `runtime/wp-root` y la duplicacion de docroots WordPress, reduciendo complejidad operativa sin romper routing, administracion, cache ni separacion entre `live` y `archive`.

## 2. Motivacion
- El layout actual expone cinco contextos bajo `runtime/wp-root`: `live`, `archive`, `admin-live`, `admin-archive` y `shared`.
- La separacion tiene logica operativa hoy, pero tambien introduce duplicacion de core WordPress y ruido visual.
- En especial, el core completo de WordPress se copia a docroots publicos y administrativos, lo que arrastra `wp-admin` incluso donde el routing actual no lo sirve directamente.
- Esto complica el razonamiento sobre codigo comun, drift y ownership de docroots.

## 3. Estado actual que se acepta por ahora
- No se toca en la fase actual para no mezclarlo con calidad, refactor de `ops/` y mejora del plano reactivo.
- El bloqueo de `wp-admin` en edge via Cloudflare puede reducir exposicion, pero no resuelve la duplicacion interna de docroots.

## 4. Preguntas que este proyecto debera cerrar
- Si el modelo correcto debe seguir siendo de cinco contextos o reducirse a cuatro o tres.
- Si `admin-live` puede colapsarse con `live` sin perder claridad operativa.
- Si `admin-archive` necesita seguir separado por `WP_HOME` y `WP_SITEURL` o puede resolverse con configuracion mas dinamica.
- Si el core WordPress debe dejar de copiarse completo a todos los docroots.
- Como preservar aislamiento de cache y comportamiento de admin/public sin aumentar fragilidad.

## 5. Alcance propuesto
- Inventario de mounts, docroots, `wp-config.php`, routing y caches por contexto.
- Analisis de compatibilidad de simplificacion `5 -> 4` o `5 -> 3`.
- Propuesta de layout objetivo con impacto en Compose, bootstrap, Nginx, smokes y documentacion.
- Definicion de validacion y rollback de la migracion.

## 6. Fuera de alcance
- Hardening productivo.
- Cambios en backups o preproduccion.
- Rehacer a la vez toda la estrategia de admin en Cloudflare.

## 7. Riesgos a vigilar
- Romper URLs admin o login.
- Desalinear `WP_HOME` y `WP_SITEURL`.
- Mezclar caches publicas y administrativas.
- Introducir drift de codigo o config en WordPress.

## 8. Validacion minima esperada
- `docker compose config`
- `nginx -t`
- `smoke-routing.sh`
- `smoke-services.sh`
- `smoke-persistence.sh`
- validacion manual de `wp-admin` y `wp-login.php` en `live` y `archive`

## 9. Rollback minimo esperado
- Revertir `compose.yaml`, bootstrap de WordPress y routing Nginx.
- Regenerar runtime local.
- Recrear el stack.
- Repetir smokes y accesos admin/public.

## 10. Estado
Pendiente para fase posterior

## 11. Lecciones aprendidas
- La simplificacion del layout tiene sentido, pero no debe mezclarse con el refactor de IA-Ops si queremos mantener control del riesgo.
