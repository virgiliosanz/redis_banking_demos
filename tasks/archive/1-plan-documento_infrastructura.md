# Plan de tareas: Infra POC WordPress

## Objetivo
Desglosar la POC en fases ejecutables, con dependencias claras y criterios de cierre, para poder avanzar sin ambiguedad y dejar una ruta limpia hacia una version mas seria.

## Relacion con la documentacion
- Documento de arquitectura: `docs/project.md`
- Documento de proyecto: integrado en `docs/project.md`

## Estado global
- Fase actual recomendada: cierre documental completado
- Prioridad actual: decidir siguiente iteracion tecnica

## Fase 0. Base documental
### Objetivo
Dejar cerrada la POC a nivel de arquitectura funcional.

### Estado
- Completada

### Entregables
- Arquitectura POC documentada
- Reglas de routing documentadas
- Contrato FastCGI documentado
- Riesgos y shortcuts documentados

### Lecciones
- La regla de particion pertenece al balanceador, no a WordPress.
- `BE-Admin` debe ser un backend pasivo con dos `docroot`.

## Fase 1. Balanceador y routing
### Objetivo
Definir la configuracion concreta de `LB-Nginx` para que el sistema enrute correctamente `live`, `archive` y `admin`.

### Estado
- Completada

### Tareas
- Definir upstreams `fe_live`, `fe_archive` y `be_admin`.
- Definir mapas de routing por host y por path.
- Definir prioridad exacta entre trafico administrativo, dominio `archive` y regla anual.
- Definir variables FastCGI obligatorias.
- Definir como se asigna `DOCUMENT_ROOT` y `SCRIPT_FILENAME` para cada backend.
- Definir estrategia de logs del balanceador.

### Criterios de cierre
- Existe una configuracion de `LB-Nginx` concreta, no pseudocodigo.
- Se pueden probar al menos estos casos:
- `nuevecuatrouno.com/wp-admin`
- `archive.nuevecuatrouno.com/wp-admin`
- `nuevecuatrouno.com/2019/...`
- `nuevecuatrouno.com/actualidad/...`
- `archive.nuevecuatrouno.com/...` no admin

### Riesgos a vigilar
- Resolver mal la prioridad de reglas.
- Mezclar `docroot` administrativo `live` y `archive`.
- Dejar WordPress dependiente de variables no preservadas por FastCGI.

### Entregables
- Integrado en `docs/project.md`

### Checklist de cierre ejecutado
- [x] Upstreams `fe_live`, `fe_archive` y `be_admin`
- [x] Routing por dominio `archive`
- [x] Routing por path anual `2015-2023`
- [x] Routing administrativo a `BE-Admin`
- [x] Seleccion de `docroot` por contexto
- [x] Variables FastCGI obligatorias
- [x] Casos minimos de prueba definidos

### Decisiones tomadas
- Toda la logica de particion vive en `LB-Nginx`.
- `BE-Admin` es pasivo y depende del `docroot` recibido.
- En la POC todo `/wp-json/` se considera administrativo para no romper el admin de WordPress.
- `LB-Nginx` debe ver el mismo arbol de contenido para servir estaticos y resolver scripts.

### Riesgos pendientes
- La regla global de `/wp-json/` puede ser demasiado amplia si luego hay REST publico intensivo.
- El diseno sigue dependiendo de bind mounts compartidos.
- Aun no esta traducido a `docker compose` ni a archivos de despliegue reales.

## Fase 2. Layout de contenedores y docroots
### Objetivo
Definir el layout real de los contenedores PHP y sus `docroot`.

### Estado
- Completada

### Tareas
- Confirmar estructura fisica de `live`, `archive`, `admin-live` y `admin-archive`.
- Definir bind mounts necesarios.
- Definir permisos de `www-data`.
- Definir estructura de logs por servicio.
- Definir nombres DNS internos de Docker.

### Criterios de cierre
- El layout de directorios esta documentado.
- Cada backend tiene un `docroot` inequívoco.
- La relacion entre contenedor, `docroot` y base de datos queda cerrada.

### Entregables
- Integrado en `docs/project.md`

### Checklist de cierre ejecutado
- [x] Red Docker definida
- [x] Hostnames internos definidos
- [x] Servicios de la POC definidos
- [x] Estructura base en host definida
- [x] Bind mounts por servicio definidos
- [x] `docroot` de `live`, `archive`, `admin-live` y `admin-archive`
- [x] Permisos base de `www-data`
- [x] Directorios de logs recomendados

### Decisiones tomadas
- Se mantiene una sola red Docker `bridge` para la POC.
- `LB-Nginx` monta el contenido en solo lectura.
- `fe-live`, `fe-archive` y `be-admin` son contenedores separados.
- `be-admin` usa dos `docroot`.
- Se aceptan bind mounts directos en host por simplicidad.

### Riesgos pendientes
- El layout aun no define secretos ni variables de entorno.
- La comparticion de `uploads` y `mu-plugins` queda como opcion, no como decision cerrada.
- Sigue faltando traducir esto a `docker compose`.

