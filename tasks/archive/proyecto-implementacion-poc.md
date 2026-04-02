# Proyecto: Implementacion POC WordPress Docker

## 1. Objetivo
Materializar la POC descrita en `docs/project.md` mediante infraestructura versionada, configuracion reproducible y verificaciones operativas minimas, dejando la topologia `live`, `archive` y `admin` desplegable, comprobable y lista para evolucion posterior.

## 2. Relacion con la documentacion base
- Documento de arquitectura: `docs/project.md`
- Este proyecto convierte la POC documental en artefactos ejecutables.
- La capa IA-Ops queda diferida como trabajo posterior de observabilidad avanzada y auditoria asistida.

## 3. Alcance acordado
- Traducir la arquitectura documentada a `docker compose` y configuracion versionada.
- Implementar `LB-Nginx`, `FE-Live`, `FE-Archive`, `BE-Admin`, `DB-Live`, `DB-Archive`, `Elastic` y `Cron-Master`.
- Materializar el contrato FastCGI, el layout de mounts y los `docroot` definidos.
- Definir `wp-config.php` por contexto mediante plantillas o convenciones reproducibles.
- Implementar healthchecks, smoke tests y validacion minima post-despliegue.
- Documentar secretos, despliegue, rollback y operaciones minimas de la POC.

## 4. Fuera de alcance
- Alta disponibilidad real.
- Backups productivos verificados.
- Cache de produccion y tuning para trafico alto.
- Integracion completa de IA-Ops o remediacion automatica.
- Pipeline CI/CD completa de produccion, salvo base minima necesaria para validar configuraciones.

## 5. Principios DevOps del proyecto
- Infraestructura como codigo, sin pasos manuales opacos.
- Configuracion versionada para `docker compose`, Nginx, PHP-FPM y servicios auxiliares.
- Healthchecks y verificaciones desde el primer despliegue.
- Secretos fuera del repositorio y separados de configuracion no sensible.
- Todo cambio debe tener rollback definido.
- La POC debe ser reproducible en un entorno de staging o laboratorio.

## 6. Artefactos objetivo
- `compose.yaml` o `docker-compose.yml`
- `.env.example` solo para variables no sensibles o nombres de recursos
- `nginx/` con configuracion de `LB-Nginx`
- `php/` con configuraciones de `php-fpm` necesarias
- `wordpress/` o estructura equivalente para plantillas de `wp-config.php`
- `scripts/` para smoke tests y validaciones
- `docs/` actualizada con operacion minima y despliegue

## 7. Fases

### Fase 1. Base reproducible e inventario tecnico
#### Estado
Completada

#### Objetivo
Preparar la estructura del repositorio y fijar los artefactos base para desplegar la POC sin ambiguedad.

#### Tareas
- Definir layout de directorios del repo para configuracion e infraestructura.
- Elegir formato final de `compose`.
- Mapear cada componente documentado a un servicio de contenedor.
- Definir inventario de bind mounts, redes, nombres DNS y volumenes.
- Separar configuracion no sensible de secretos.

#### Entregables
- Estructura inicial del repo.
- Inventario tecnico de servicios y mounts.
- Convencion de variables y ficheros de configuracion.

#### Criterios de cierre
- El repo ya refleja la topologia de la POC.
- Cada servicio tiene responsabilidad y artefacto asociado.
- No hay ambiguedad sobre que configuracion va en repo y que secreto va fuera.

#### Decisiones tomadas
- Se adopta `compose.yaml` como formato base del stack.
- La configuracion versionada se organiza en `nginx/`, `php/`, `wordpress/`, `scripts/` y `config/`.
- Los bind mounts locales de laboratorio se referencian por variables no sensibles y apuntan por defecto a `./runtime/...`.
- Los secretos no viven en `.env`; se inyectaran externamente o por ficheros no versionados.

