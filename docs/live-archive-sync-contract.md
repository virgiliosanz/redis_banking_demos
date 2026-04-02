# Contrato de sincronizacion `live <-> archive`

## Objetivo
Definir que debe mantenerse alineado entre `live` y `archive`, con que frecuencia, por que mecanismo y con que exclusiones.

Este contrato existe para evitar mezclar tres problemas distintos:
- rollover anual de contenido
- sincronizacion editorial frecuente
- consistencia de plataforma y presentacion

## 1. Separacion de flujos

### Rollover anual de contenido
- mueve posts publicados del anio cerrado desde `live` a `archive`
- frecuencia anual
- se valida con `dry-run`, `report-only`, informe y rollback

### Sincronizacion editorial
- mantiene alineados usuarios editoriales, roles, capacidades y hashes de password
- frecuencia diaria o bajo demanda tras cambios de usuarios
- no mueve posts ni opciones globales

### Sincronizacion de plataforma
- mantiene coherencia funcional y visual alli donde no debe existir divergencia
- el codigo se alinea por despliegue declarativo
- la configuracion persistida en DB que deba ser comun se sincroniza de forma logica y separada

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

Estas cuentas se tratan como cuentas bootstrap o de emergencia por contexto. No forman parte de la sincronizacion editorial.

### Frecuencia inicial
- diaria mediante job programado
- bajo demanda tras cambios relevantes de usuarios, roles o passwords

### Regla operativa
- `live` es fuente de verdad para usuarios editoriales
- `archive` recibe altas, cambios y bajas logicas desde `live`
- nunca se copia la DB completa para resolver usuarios

### Validaciones minimas
- el numero de usuarios sincronizables coincide entre origen y destino tras la sync
- los hashes de password quedan alineados para usuarios sincronizables
- los roles y capacidades quedan alineados
- las cuentas bootstrap siguen existiendo en su contexto

### Rollback
- export logico de usuarios sincronizables en `archive` antes de aplicar cambios
- informe con altas, cambios y bajas
- posibilidad de reimportar el snapshot previo de usuarios en `archive`

### Primera iteracion de implementacion
- `apply` crea y actualiza usuarios editoriales en `archive`
- las bajas se reportan como `stale_users`, pero no se borran automaticamente todavia
- el borrado de usuarios solo se considerara cuando exista evidencia suficiente y rollback mas fino

## 3. Sincronizacion de plataforma

### Codigo que debe mantenerse por despliegue declarativo
- theme activo
- plugins
- `mu-plugins`
- core WordPress cuando aplique el mismo ciclo de despliegue

Esto no debe resolverse copiando ficheros entre `live` y `archive` en caliente.

### Configuracion persistida en DB que puede requerir sincronizacion logica
- `sidebars_widgets`
- `widget_*`
- `theme_mods_<theme-activo>`
- menus y localizaciones de menu
- opciones compartidas de plugins cuando sean funcionalmente comunes

### Configuracion que no debe sincronizarse ciegamente
- `home`
- `siteurl`
- identidad especifica de contexto
- ajustes que deban divergir explicitamente entre `live` y `archive`
- opciones de plugins con estado efimero, cache o informacion contextual

### Frecuencia inicial
- por despliegue o cambio de plataforma
- bajo demanda cuando se modifiquen widgets, menus o apariencia comun

### Regla operativa
- el codigo es comun por despliegue
- la configuracion de DB comun se replica con allowlist
- toda divergencia permitida debe quedar documentada

### Validaciones minimas
- theme activo alineado
- lista de plugins activos alineada cuando corresponda
- `mu-plugins` compartidos presentes
- widgets y menus comunmente declarados alineados
- diferencias permitidas documentadas

### Rollback
- snapshot previo de opciones sincronizadas en `archive`
- informe de opciones modificadas
- restauracion selectiva de opciones previas

## 4. Dependencia con el rollover anual
- el `execute` del rollover no debe abrirse mientras no exista al menos contrato cerrado de sincronizacion editorial y de plataforma
- el contenido puede moverse bien y aun asi dejar un `archive` incoherente si usuarios, roles o configuracion comun no estan alineados

## 5. Dependencia con IA-Ops
- IA-Ops debe poder detectar drift editorial y drift de plataforma
- el auditor nocturno debe incluir al menos:
  - drift de usuarios editoriales
  - drift de roles/capacidades
  - drift de plugins activos
  - drift de theme y configuracion compartida
