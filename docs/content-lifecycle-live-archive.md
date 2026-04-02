# Ciclo de vida de contenido `live <-> archive`

## Objetivo
Definir en un unico documento como se gobierna el contenido entre `live` y `archive`, incluyendo rollover anual, sincronizacion editorial, sincronizacion de plataforma, validaciones y rollback.

## Modelo operativo
- `archive` contiene anios cerrados
- `live` contiene el anio vigente y el contenido nuevo
- `uploads` sigue compartido; mover contenido no implica mover medios
- el corte anual del balanceador debe evolucionar de forma coherente con el rollover

## Separacion de flujos

### Rollover anual de contenido
- mueve posts publicados del anio cerrado desde `live` a `archive`
- frecuencia anual
- requiere `report-only`, `dry-run`, validacion y rollback

### Sincronizacion editorial
- mantiene alineados usuarios editoriales, roles, capacidades y hashes de password
- frecuencia diaria o bajo demanda
- no mueve posts ni opciones globales

### Sincronizacion de plataforma
- mantiene coherencia funcional y visual donde no debe existir divergencia
- el codigo se alinea por despliegue declarativo
- la configuracion persistida en DB que deba ser comun se sincroniza por allowlist

## 1. Rollover anual

### Regla operativa
El rollover se ejecuta una vez al anio al cerrar el anio natural.

Ejemplo:
- el `1 de enero de 2026` se mueve `2025` desde `live` a `archive`

### Alcance de datos que si se mueven
- posts del tipo `post` en estado `publish`
- seleccion por `post_date` dentro del anio objetivo
- meta necesaria para render, taxonomias y busqueda
- relaciones con categorias y tags usadas por los posts movidos
- referencia a imagen destacada y adjuntos realmente usados por esos posts

### Alcance de datos que no se mueven en la primera implementacion
- `page`
- borradores, revisiones, autosaves o papelera
- usuarios, roles o credenciales
- comentarios
- menus, widgets y opciones globales
- configuracion de plugins o tablas auxiliares no ligadas directamente a los posts movidos

### Invariantes
- la URL canonica publica del post no cambia
- `uploads` no se mueve ni se duplica
- la lectura de busqueda sigue yendo al alias `n9-search-posts`
- el contenido movido deja de existir en `live` solo cuando la validacion en `archive` ya ha pasado
- el corte anual del balanceador debe avanzar en la misma operacion de forma coherente

### Modos de ejecucion

#### `report-only`
- no mueve ni borra datos
- inspecciona el estado actual del anio objetivo
- devuelve informe operativo reutilizable por IA-Ops

#### `dry-run`
- no modifica `live` ni `archive`
- calcula seleccion, conteos, slugs conflictivos y artefactos previstos
- produce informe persistente

#### `execute`
- exporta, importa, valida, reindexa y solo borra en `live` si toda la validacion ha pasado
- deja informe y artefactos de rollback

### Flujo recomendado
1. ejecutar `report-only` o `dry-run`
2. validar que el anio objetivo es cerrado y elegible
3. validar que el anio objetivo coincide con `LIVE_MIN_YEAR` en `routing-cutover.env`
4. exportar snapshot logico del subconjunto objetivo de `live`
5. exportar snapshot logico previo de `archive`
6. importar en `archive`
7. validar conteos, slugs, taxonomias, adjuntos y URLs
8. reindexar `archive`
9. validar busqueda unificada contra `n9-search-posts`
10. avanzar el corte anual de routing y recargar el balanceador
11. borrar en `live` solo si todo lo anterior ha pasado
12. reindexar `live`
13. emitir informe final y artefactos de rollback

### Validaciones obligatorias antes de borrar en `live`
- el anio objetivo no es el anio en curso
- el anio objetivo coincide con `LIVE_MIN_YEAR`
- el numero de posts seleccionados en `live` coincide con los importados en `archive`
- las taxonomias necesarias existen en `archive`
- no hay colisiones de slug sin resolver
- los posts muestreados resuelven su URL canonica desde `archive`
- los adjuntos referenciados siguen disponibles desde `uploads`
- el indice de `archive` se ha actualizado correctamente
- la busqueda unificada devuelve contenido del anio movido
- los smoke tests de routing y busqueda siguen pasando

### Checklist operativa

#### Pre-ejecucion
- confirmar el anio objetivo
- confirmar que el anio objetivo es anterior al anio en curso
- confirmar que `docker compose ps` no muestra contenedores degradados
- confirmar que `db-live`, `db-archive`, `elastic` y `cron-master` estan sanos
- confirmar que la busqueda unificada responde y que el alias `n9-search-posts` existe
- ejecutar `report-only` o `dry-run` y revisar el informe
- ejecutar `./scripts/smoke-rollover-year.sh --year <YYYY> --state pre`

#### Validacion previa al borrado
- coinciden posts seleccionados e importados
- existen taxonomias necesarias en `archive`
- los posts del anio objetivo resuelven su URL canonica desde `archive`
- los adjuntos siguen disponibles desde `uploads`
- el reindexado de `archive` ha finalizado correctamente
- la busqueda unificada devuelve contenido del anio objetivo
- los artefactos de rollback ya estan generados

