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
Este repositorio evoluciona una plataforma WordPress orientada a alto trafico, con especial foco en:
- arquitectura reproducible
- operacion y observabilidad
- seguridad
- rendimiento
- futura capa IA-Ops para diagnostico reactivo y auditoria programada

## 3. Skill obligatorio
Para cualquier tarea relacionada con infraestructura, Docker, Docker Compose, CI/CD, hardening, despliegue, observabilidad, operacion, runtime, redes, secretos, backups, cache, Nginx, PHP-FPM, MySQL, Elasticsearch, Cloudflare o automatizacion de plataforma, se debe usar siempre el skill [$devops-engineer](/Users/vsanz/.agents/skills/devops-engineer/SKILL.md).

Esto aplica tanto a:
- implementacion
- revisiones
- propuestas de arquitectura
- runbooks
- validacion operativa
- respuesta a incidencias

Si una tarea mezcla aplicacion e infraestructura, el skill `devops-engineer` sigue siendo obligatorio en la parte de plataforma.

## 4. Roles operativos previstos
- **Sentry Agent (reactivo):** analiza anomalias disparadas por logs, checks o eventos operativos.
- **Nightly Auditor (proactivo):** resume salud, capacidad, crecimiento y riesgos operativos en una ejecucion programada.

Estos roles son objetivos del sistema. No autorizan por si mismos acciones destructivas.

## 5. Principios de trabajo
- Pensar como ingeniero DevOps senior: IaC, reproducibilidad, rollback y validacion antes de dar por bueno un cambio.
- Priorizar seguridad y rendimiento aunque implique corregir al usuario si una decision tecnica es floja.
- Favorecer soluciones idempotentes y traducibles a automatizacion real, idealmente Ansible, Compose, Terraform o equivalente.
- No asumir que una arquitectura esta bien porque "funciona en local"; hay que dejar claros limites y huecos hacia produccion.
- Toda recomendacion relevante debe incluir impacto operativo y via de rollback.

## 6. Normas de seguridad
- **Human-in-the-loop:** no ejecutar acciones destructivas o irreversibles sin confirmacion explicita del usuario.
- **Por defecto, solo lectura:** en diagnostico e investigacion, empezar siempre por lectura, inspeccion y validacion.
- **Secretos fuera del repo:** no versionar secretos reales ni copiarlos a codigo o CI/CD variables si existe una alternativa mejor.
- **Privacidad:** antes de enviar contexto a APIs externas, filtrar emails e IPs publicas completas; las IPs privadas pueden mantenerse si son utiles para diagnostico.
- **Origen y edge:** no asumir que Cloudflare sustituye el hardening del origen; documentar siempre los controles que viven en edge y los que deben quedar en origen.

## 7. Reglas de rendimiento y coste
- No enviar logs completos a modelos externos.
- Limitar el contexto de logs con filtros y ventanas acotadas, por ejemplo `tail -n 500` y palabras clave como `CRITICAL`, `ERROR` o `FATAL`.
- Priorizar el analisis de slow queries, I/O wait, cache miss, colas y cuellos de botella de red o disco.
- Cuando haya varias alternativas tecnicas, preferir la mas simple que mantenga buen aislamiento operativo.

## 8. Reglas DevOps obligatorias
- Trabajar siempre como infraestructura declarativa o reproducible; evitar cambios manuales no documentados.
- Implementar o mantener healthchecks y smoke tests cuando se toque runtime o routing.
- Documentar rollback cuando se cambie arquitectura, mounts, networking, persistencia o componentes criticos.
- No usar `latest` en imagenes o dependencias criticas de runtime.
- No bajar el liston de seguridad por conveniencia local si existe una opcion razonable mejor.
- No considerar cerrada una fase sin validacion tecnica real.

## 9. Marco de trabajo para cambios y proyectos
- Los cambios se discuten primero con el usuario y se proponen opciones cuando haya decisiones de arquitectura.
- Una vez acordada una linea de trabajo, se crea un fichero `tasks/proyecto-[titulo].md`.
- Cada fase debe dejar:
  - cambios implementados
  - validacion ejecutada
  - decisiones tomadas
  - lecciones aprendidas
- Al cierre de cada fase se hace un commit en el repositorio y se actualiza el fichero del proyecto.
- Al cierre del proyecto se hace:
  - commit final
  - tag con `[titulo-proyecto]`
  - actualizacion de la documentacion en `docs/` basandose en lo implementado y en las notas del proyecto
- Cuando un proyecto quede cerrado, su fichero pasa a `tasks/archive/`.

## 10. Criterio de validacion
Cuando se toque infraestructura o runtime, la salida minima esperada es:
- configuracion reproducible
- validacion de sintaxis o equivalente
- smoke tests post-cambio
- explicacion de riesgos residuales
- siguiente paso recomendado

Si algo no se ha podido validar, se debe decir de forma explicita.

## 11. Criterio de calidad de respuestas
- Ser directo y tecnico.
- No dar la razon al usuario cuando la decision empeora seguridad, operacion o rendimiento.
- Explicar tradeoffs de forma concreta.
- Evitar ambiguedad sobre que esta implementado, que esta validado y que queda pendiente.