## Fase 3. Configuracion WordPress
### Objetivo
Dejar definidas las instancias WordPress de `live`, `archive` y admin.

### Estado
- Completada

### Tareas
- Definir configuracion de conexion a `DB-Live`.
- Definir configuracion de conexion a `DB-Archive`.
- Definir como `BE-Admin` carga el contexto `live` o `archive`.
- Definir parametros comunes y parametros especificos de cada instancia.
- Definir comportamiento cuando `Elastic` no este disponible.

### Criterios de cierre
- Cada contexto WordPress tiene configuracion documentada.
- No existe logica de particion anual dentro de WordPress.
- El admin funciona para `live` y `archive` solo por `host` y `docroot`.

### Entregables
- Integrado en `docs/project.md`

### Checklist de cierre ejecutado
- [x] `live` definido contra `db-live`
- [x] `archive` definido contra `db-archive`
- [x] `admin-live` definido contra `db-live`
- [x] `admin-archive` definido contra `db-archive`
- [x] Sin logica de particion anual dentro de WordPress
- [x] `BE-Admin` resuelto por `docroot`
- [x] Secretos fuera del repositorio
- [x] `cron-master` con `path` explicito por contexto

### Decisiones tomadas
- Cada contexto tiene su propio `wp-config.php`.
- Se admite un fichero comun compartido para ajustes no contextuales.
- `BE-Admin` no hace autodeteccion del sitio.
- `Elastic` no puede romper el bootstrap completo del sitio.

### Riesgos pendientes
- Aun no existe plantilla real de `docker compose` para inyectar estas variables.
- Falta decidir si `uploads` sera compartido o separado entre contextos.
- Faltan secretos y valores reales de despliegue.

## Fase 4. Observabilidad y operacion
### Objetivo
Dejar la POC operable y diagnosticable.

### Estado
- Completada

### Tareas
- Definir healthchecks por contenedor.
- Definir logs minimos por servicio.
- Definir metricas minimas para `php-fpm`, MySQL y Nginx.
- Definir alarmas `warning` y `critical`.
- Definir pruebas de humo de routing y disponibilidad.

### Criterios de cierre
- Existe una lista minima de chequeos operativos.
- Los fallos de routing y de servicios se pueden detectar rapido.

### Entregables
- Integrado en `docs/project.md`

### Checklist de cierre ejecutado
- [x] `LB-Nginx` con `/healthz`
- [x] `php-fpm` con `ping` y `status`
- [x] MySQL con `mysqladmin ping`
- [x] Elastic con `_cluster/health`
- [x] `cron-master` con criterio de ultima ejecucion
- [x] logs minimos por servicio
- [x] bateria minima de smoke tests

### Decisiones tomadas
- La POC no monta una plataforma completa de observabilidad.
- Se priorizan checks que detecten rapido caidas y routing roto.
- Elastic puede degradar funciones, pero no deberia tumbar el sitio entero.
- Los reinicios automaticos no sustituyen al diagnostico.

### Riesgos pendientes
- Aun no existe implementacion real de healthchecks en `docker compose`.
- Aun no existe formato final de logs ni rotacion configurada.
- Faltan scripts reales de smoke tests.

## Fase 5. Criterios de paso a produccion
### Objetivo
Documentar que faltaria para considerar este diseno apto para un entorno serio.

### Estado
- Completada

### Tareas
- Definir estrategia de backups y restore.
- Definir secretos y gestion de credenciales.
- Definir cache y rendimiento real.
- Definir endurecimiento de `wp-admin`.
- Definir HA o, al menos, estrategia de recuperacion.
- Definir pipeline de despliegue reproducible.

### Criterios de cierre
- Existe un checklist de huecos entre POC y produccion.
- Cada shortcut de la POC tiene su reemplazo propuesto.

### Entregables
- Integrado en `docs/project.md`

### Checklist de cierre ejecutado
- [x] Backups y restore definidos como gap
- [x] Secretos y seguridad definidos como gap
- [x] Cache y rendimiento definidos como gap
- [x] HA y recuperacion definidos como gap
- [x] Despliegue reproducible definido como gap
- [x] Observabilidad real definida como gap
- [x] Checklist de promocion cerrado

### Decisiones tomadas
- La POC no pasa a produccion por inercia.
- El mayor trabajo pendiente es operativo, no conceptual.
- Los shortcuts de la POC ya tienen reemplazo objetivo o tratamiento propuesto.

### Riesgos pendientes
- Ninguno nuevo de arquitectura.
- Quedan pendientes todas las implementaciones reales de produccion.

## Dependencias entre fases
- `Fase 1` depende de `Fase 0`.
- `Fase 2` depende de `Fase 1`.
- `Fase 3` depende de `Fase 2`.
- `Fase 4` depende de `Fase 1`, `Fase 2` y `Fase 3`.
- `Fase 5` depende de todas las anteriores.

## Siguiente accion recomendada
- Cerrar el proyecto documental actual y decidir si la siguiente iteracion sera implementacion o plantillas de despliegue.
