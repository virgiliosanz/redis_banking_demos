# Proyecto: WordPress real, cache y busqueda

## 1. Objetivo
Sustituir los stubs de la POC por WordPress real, materializar la persistencia minima necesaria y dejar definida una politica operativa de cache y busqueda que respete la separacion `live`, `archive` y `admin`.

## 2. Relacion con la documentacion base
- Documento de arquitectura vigente: `docs/project.md`
- Proyecto previo completado: `projects/proyecto-implementacion-poc.md`
- Este proyecto arranca sobre una POC ya desplegable, validada y endurecida a nivel basico.

## 3. Decisiones ya acordadas
- El acceso administrativo en origen se protegera aguas arriba con Cloudflare y reglas de IP.
- `archive` existe para soportar una politica de cache mas agresiva y un perfil de infraestructura distinto al de `live`.
- El trabajo editorial ocurrira casi siempre sobre `live`; `admin-archive` queda como capacidad secundaria.
- Elasticsearch se usara para busqueda sobre contenido `live` y `archive` mediante ElasticPress.
- `uploads` sera compartido entre contextos.
- La cache no sera compartida entre contextos.
- `wp-config.php` seguira siendo propio por contexto.
- El codigo WordPress, plugins y tema deberian mantenerse compartidos salvo necesidad justificada de divergencia.

## 4. Alcance acordado
- Introducir WordPress real en los contextos `live`, `archive`, `admin-live` y `admin-archive`.
- Definir persistencia compartida y persistencia aislada por contexto.
- Preparar politica de cache distinta para `live` y `archive`.
- Integrar Elasticsearch + ElasticPress a nivel de arquitectura y validacion funcional minima.
- Preparar la superficie de origen para convivir con Cloudflare sin dejar bypass evidente.
- Documentar degradacion de busqueda, indexacion y siguientes pasos operativos.

## 5. Fuera de alcance
- Produccion completa o migracion final.
- CDN y reglas Cloudflare desplegadas de verdad sobre el entorno final.
- Tuning fino de rendimiento para carga real.
- Backups productivos completos y restore final.
- IA-Ops operativo sobre señales reales, aunque se dejara preparada la base para ello.

## 6. Principios de arquitectura
- `LB-Nginx` sigue decidiendo el contexto; WordPress no toma decisiones de particion.
- El contenido funcional compartido debe vivir una sola vez; el contenido descartable debe aislarse por contexto.
- `archive` debe poder cachearse de forma mas agresiva sin contaminar a `live`.
- El plano administrativo no se optimiza para throughput, sino para control y seguridad.
- La busqueda debe degradar de forma limpia si Elasticsearch no esta disponible.

## 7. Politica de persistencia

### Compartido
- `shared/uploads/`
- `shared/mu-plugins/`
- `shared/config/` cuando sea estrictamente comun y controlado

### Aislado por contexto
- `live/current/public/`
- `archive/current/public/`
- `admin-live/current/public/`
- `admin-archive/current/public/`
- `live/var/cache/`
- `archive/var/cache/`
- `admin-live/var/cache/`
- `admin-archive/var/cache/`

### Reglas operativas
- `uploads` se trata como dato persistente y entra en la estrategia de backup.
- La cache se trata como descartable y regenerable.
- No se comparte cache de pagina, cache de plugin ni cache temporal de disco entre contextos.
- Si mas adelante se introduce Redis, se separaran prefijos o DB logicas por contexto.

## 8. Fases

### Fase 1. WordPress real por contexto
#### Estado
Completada

#### Objetivo
Sustituir los stubs PHP por una base WordPress real manteniendo el routing ya validado.

#### Tareas
- Decidir estrategia de provision de core WordPress para laboratorio.
- Materializar el layout real en `runtime/wp-root/`.
- Mantener `wp-config.php` independiente por contexto.
- Verificar que `live`, `archive`, `admin-live` y `admin-archive` arrancan con WordPress real.

#### Entregables
- Core WordPress desplegable en local.
- Bootstrap reproducible para levantar WordPress real.
- Validacion funcional minima sobre login, front y admin.

#### Criterios de cierre
- Los cuatro contextos cargan WordPress real.
- El routing existente sigue siendo valido.
- El bootstrap local deja de depender de stubs.