#### Validacion posterior
- el contenido del anio objetivo ya no aparece en `live`
- el contenido del anio objetivo aparece en `archive`
- la URL publica sigue respondiendo correctamente
- la busqueda unificada sigue devolviendo el contenido movido
- los smoke tests siguen pasando
- ejecutar `./scripts/smoke-rollover-year.sh --year <YYYY> --state post`

### Rollback del rollover

#### Artefactos minimos
- export logico del subconjunto del anio objetivo desde `live`
- export logico previo del estado de `archive`
- snapshot del corte anual de routing antes del cambio
- informe de ejecucion con conteos y IDs afectados

#### Estrategia
1. si falla antes del borrado en `live`, limpiar importacion en `archive`, restaurar `archive` si hace falta y revertir el corte anual
2. si falla despues del borrado en `live`, reimportar el subconjunto exportado, revertir el corte anual y reindexar ambos lados
3. tras cualquier rollback, volver a ejecutar smoke tests de routing y busqueda

## 2. Sincronizacion editorial

### Entidades que si se sincronizan
- `wp_users` para usuarios editoriales
- hash de password
- `user_email`
- `display_name`
- `user_nicename`
- `user_status`
- roles y capacidades efectivas
- metadata editorial necesaria para permisos o presencia en el admin

### Entidades que no se sincronizan
- sesiones
- tokens efimeros
- application passwords
- preferencias cosmeticas del dashboard
- metadata temporal o de plugins no necesaria para acceso/editorial

### Cuentas excluidas por diseno
- `n9liveadmin`
- `n9archiveadmin`

Estas cuentas se consideran bootstrap o emergencia por contexto y no forman parte de la sync editorial.

### Frecuencia inicial
- diaria mediante job programado
- bajo demanda tras cambios relevantes de usuarios, roles o passwords

### Regla operativa
- `live` es la fuente de verdad para usuarios editoriales
- `archive` recibe altas y cambios desde `live`
- no se copia la DB completa para resolver usuarios

### Validaciones minimas
- el numero de usuarios sincronizables coincide entre origen y destino
- los hashes de password quedan alineados
- los roles y capacidades quedan alineados
- las cuentas bootstrap siguen existiendo en su contexto

### Rollback
- export logico de usuarios sincronizables en `archive` antes de aplicar cambios
- informe con altas, cambios y bajas
- posibilidad de reimportar el snapshot previo de usuarios en `archive`

### Primera iteracion implementada
- `apply` crea y actualiza usuarios editoriales en `archive`
- las bajas se reportan como `stale_users`, pero no se borran automaticamente

## 3. Sincronizacion de plataforma

### Codigo que debe mantenerse por despliegue declarativo
- theme activo
- plugins
- `mu-plugins`
- core WordPress cuando aplique el mismo ciclo de despliegue

Esto no debe resolverse copiando ficheros entre `live` y `archive` en caliente.

### Configuracion persistida en DB que puede requerir sync logica
- `sidebars_widgets`
- `widget_*`
- `theme_mods_<theme-activo>`
- menus y localizaciones de menu
- opciones compartidas de plugins cuando sean funcionalmente comunes

### Configuracion que no debe sincronizarse ciegamente
- `home`
- `siteurl`
- identidad especifica de contexto
- ajustes que deban divergir explicitamente
- opciones con estado efimero, cache o informacion contextual

### Frecuencia inicial
- por despliegue o cambio de plataforma
- bajo demanda cuando se modifiquen widgets, menus o apariencia comun

### Regla operativa
- el codigo es comun por despliegue
- la configuracion comun de DB se replica con allowlist
- toda divergencia permitida debe quedar documentada

### Validaciones minimas
- theme activo alineado cuando corresponda
- lista de plugins activos alineada cuando corresponda
- `mu-plugins` compartidos presentes
- widgets y menus declarados como comunes alineados
- diferencias permitidas documentadas

### Rollback
- snapshot previo de opciones sincronizadas en `archive`
- informe de opciones modificadas
- restauracion selectiva de opciones previas

### Primera iteracion implementada
- la sync de plataforma aplica una allowlist corta:
  - `sidebars_widgets`
  - `nav_menu_locations`
  - `theme_mods_<theme-activo>`
- el drift de `active_plugins`, `template` y `stylesheet` se informa, pero no se corrige desde este flujo

## 4. Dependencias cruzadas

### Dependencia con el rollover anual
- el `execute` del rollover no debe abrirse mientras no exista contrato cerrado de sincronizacion editorial y de plataforma

### Dependencia con IA-Ops
- IA-Ops debe poder detectar drift editorial y de plataforma
- el auditor nocturno debe incluir al menos:
  - drift de usuarios editoriales
  - drift de roles y capacidades
  - drift de plugins activos
  - drift de theme y configuracion compartida
