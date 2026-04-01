# Proyecto: docs, contenido semilla, buscador y estabilidad admin

## 1. Objetivo
Mejorar la capacidad de prueba manual de la POC antes del siguiente proyecto mayor, dejando actualizada la documentacion operativa, cargando contenido inicial mas realista para `live` y `archive`, exponiendo un buscador visible en frontend y corrigiendo el comportamiento incorrecto de redirecciones en `/wp-admin`.

## 2. Relacion con la documentacion base
- Documento de arquitectura vigente: `docs/project.md`
- Proyecto previo completado: `projects/proyecto-wordpress-real-cache-y-busqueda.md`
- Este proyecto arranca sobre una POC funcional con WordPress real, politica de cache, busqueda ElasticPress y validacion funcional consolidada.

## 3. Decisiones ya acordadas
- Antes del rollover anual y del bootstrap IA-Ops conviene mejorar la experiencia de prueba manual de la POC.
- La documentacion en `docs/` debe reflejar no solo la arquitectura, sino tambien el uso operativo local y las credenciales de laboratorio.
- La POC necesita mas contenido realista para probar routing por anio, cache y busqueda sin depender de dos URLs de ejemplo.
- Debe existir un buscador visible en frontend para validar manualmente la busqueda sobre `live` y `archive`.
- El bug de redirecciones `302` infinitas en `/wp-admin` tiene prioridad funcional y debe corregirse antes de seguir.

## 4. Alcance acordado
- Revisar y ampliar la documentacion practica en `docs/`.
- Generar una carga inicial reproducible de posts y paginas para `live` y `archive`.
- Exponer un buscador visible y minimo en frontend sobre ambos contextos publicos.
- Diagnosticar y corregir los bucles de redireccion en `wp-admin` y `wp-login.php`.
- Asegurar que los cambios siguen siendo compatibles con la particion `live/archive` y con ElasticPress.

## 5. Fuera de alcance
- Migracion de contenido real desde produccion.
- Diseno final de tema o maquetacion editorial definitiva.
- Script anual de rollover `live -> archive`.
- Automatizacion IA-Ops sobre senales reales.
- Endurecimiento adicional de produccion mas alla de lo ya documentado.

## 6. Principios de trabajo
- La semilla de contenido debe ser reproducible, idempotente y facil de regenerar.
- El buscador visible debe ser minimo y funcional; no se justifica todavia un tema complejo.
- Los fixes en admin deben apoyarse en causa raiz, no en parches opacos de redirects.
- Toda correccion debe validarse con smoke tests y con prueba manual sobre navegador.

## 7. Fases

### Fase 1. Documentacion operativa de uso local
#### Estado
Completada

#### Objetivo
Actualizar `docs/` para reflejar el uso real de la POC como entorno de laboratorio.

#### Tareas
- Documentar URLs, credenciales admin y scripts de bootstrap/validacion.
- Explicar como probar manualmente routing, admin y busqueda.
- Aclarar que contenido existe de serie y como regenerarlo.

#### Entregables
- Actualizacion de `docs/poc-local-runbook.md`.
- Actualizacion de `docs/project.md` en la parte practica si hiciera falta.
- Documento o seccion de referencia rapida para credenciales y pruebas manuales.

#### Criterios de cierre
- Un tercero puede levantar la POC y probarla manualmente solo con `docs/`.

#### Progreso actual
- `docs/poc-local-runbook.md` ya incorpora accesos admin, contenido inicial y pruebas manuales relevantes.
- Se crea una referencia rapida en `docs/poc-manual-testing-reference.md` para URLs, credenciales y checks de laboratorio.
- La documentacion ya recoge el flujo correcto de admin tras corregir el bug de redirects en `/wp-admin/`.

#### Decisiones tomadas
- La referencia operativa corta vive fuera del documento de arquitectura principal para no mezclar diseño con uso diario del laboratorio.
- Las credenciales de admin se documentan como rutas a ficheros de secretos locales, no como valores literales en el repo.

#### Lecciones aprendidas
- Sin una referencia rapida de URLs y credenciales, la POC es util para automatismos pero fricciona mucho en prueba manual.
- El bug de `wp-admin/` habia que corregirlo antes de documentar el flujo admin, porque invalidaba la guia de uso local.

### Fase 2. Carga inicial de contenido semilla
#### Estado
Completada

#### Objetivo
Poblar `live` y `archive` con contenido suficiente para probar routing, anios, taxonomias y busqueda.

#### Tareas
- Definir un dataset reproducible para `live` y `archive`.
- Sembrar posts en varios anios y secciones.
- Incluir casos que permitan validar bien reglas Nginx y resultados de busqueda.

#### Entregables
- Script de seed idempotente.
- Contenido visible y verificable en `live` y `archive`.
- Casos de prueba asociados.

#### Criterios de cierre
- Hay suficientes URLs distintas para comprobar routing por contexto y resultados de busqueda manuales.