#### Progreso actual
- Detectada la necesidad de imagen PHP propia con extensiones WordPress (`mysqli`, `pdo_mysql`).
- Bootstrap del core oficial y bootstrap de instalacion via `wp-cli` planteados como base reproducible.
- WordPress real instalado en `live` y `archive`, con `admin-live` y `admin-archive` operativos sobre el backend administrativo.
- Smoke tests funcionales ya ejecutandose contra WordPress real, no contra stubs.
- El bootstrap del core queda resincronizable sobre los cuatro contextos sin reparaciones manuales.

#### Decisiones tomadas
- Se construyen imagenes propias para `php-fpm` y `php-cli` en lugar de depender de las imagenes oficiales sin extensiones MySQL.
- La instalacion local se automatiza con `wp-cli` desde `cron-master`.
- En local, `WP_HOME` y `WP_SITEURL` quedan en `http` mientras no exista TLS real terminado.
- `archive` se siembra con posts fechados para validar URLs anuales reales; `live` usa paginas jerarquicas para validar routing no anual.
- El core oficial de WordPress se cachea en `runtime/cache/wordpress` y se sincroniza con `rsync`, preservando `wp-config.php` y `wp-content/uploads`.

#### Lecciones aprendidas
- El salto de stubs a WordPress real expone fallos de routing que con PHP plano quedaban ocultos.
- En `map` de Nginx no debe usarse sintaxis `=...` esperando comportamiento de `location`; eso rompe el matching exacto.
- `try_files` con docroot dinamico y rutas PHP administrativas puede fallar silenciosamente; la validacion explicita del script es mas robusta aqui.
- Los docroots `admin-*` deben tratarse como copias completas del core, no como variaciones parciales.
- Un bootstrap de core reproducible no debe depender de copias ciegas ni de ejecuciones de red indefinidas; conviene cachear el tarball y sincronizar de forma determinista.

### Fase 2. Persistencia y contenido compartido
#### Estado
Completada

#### Objetivo
Definir de forma operativa que se comparte y que no entre `live`, `archive` y `admin`.

#### Tareas
- Reestructurar mounts para `uploads`, `mu-plugins` y cache.
- Documentar politica de escritura.
- Delimitar que puede mutar en `archive` y que debe tratarse como casi inmutable.
- Preparar criterio de backup sobre el contenido persistente.

#### Entregables
- Layout actualizado de mounts y runtime.
- Documento de persistencia y ownership.
- Scripts de bootstrap adaptados al nuevo layout.

#### Criterios de cierre
- `uploads` queda compartido de forma explicita.
- La cache queda aislada por contexto.
- No hay ambiguedad sobre que rutas son persistentes y cuales son descartables.

#### Progreso actual
- `uploads` y `mu-plugins` quedan montados desde `runtime/wp-root/shared/` sobre los cuatro contextos.
- `wp-content/cache` queda montado desde `runtime/wp-root/<context>/var/cache/wp-content` con aislamiento explicito por contexto.
- El bootstrap local ya prepara layout, mounts y un probe de persistencia para validacion rapida.
- La documentacion operativa queda aterrizada en `docs/wordpress-persistence-layout.md`.

#### Decisiones tomadas
- `uploads` se comparte por bind mount directo, no por copia ni por sincronizacion entre docroots.
- `mu-plugins` se trata como codigo comun compartido y controlado.
- La cache de WordPress se aisla por contexto montando `wp-content/cache` a un directorio propio de cada contexto.
- El bootstrap del core preserva `uploads`, `mu-plugins`, `cache`, `plugins`, `themes`, `languages` y `upgrade` para no destruir estado funcional al resincronizar el core.

#### Lecciones aprendidas
- Compartir datos entre contextos en Docker Compose debe resolverse con mounts explicitos; confiar en rutas hermanas fuera del bind mount principal no funciona dentro del contenedor.
- El salto a persistencia compartida ha revelado otra necesidad de routing: las rutas estaticas de `uploads`, `themes`, `plugins` y `wp-includes` deben resolverse antes del fallback a WordPress para no convertir un asset existente en `404` aplicado.

### Fase 3. Cache por contexto
#### Objetivo
Definir una politica de cache coherente con el perfil `live` frente a `archive`.

