# Plan de tareas: Infra POC WordPress

## Objetivo
Desglosar la POC en fases ejecutables, con dependencias claras y criterios de cierre, para poder avanzar sin ambiguedad y dejar una ruta limpia hacia una version mas seria.

## Relacion con la documentacion
- Documento de arquitectura: `docs/project.md`
- Documento de proyecto: `proyecto-infra-poc-wordpress.md`

## Estado global
- Fase actual recomendada: `Fase 3`
- Prioridad actual: cerrar configuracion WordPress por contexto

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
- `archive.nuevecuatrouno.com/...`

### Riesgos a vigilar
- Resolver mal la prioridad de reglas.
- Mezclar `docroot` administrativo `live` y `archive`.
- Dejar WordPress dependiente de variables no preservadas por FastCGI.

### Entregables
- `docs/lb-nginx-routing.md`
- `tasks/fase-1-lb-nginx-routing.md`

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
- `docs/docker-layout.md`
- `tasks/fase-2-docker-layout.md`

## Fase 3. Configuracion WordPress
### Objetivo
Dejar definidas las instancias WordPress de `live`, `archive` y admin.

### Estado
- Siguiente fase activa

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

## Fase 4. Observabilidad y operacion
### Objetivo
Dejar la POC operable y diagnosticable.

### Tareas
- Definir healthchecks por contenedor.
- Definir logs minimos por servicio.
- Definir metricas minimas para `php-fpm`, MySQL y Nginx.
- Definir alarmas `warning` y `critical`.
- Definir pruebas de humo de routing y disponibilidad.

### Criterios de cierre
- Existe una lista minima de chequeos operativos.
- Los fallos de routing y de servicios se pueden detectar rapido.

## Fase 5. Criterios de paso a produccion
### Objetivo
Documentar que faltaria para considerar este diseno apto para un entorno serio.

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

## Dependencias entre fases
- `Fase 1` depende de `Fase 0`.
- `Fase 2` depende de `Fase 1`.
- `Fase 3` depende de `Fase 2`.
- `Fase 4` depende de `Fase 1`, `Fase 2` y `Fase 3`.
- `Fase 5` depende de todas las anteriores.

## Siguiente accion recomendada
- Ejecutar `Fase 3`: definir configuracion WordPress por contexto.