#### Entregables ejecutados
- Estructura inicial del repo creada.
- `compose.yaml` base del stack creado.
- `.env.example` con variables no sensibles creado.
- Inventario tecnico en `tasks/archive/docs/poc-implementation-inventory.md`.

#### Lecciones aprendidas
- Conviene separar desde el inicio el layout de laboratorio del layout objetivo en host para no contaminar la configuracion con rutas absolutas.
- El inventario tecnico debe existir antes de escribir Nginx o `php-fpm` para evitar contradicciones entre mounts y docroots.

### Fase 2. Balanceador y contrato FastCGI
#### Estado
Completada

#### Objetivo
Implementar `LB-Nginx` conforme al routing y contrato FastCGI definidos en la documentacion.

#### Tareas
- Crear configuracion de `upstreams`.
- Implementar `map` de host, path y admin.
- Resolver `fastcgi_pass`, `DOCUMENT_ROOT` y `SCRIPT_FILENAME`.
- Configurar `archive.nuevecuatrouno.test` con redireccion o bloqueo no admin.
- Añadir `access_log`, `error_log` y endpoint `/healthz`.

#### Entregables
- Configuracion Nginx versionada.
- Validacion estatica de sintaxis.
- Tabla de casos de prueba asociados al routing.

#### Criterios de cierre
- El routing coincide con `docs/project.md`.
- La configuracion carga sin errores.
- Los casos `live`, `archive` y `admin` quedan trazables.

#### Progreso actual
- Estructura de `LB-Nginx` creada en `nginx/lb/`.
- Configuracion final de routing, sintaxis y casos de prueba asociados cerrados.

#### Decisiones tomadas
- El host `archive.nuevecuatrouno.test` no administrativo redirige por `302` a `http://nuevecuatrouno.test$request_uri` en esta POC.
- Se registra `php_upstream` y `site_context` en `access_log` para facilitar el diagnostico de routing.
- El `healthcheck` del balanceador se expone como `GET /healthz` con respuesta `200 ok`.

#### Entregables ejecutados
- Configuracion de routing en `nginx/lb/conf.d/poc-routing.conf`.
- Casos de prueba documentados en `tasks/archive/docs/poc-lb-routing-test-cases.md`.
- Validacion estatica de `compose`.
- Validacion de sintaxis `nginx -t` ejecutada con `nginx:1.27.5-alpine`.

#### Lecciones aprendidas
- Merece la pena validar Nginx contra la misma imagen que usara la POC para evitar diferencias del host.
- Los valores por defecto en `compose.yaml` son necesarios para que la validacion estatica no dependa de un `.env` local.
- En Docker Compose, Nginx debe resolver dinamicamente los upstreams o quedara apuntando a IPs antiguas tras recrear contenedores PHP.

### Fase 3. Servicios de aplicacion y datos
#### Estado
Completada

#### Objetivo
Definir e integrar los servicios PHP, MySQL, Elastic y `Cron-Master` dentro de `compose`.

#### Tareas
- Definir imagenes base con versiones fijadas.
- Configurar `FE-Live`, `FE-Archive` y `BE-Admin` con `php-fpm`.
- Configurar `DB-Live`, `DB-Archive`, `Elastic` y `Cron-Master`.
- Declarar healthchecks por servicio.
- Declarar limites basicos de recursos y dependencias entre servicios.

#### Entregables
- Archivo `compose` funcional.
- Configuracion `php-fpm` minima.
- Definicion de redes, volumenes y healthchecks.

#### Criterios de cierre
- Todos los servicios arrancan bajo `compose`.
- Los healthchecks reflejan lo documentado.
- Las dependencias minimizan arranques opacos o carreras triviales.

#### Progreso actual
- Servicios definidos en `compose.yaml`.
- Healthchecks base anadidos para `LB-Nginx`, `php-fpm`, MySQL, Elastic y `Cron-Master`.
- Configuracion minima de salud para `php-fpm` creada en `php/common/zz-health.conf`.
- Secretos locales no versionados previstos para inicializar MySQL en la POC local.
- Scripts iniciales de bootstrap local y smoke routing preparados para validar el stack sin WordPress real.