#### Tareas
- Diseñar headers y bypass de cache para `live`.
- Diseñar estrategia mas agresiva para `archive`.
- Definir tratamiento de admin, login y peticiones autenticadas.
- Identificar si conviene cache de pagina, microcache o solo cache CDN en esta iteracion.

#### Entregables
- Documento de politica de cache por contexto.
- Configuracion inicial aplicable en Nginx y/o WordPress.
- Casos de prueba para validar bypass e invalidacion minima.

#### Criterios de cierre
- `live` y `archive` tienen reglas distintas y justificadas.
- La cache editorial y administrativa no rompe el flujo de trabajo.
- Queda claro que partes dependen de Cloudflare y cuales del origen.

### Fase 4. Busqueda con Elasticsearch y ElasticPress
#### Objetivo
Preparar la busqueda unificada sobre contenido `live` y `archive`.

#### Tareas
- Definir modelo de indices para `live` y `archive`.
- Decidir estrategia de consulta desde ElasticPress.
- Documentar indexacion inicial y reindexacion.
- Definir degradacion funcional cuando Elasticsearch no responda.

#### Entregables
- Decision de arquitectura de indices.
- Configuracion funcional minima de ElasticPress.
- Smoke tests de busqueda y de degradacion.

#### Criterios de cierre
- La busqueda consulta contenido de `live` y `archive`.
- Existe runbook minimo de reindexacion.
- La caida de Elasticsearch no derriba el sitio entero.

### Fase 5. Cloudflare-aware origin y endurecimiento minimo real
#### Objetivo
Preparar el origen para quedar correctamente detras de Cloudflare.

#### Tareas
- Definir politica de acceso origen solo via Cloudflare.
- Revisar cabeceras, `real_ip`, trust boundaries y bypass potenciales.
- Endurecer el acceso administrativo en origen.
- Documentar que controles dependen del edge y cuales deben quedarse tambien en origen.

#### Entregables
- Documento de origen detras de Cloudflare.
- Configuracion minima de Nginx preparada para ese escenario.
- Checklist de controles en edge y origen.

#### Criterios de cierre
- No queda una falsa sensacion de proteccion solo por estar detras de Cloudflare.
- El origen tiene una politica clara y documentada.
- El acceso administrativo queda acotado.

### Fase 6. Validacion funcional y salida a siguiente iteracion
#### Objetivo
Cerrar el proyecto con pruebas funcionales utiles y dejar lista la base para IA-Ops.

#### Tareas
- Extender smoke tests a WordPress real, login, medios y busqueda.
- Validar persistencia de `uploads` y regeneracion de cache.
- Documentar limites pendientes hacia produccion.
- Definir la interfaz minima que necesitara `IA-Ops Bootstrap` para operar sobre logs y señales reales.

#### Entregables
- Scripts de validacion funcional.
- Documentacion de cierre del proyecto.
- Decision documentada del siguiente proyecto.

#### Criterios de cierre
- La arquitectura deja de ser solo de infraestructura y pasa a ser aplicacion real operable.
- Los huecos hacia produccion y hacia IA-Ops quedan explicitados.
- El proyecto deja una base valida para el siguiente salto.

## 9. Riesgos conocidos
- WordPress real introducira problemas que hoy no aparecen con stubs: permisos, writes, plugins, login y sesiones.
- Compartir `uploads` simplifica consistencia, pero obliga a pensar desde ya en backup y ownership.
- La politica de cache puede ocultar errores funcionales si no se valida con casos autenticados y no autenticados.
- ElasticPress puede forzar decisiones de indices y mapping antes de lo previsto.
- Si el origen no se blinda correctamente, Cloudflare no servira como control suficiente.

## 10. Criterio de exito del proyecto
El proyecto se considerara completado cuando el stack actual sirva WordPress real en los cuatro contextos, con persistencia compartida bien delimitada, cache separada por contexto, busqueda soportada sobre `live` y `archive`, y una frontera clara entre origen, edge y siguientes pasos operativos.

## 11. Seguimiento y lecciones aprendidas
Este fichero se actualizara al cierre de cada fase con:
- decisiones tomadas
- cambios de alcance
- lecciones aprendidas
- bloqueos y riesgos nuevos