#### Progreso actual
- Se crea `scripts/bootstrap-wordpress-seed.sh` como semilla reproducible e idempotente para `live` y `archive`.
- El bootstrap general ya ejecuta la semilla antes de reindexar ElasticPress.
- La bateria de smoke cubre ya rutas adicionales de `live` y `archive`, y terminos de busqueda mas utiles.

#### Decisiones tomadas
- La semilla queda separada de la instalacion base para poder regenerar contenido sin reinstalar WordPress.
- `live` se puebla con paginas jerarquicas y algunos posts; `archive` se puebla con posts fechados en varios anios para validar routing anual.
- Se usan terminos compartidos entre `live` y `archive` para validar busqueda unificada de forma manual y automatizada.
- Se fijan explicitamente las estructuras de permalinks de `live` y `archive` durante la instalacion base.

#### Lecciones aprendidas
- La POC necesitaba un dataset mas rico no por volumen, sino por diversidad de URLs y terminos de busqueda.
- Los permalinks no deben depender del estado previo de la base; conviene fijarlos en el bootstrap.
- Separar seed y install da mas control operativo y evita reinstalaciones innecesarias durante pruebas.

### Fase 3. Buscador visible en frontend
#### Estado
Completada

#### Objetivo
Añadir una UI minima de busqueda en frontend para validar ElasticPress sin depender solo de consultas manuales por query string.

#### Tareas
- Elegir el punto minimo de insercion del formulario de busqueda.
- Mostrar el buscador en `live` y `archive`.
- Validar que consulta sobre el alias unificado y devuelve resultados de ambos contextos cuando proceda.

#### Entregables
- Implementacion minima de buscador visible.
- Ajustes minimos de plantilla o tema necesarios.
- Validacion funcional manual y automatizada.

#### Criterios de cierre
- Un usuario puede lanzar busquedas desde la interfaz sin conocer `/?s=...`.

#### Progreso actual
- Se anade un buscador visible en cabecera como `mu-plugin` compartido.
- El formulario aparece tanto en home como en resultados de busqueda.
- Los resultados mezclados de `live` y `archive` mantienen enlaces canonicos con fecha completa.

#### Decisiones tomadas
- La UI de busqueda se implementa como capa compartida y minima, sin reabrir el frente de tema o maquetacion.
- La correccion de enlaces `?p=<id>` se hace en la capa de render de resultados ElasticPress, reutilizando el permalink indexado.

#### Lecciones aprendidas
- Para esta POC, un `mu-plugin` pequeño es una forma mas estable de introducir UI transversal que tocar el tema de bloque entero.
- El problema de los enlaces de resultados no estaba en Elasticsearch ni en los permalinks del sitio, sino en el render del bloque de resultados mezclados.

### Fase 4. Estabilidad de admin y correccion de redirects
#### Estado
Completada

#### Objetivo
Eliminar los bucles `302` en `wp-admin` y `wp-login.php`, dejando el plano administrativo navegable y estable.

#### Tareas
- Reproducir el bug en `live` y `archive`.
- Aislar si el problema esta en Nginx, `WP_HOME/WP_SITEURL`, cookies, canonical redirects o mezcla de host/contexto.
- Aplicar una correccion explicita y documentada.
- Añadir validacion automatizada para detectar regresiones.

#### Entregables
- Fix de configuracion y/o aplicacion.
- Documentacion de causa raiz y solucion.
- Smoke test o comprobacion reproducible del flujo admin.

#### Criterios de cierre
- `wp-admin` y `wp-login.php` dejan de entrar en loops `302`.
- El acceso admin funciona de forma estable en `nuevecuatrouno.test` y `archive.nuevecuatrouno.test`.

#### Progreso actual
- El loop de `302` en `/wp-admin/` queda corregido en el balanceador.
- `wp-admin/` ya redirige a `wp-login.php` tanto en `live` como en `archive`.

#### Decisiones tomadas
- La correccion se hace en Nginx reescribiendo el caso exacto `/wp-admin/` hacia `wp-admin/index.php`, sin alterar el resto del manejo PHP.

#### Lecciones aprendidas
- El bug no estaba en cookies ni en credenciales, sino en como entraba el URI exacto `/wp-admin/` al front controller administrativo.

## 8. Riesgos a vigilar
- El contenido semilla puede ocultar problemas reales si no cubre suficientes variantes de routing.
- Un buscador visible mal insertado puede acoplar demasiado la POC a un pseudo-tema transitorio.
- Los loops de admin pueden venir de varias capas a la vez; hay que validar con `curl` y navegador, no solo por inspeccion de codigo.

## 9. Criterio de exito global
- La POC queda lista para pruebas manuales realistas de frontend, busqueda y administracion.
- La documentacion local permite repetir el flujo sin conocimiento previo del historial del repo.
- El entorno queda preparado para abordar despues el rollover anual y el bootstrap IA-Ops con menos friccion.
