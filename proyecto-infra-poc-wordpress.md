# Proyecto: Infra POC WordPress

## Objetivo
Convertir la documentacion inicial en una especificacion coherente de una POC WordPress segmentada en `live`, `archive` y `admin`.

## Estado
- Fase actual: `Fase 3` completada, `Fase 4` lista para arrancar.
- Estado: en curso.

## Decisiones acordadas
- La plataforma real sera `docker`.
- `LB-Nginx` hablara `FastCGI` con `php-fpm`.
- Existen tres backends PHP diferenciados: `FE-Live`, `FE-Archive` y `BE-Admin`.
- `FE-Live` apunta a `DB-Live`.
- `FE-Archive` apunta a `DB-Archive`.
- Ambos frontends consumen `Elastic`.
- `/wp-admin` y los flujos administrativos asociados se enrutan a `BE-Admin`.
- La separacion `live/archive` se decide por dominio y por URL.
- Si el primer segmento de la ruta es `2015` a `2023`, la peticion se enruta a `FE-Archive`.
- El resto del trafico va a `FE-Live`.
- `archive.nuevecuatrouno.com` queda reservado al admin de `archive`, no al frontend publico.
- Es una POC de baja carga: `2-3` usuarios simultaneos como maximo.
- No se contemplan backups en esta fase.
- Elasticsearch no se considera critico en esta fase.
- `LB-Nginx` evaluara primero trafico administrativo, despues dominio `archive`, despues path anual y por ultimo `live`.
- El contrato FastCGI debe preservar host, URI y `docroot` del backend seleccionado.
- La regla anual existe solo en `LB-Nginx`, no dentro de WordPress.
- `BE-Admin` no contiene logica de seleccion: sirve `live` o `archive` segun el `docroot` administrativo recibido.
- El modelo administrativo de la POC es un contenedor `BE-Admin` con dos `docroot`: `admin-live` y `admin-archive`.

## Shortcuts aceptados para la POC
- Sin alta disponibilidad.
- Sin backups.
- Sin storage distribuido: bind mount compartido.
- Sin cache de produccion ni optimizacion para trafico alto.
- Sin clustering de Elasticsearch.
- Sin modelado de capacidad para carga real.

## Riesgos aceptados
- Punto unico de fallo en `LB-Nginx`.
- Punto unico de fallo en `Elastic`.
- Dependencia de almacenamiento compartido simple.
- Falta de validacion de restauracion al no haber backups.
- Topologia valida para demo o validacion tecnica, no para produccion.

## Propuesta de monitorizacion
- Vigilar disponibilidad y errores de `LB-Nginx`.
- Vigilar `php-fpm` en `FE-Live`, `FE-Archive` y `BE-Admin`.
- Vigilar disponibilidad, conexiones y slow queries en ambas DB.
- Vigilar disponibilidad de `Elastic`.
- Vigilar exito/fallo de tareas en `Cron-Master`.
- Alertar antes de reiniciar automaticamente por uso alto de RAM.

## Lecciones aprendidas
- El documento original mezclaba conceptos de produccion con una POC.
- Era imprescindible separar decisiones funcionales de objetivos futuros.
- La topologia solo se entiende bien cuando el enrutado por dominio, URL y backend administrativo queda explicitado.
- Sin una matriz de prioridad del balanceador, el diseno queda ambiguo y no es implementable.
- El backend administrativo debe ser pasivo: toda la decision pertenece al balanceador y al `docroot` enviado por FastCGI.
- La POC ya necesita una configuracion concreta del balanceador; el pseudocodigo deja demasiados huecos en un sistema con `live`, `archive` y `admin`.
- Para que `LB-Nginx` pueda servir estaticos y resolver `SCRIPT_FILENAME`, el layout de mounts y `docroot` tiene que definirse antes de tocar WordPress.
- El backend administrativo queda mucho mas limpio cuando cada contexto tiene su propio `wp-config.php` y el contenedor no hace autodeteccion.
- Reservar `archive.nuevecuatrouno.com` solo para el admin elimina la ambigüedad de host canonico en el frontend historico.

## Siguiente fase propuesta
- Definir observabilidad, healthchecks y logs minimos.
- Definir pruebas de humo de routing y disponibilidad.
- Traducir lo ya acordado a chequeos operativos.

## Plan operativo
- Plan detallado en `tasks/infra-poc-wordpress-plan.md`.
- La `Fase 1` queda cerrada con `docs/lb-nginx-routing.md`.
- La `Fase 2` queda cerrada con `docs/docker-layout.md`.
- La `Fase 3` queda cerrada con `docs/wordpress-contexts.md`.
- La siguiente fase activa es observabilidad y operacion.