#### Decisiones tomadas
- MySQL se inicializa en local mediante secretos Compose montados desde `./.secrets/`, no desde variables inline ni `.env`.
- Los healthchecks de `php-fpm` verifican disponibilidad del puerto `9000` para evitar dependencias a utilidades ausentes.
- `LB-Nginx` usa resolucion dinamica de upstreams en el DNS interno de Docker para tolerar recreaciones de contenedores.

#### Entregables ejecutados
- `compose.yaml` operativo con servicios `LB-Nginx`, `FE-Live`, `FE-Archive`, `BE-Admin`, `DB-Live`, `DB-Archive`, `Elastic` y `Cron-Master`.
- Healthchecks base materializados.
- `scripts/bootstrap-local-runtime.sh` y `scripts/smoke-routing.sh`.
- Stack base levantado y smoke tests de routing validados contra stubs PHP.

#### Lecciones aprendidas
- Sin secretos iniciales de MySQL, la POC no llega siquiera a fase de routing útil.
- Los upstreams FastCGI en Docker no deben depender de resolucion estatica si el ciclo normal incluye recrear contenedores.

### Fase 4. WordPress por contexto
#### Estado
Completada

#### Objetivo
Materializar los `docroot`, mounts y configuraciones WordPress de `live`, `archive`, `admin-live` y `admin-archive`.

#### Tareas
- Definir estructura de directorios alineada con `/tank/data/wp-root`.
- Preparar plantillas o convenciones para `wp-config.php`.
- Configurar contexto `live` y `archive` con sus DB correspondientes.
- Configurar `BE-Admin` como backend pasivo con dos `docroot`.
- Definir politica inicial para `shared/config`, `uploads` y `mu-plugins`.

#### Entregables
- Layout de mounts y docroots materializado.
- Plantillas de configuracion WordPress.
- Documentacion de diferencias entre contextos.

#### Criterios de cierre
- Cada contexto tiene un `docroot` inequívoco.
- Las configuraciones por contexto son reproducibles.
- `BE-Admin` no introduce logica propia de particion.

#### Progreso actual
- Plantillas `wp-config.php` por contexto y configuracion compartida materializadas.
- Bootstrap reproducible del layout WordPress local sobre `./runtime/wp-root/` operativo.

#### Decisiones tomadas
- `wp-config.php` lee secretos primero desde variables de entorno y, para la POC local, desde ficheros montados bajo `/run/project-secrets`.
- La configuracion comun de WordPress vive en `runtime/wp-root/shared/config/wp-common.php`.
- El layout local sigue usando stubs PHP de validacion hasta introducir el core real de WordPress.

#### Entregables ejecutados
- Plantillas en `wordpress/templates/wp-config.php.tpl` y `wordpress/templates/wp-common.php.tpl`.
- Convencion de contextos en `wordpress/contexts.env.example`.
- Scripts `bootstrap-local-secrets.sh`, `bootstrap-wordpress-config.sh` y `bootstrap-wordpress-stubs.sh`.
- `wp-config.php` generado para `live`, `archive`, `admin-live` y `admin-archive`.

#### Lecciones aprendidas
- Para una POC local es mas robusto leer secretos WordPress desde ficheros montados que depender de variables inline.
- El bootstrap de contexto debe generar tanto configuracion como stubs minimales para poder validar routing antes de introducir el core real.

### Fase 5. Verificacion operativa
#### Estado
Completada

#### Objetivo
Comprobar que la POC arranca, enruta y responde segun el contrato definido.

#### Tareas
- Implementar smoke tests de routing y salud.
- Verificar `/healthz`, `ping` de `php-fpm`, MySQL y Elastic.
- Validar rutas `wp-admin`, `archive` por anio y frontend `live`.
- Documentar procedimiento de despliegue local o staging.
- Documentar rollback al ultimo estado estable del repo y configuracion.

