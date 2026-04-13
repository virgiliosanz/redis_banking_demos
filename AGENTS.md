# AGENTS.md

## 1. Naturaleza de este fichero
Este fichero es una guia operativa para agentes que trabajen en este repositorio.

No define por si solo el alcance funcional del producto. Su funcion es fijar:
- como trabajar en el repo
- que prioridades tecnicas seguir
- como documentar, validar y cerrar cambios

Precedencia:
- las instrucciones de sistema y del entorno tienen prioridad
- las instrucciones explicitas del usuario tienen prioridad sobre este fichero
- este fichero gobierna el modo normal de trabajo dentro del repositorio

## 2. Proposito del repositorio
Este repositorio contiene el **Redis Banking Workshop Demo**: una aplicacion Spring Boot 3.x + Vanilla JS que presenta 7 casos de uso interactivos de Redis para un workshop dirigido a clientes del sector bancario.

Cada caso de uso tiene:
- un **panel de demo en vivo** donde el usuario interactua y ve resultados en tiempo real
- un **panel de code showcase** con snippets curados de Redis (Java + Redis CLI) con syntax highlighting

Foco principal:
- claridad y impacto visual para audiencia tecnica (arquitectos, desarrolladores)
- buenas practicas de Redis en cada caso de uso
- experiencia de desarrollador fluida (arranque rapido, codigo legible)

## 3. Skills obligatorios
Para cualquier tarea relacionada con Redis (estructuras de datos, Redis Query Engine, busqueda vectorial, patrones de caching, modelado de datos), se debe usar siempre el skill `redis-development`.

Para cualquier tarea relacionada con frontend o UI (colores, tipografia, componentes, dark mode, layout), se debe usar siempre el skill `redis-brand-ui`.

Esto aplica tanto a:
- implementacion
- revisiones
- propuestas de diseno
- validacion visual

Si una tarea mezcla backend y frontend, ambos skills aplican en sus respectivas areas.

Para Java/Spring Boot: seguir convenciones de Spring Boot 3.x y Spring Data Redis con Lettuce.

## 4. Principios de trabajo
- **Demo-first:** priorizar claridad y impacto visual sobre patrones de produccion. Esto es un workshop, no un sistema productivo.
- Cada caso de uso debe ser autocontenido y demostrable de forma independiente.
- Los snippets del code showcase deben ser pedagogicos: claros, bien comentados, mostrando comandos Redis de forma explicita.
- Favorecer simplicidad. Si hay dos formas de hacer algo, elegir la mas facil de entender en una presentacion en vivo.
- Todas las interacciones con Redis deben seguir las buenas practicas del skill `redis-development` (naming de keys, TTL, connection pooling, etc.).
- El frontend debe seguir estrictamente las guias del skill `redis-brand-ui` (colores, tipografia, espaciado, componentes).

## 5. Normas de seguridad
- **Sin credenciales reales en el repo.** Usar datos mock/demo exclusivamente.
- **Sin PII real en datos de ejemplo.** Todos los datos bancarios son ficticios.
- **Docker Compose solo para desarrollo local.** No hay despliegue a produccion.
- **Human-in-the-loop:** no ejecutar acciones destructivas o irreversibles sin confirmacion explicita del usuario.

## 6. Convenciones Java/Spring Boot
- Paquete base: `com.redis.workshop`
  - `controller/` — REST controllers, uno por caso de uso
  - `service/` — Logica de negocio + operaciones Redis, uno por caso de uso
  - `config/` — Configuracion de Redis, data loaders
  - `model/` — DTOs y objetos de dominio
- Naming: `{UseCase}Controller.java`, `{UseCase}Service.java`
- Usar Spring Data Redis con Lettuce (no Jedis)
- Usar `StringRedisTemplate` o `RedisTemplate<String, String>` para visibilidad explicita de comandos Redis
- Preferir comandos Redis explicitos sobre abstracciones de Spring cuando haga la demo mas clara
- Convencion de keys: `workshop:{usecase}:{entity}:{id}` (ejemplo: `workshop:session:user:1001`)

## 7. Convenciones frontend
- Templates Thymeleaf para server-side rendering con layout compartido
- Todo el comportamiento dinamico en vanilla JS — sin jQuery, sin React, sin frameworks
- Un fichero JS por caso de uso: `static/js/usecase-N.js`
- Utilidades compartidas: `static/js/main.js`
- CSS: `static/css/redis-brand.css` con todos los tokens de marca
- Syntax highlighting: Prism.js (fichero vendor local, no CDN)
- Dark mode toggle usando CSS custom properties y atributo `data-theme`
- Todo el espaciado en multiplos de 8px, todo el border-radius en 5px
- Nunca usar negro puro (`#000`) — usar `#091A23`
- Nunca usar rojos genericos — siempre `#FF4438` (Redis Red)

## 8. Datos de demo
- Todos los datos de ejemplo son mock/ficticios con tematica bancaria.
- Los datos se cargan al arrancar la aplicacion via `@PostConstruct` o `ApplicationRunner`.
- Los vectores pre-computados se almacenan como arrays de floats en las clases data loader (no se necesita modelo de embeddings en vivo).
- Documentos de regulacion: extractos ficticios de MiFID II, PSD2, GDPR (resumenes inventados).

## 9. Criterio de validacion
La salida minima esperada para cualquier cambio es:
- `docker compose up -d` → Redis Stack healthy
- `./mvnw compile` → sin errores de compilacion
- `./mvnw test` → tests pasando
- Cada pagina de caso de uso carga con panel de demo y panel de code showcase
- Cada endpoint REST responde correctamente
- La UI sigue las guias de marca Redis (verificacion visual de colores, fuentes, espaciado)

Si algo no se ha podido validar, se debe decir de forma explicita.

## 10. Criterio de calidad de respuestas
- Ser directo y tecnico.
- No dar la razon al usuario cuando la decision empeora claridad, experiencia de demo o buenas practicas Redis.
- Explicar tradeoffs de forma concreta.
- Evitar ambiguedad sobre que esta implementado, que esta validado y que queda pendiente.

## 11. Mapa del codebase

| Directorio | Contenido |
| :--- | :--- |
| `src/main/java/com/redis/workshop/` | Aplicacion Spring Boot: controllers, services, config, models |
| `src/main/java/.../controller/` | REST controllers — uno por caso de uso |
| `src/main/java/.../service/` | Operaciones Redis + logica de negocio — uno por caso de uso |
| `src/main/java/.../config/` | RedisConfig, DataLoader, WebConfig |
| `src/main/java/.../model/` | DTOs, objetos de dominio |
| `src/main/resources/templates/` | Templates Thymeleaf: layout, index, usecase-1..7 |
| `src/main/resources/static/css/` | redis-brand.css, prism.css |
| `src/main/resources/static/js/` | main.js, usecase-1.js..usecase-7.js, prism.js |
| `src/main/resources/` | application.yml |
| `docker-compose.yml` | Redis Stack para desarrollo |
| `AGENTS.md` | Este fichero — guia operativa para agentes |
| `README.md` | Instrucciones de setup, descripcion de casos de uso |

## 12. Documentacion de referencia
- `README.md` (raiz): setup rapido, prerrequisitos, descripcion de los 7 casos de uso
- `AGENTS.md` (este fichero): guia operativa para agentes que trabajen en el repo