#### Entregables
- Scripts de smoke tests.
- Guia de despliegue y validacion.
- Procedimiento de rollback.

#### Criterios de cierre
- La POC pasa la bateria minima de pruebas documentada.
- Un tercero puede desplegar y verificar el stack.
- El rollback esta descrito de forma concreta.

#### Decisiones tomadas
- La verificacion se divide en dos planos: `smoke-routing.sh` para contrato HTTP y `smoke-services.sh` para estado interno de contenedores.
- El rollback local se documenta como rollback de Git mas recreacion de Compose, sin automatismos destructivos.

#### Entregables ejecutados
- Runbook local en `docs/poc-local-runbook.md`.
- Checklist de validacion en `tasks/archive/docs/poc-validation-checklist.md`.
- Script `scripts/smoke-services.sh`.
- Bateria de smoke tests HTTP y de servicios validada sobre el stack actual.

#### Lecciones aprendidas
- Separar pruebas HTTP de pruebas internas simplifica el diagnostico cuando falla una capa concreta.
- En una POC Docker, un rollback util es casi siempre recrear stack y runtime desde Git y bootstrap local.

### Fase 6. Hardening minimo y evolucion
#### Estado
Completada

#### Objetivo
Cerrar los huecos minimos para que la POC sea operable y pueda servir de base a la siguiente iteracion.

#### Tareas
- Documentar gestion de secretos fuera del repo.
- Endurecer superficie basica de admin segun el alcance de la POC.
- Definir rotacion minima de logs.
- Identificar huecos pendientes hacia produccion.
- Delimitar la siguiente iteracion, incluyendo `IA-Ops Bootstrap`.

#### Entregables
- Documento de operacion minima.
- Checklist de pendientes post-POC.
- Decision documentada sobre siguiente proyecto.

#### Criterios de cierre
- La POC queda demostrable y mantenible.
- Los huecos a produccion estan explicitados.
- La siguiente iteracion queda priorizada sin ambiguedad.

#### Decisiones tomadas
- El hardening de esta fase se limita a defensa basica de superficie, sin introducir politicas que distorsionen la POC ni aparenten seguridad de produccion.
- La rotacion de logs se resuelve en Docker Compose para cubrir el laboratorio sin depender del host.
- `./.secrets/` sigue siendo aceptable solo como mecanismo local de laboratorio; la siguiente iteracion debera mover secretos a un backend dedicado.

#### Entregables ejecutados
- Hardening basico de `LB-Nginx` aplicado.
- Rotacion minima de logs y `no-new-privileges` materializados en `compose.yaml`.
- Documento `tasks/archive/docs/poc-hardening-and-next-steps.md` con secretos, gaps y siguiente proyecto recomendado.

#### Lecciones aprendidas
- Para una POC util, endurecer lo obvio aporta mas valor que simular controles avanzados sin soporte operativo real.
- Si no se documenta de forma explicita la frontera entre laboratorio y produccion, la POC invita a decisiones equivocadas en fases posteriores.

## 8. Riesgos conocidos
- La POC depende de bind mounts y de un layout local aun no materializado.
- `archive.nuevecuatrouno.test` y el routing admin pueden generar errores sutiles si no se validan con smoke tests reales.
- Elasticsearch debe degradar sin tumbar el stack, pero eso requiere comprobacion practica.
- La ausencia de backups y HA sigue siendo un riesgo aceptado de la POC.

## 9. Criterio de exito del proyecto
El proyecto se considerara completado cuando la topologia de `docs/project.md` pueda levantarse con configuracion versionada, pasar healthchecks y smoke tests basicos, y quedar documentada con un procedimiento de despliegue y rollback reproducible.

## 10. Seguimiento y lecciones aprendidas
Este fichero se ira actualizando al cierre de cada fase con:
- decisiones tomadas
- cambios de alcance
- lecciones aprendidas
- bloqueos y riesgos nuevos
