# Criterios de paso a produccion

## Objetivo
Documentar de forma explicita que le falta a esta POC para considerarse apta para un entorno serio, y convertir cada shortcut aceptado en un requisito concreto de evolucion.

## Alcance
- Backups y restauracion
- Secretos y configuracion sensible
- Seguridad y endurecimiento
- Rendimiento y cache
- Alta disponibilidad y recuperacion
- Despliegue reproducible
- Operacion y validacion

## Principio base
La POC actual es valida para validar arquitectura, routing y separacion funcional. No es aceptable para produccion sin cerrar los puntos de este documento.

## Mapa de huecos entre POC y produccion
| Area | Estado POC | Requisito de produccion |
| :--- | :--- | :--- |
| Backups | inexistentes | backups verificados y restore probado |
| Secretos | variables simples | gestion de secretos fuera de repo y control de acceso |
| Storage | bind mounts simples | estrategia de persistencia y recuperacion clara |
| HA | inexistente | eliminar o mitigar puntos unicos de fallo |
| Cache | no definida | estrategia de cache de pagina, objeto y opcode |
| Seguridad admin | minima | control de acceso fuerte y superficie reducida |
| Elastic | no critico, 1 nodo | politica degradada definida y operacion estable |
| Despliegue | documental | pipeline reproducible y rollback |
| Observabilidad | minima | alertas operativas y retencion de logs |
| Capacidad | no modelada | pruebas de carga y tuning minimo |

## 1. Backups y restauracion

### Situacion actual
- No hay backups.
- No hay pruebas de restore.

### Requisito para produccion
- Backup programado de `DB-Live`.
- Backup programado de `DB-Archive`.
- Backup de configuraciones de `LB-Nginx`, WordPress y jobs.
- Backup de `uploads` y contenido mutable.
- Politica de retencion documentada.
- Prueba de restauracion periodica documentada.

### Criterio de aceptacion
- Se puede reconstruir un entorno funcional desde backup.
- Existe evidencia de un restore exitoso.

## 2. Secretos y configuracion sensible

### Situacion actual
- Variables de entorno simples para la POC.

### Requisito para produccion
- Secretos fuera del repositorio.
- Rotacion de credenciales documentada.
- Diferenciacion entre secretos de `live`, `archive` y admin.
- Acceso minimo necesario para operadores y automatizaciones.

### Criterio de aceptacion
- Ningun secreto vive en codigo o docs.
- Existe inventario de secretos y responsable operativo.

## 3. Seguridad y endurecimiento

### Situacion actual
- Endurecimiento basico.
- `wp-admin` separado, pero sin capa fuerte de control de acceso documentada.

### Requisito para produccion
- Restringir `wp-admin` con una capa adicional: VPN, allowlist IP, SSO o Basic Auth delante.
- Deshabilitar o controlar `xmlrpc.php`.
- Politica clara para plugins y temas.
- Politica de actualizaciones.
- Cabeceras de seguridad en `LB-Nginx`.
- Minimizar escritura en filesystem.

### Criterio de aceptacion
- El admin no es accesible libremente desde Internet.
- Existe checklist de hardening ejecutado.

## 4. Persistencia y storage

### Situacion actual
- Bind mounts simples.
- Sin estrategia de release ni rollback.

### Requisito para produccion
- Separar claramente codigo, configuracion y datos mutables.
- Limitar escritura a `uploads` y caches controladas.
- Estrategia de versionado o despliegue inmutable.
- Politica de permisos y ownership auditada.

### Criterio de aceptacion
- Un despliegue no depende de cambios manuales dentro del contenedor.
- El almacenamiento critico tiene ruta de recuperacion definida.

## 5. Cache y rendimiento

### Situacion actual
- Sin estrategia definida.
- Sin validacion de capacidad.

### Requisito para produccion
- Definir cache de pagina si aplica.
- Definir cache de objeto, previsiblemente Redis o equivalente.
- Confirmar OPcache y parametros de `php-fpm`.
- Confirmar indices y tuning basico de MySQL.
- Probar latencia y throughput de busqueda y frontend.

### Criterio de aceptacion
- Existen objetivos minimos de latencia.
- Hay resultados de pruebas de carga y decisiones de tuning.

## 6. Alta disponibilidad y recuperacion

### Situacion actual
- `LB-Nginx`, `Elastic` y cada DB son puntos unicos de fallo aceptados.

### Requisito para produccion
- Definir si se buscara HA real o recuperacion rapida.
- Si hay HA:
- redundancia de balanceador
- redundancia de DB o replica util
- politica para Elastic
- Si no hay HA completa:
- tiempo objetivo de recuperacion
- procedimiento documentado de failover o rebuild

### Criterio de aceptacion
- Cada punto unico de fallo tiene tratamiento decidido.
- Existe runbook de recuperacion para incidentes graves.

## 7. Despliegue reproducible

### Situacion actual
- Arquitectura y fases documentadas, pero no pipeline ejecutable final.

### Requisito para produccion
- `docker compose` o IaC versionado.
- Configuracion de Nginx versionada.
- Variables de entorno inyectadas de forma controlada.
- Proceso de despliegue repetible.
- Estrategia de rollback.

### Criterio de aceptacion
- Un operador puede desplegar desde cero siguiendo un procedimiento documentado.
- Un cambio se puede revertir sin improvisacion.

## 8. Observabilidad real

### Situacion actual
- Healthchecks y smoke tests definidos.

### Requisito para produccion
- Ejecutar de verdad healthchecks en el orquestador.
- Alertas conectadas a un canal real.
- Retencion y rotacion de logs.
- Correlacion por `request_id`.
- Dashboard o, al menos, consultas operativas documentadas.

### Criterio de aceptacion
- Una caida de servicio genera alerta real.
- Un operador puede seguir una peticion entre LB y backend.

## 9. Gobernanza WordPress

### Situacion actual
- Contextos definidos, pero sin politica operativa de contenidos y cambios.

### Requisito para produccion
- Definir quien puede instalar plugins o temas.
- Definir como se propagan cambios entre `live`, `archive`, `admin-live` y `admin-archive`.
- Definir politica para diferencias entre ambos contextos.
- Definir como se gestionan migraciones de base de datos.

### Criterio de aceptacion
- No hay cambios manuales sin trazabilidad.
- La divergencia entre contextos esta bajo control.

## 10. Checklist de promocion

### Bloqueantes
- [ ] Backups y restore verificados
- [ ] Secretos fuera del repo y gestionados
- [ ] `wp-admin` endurecido
- [ ] Despliegue reproducible
- [ ] Runbook de recuperacion
- [ ] Alertas reales conectadas
- [ ] Smoke tests ejecutables

### Importantes
- [ ] Cache definida
- [ ] Tuning minimo de PHP y MySQL
- [ ] Politica de plugins y actualizaciones
- [ ] Rotacion de logs
- [ ] Politica clara para Elastic degradado

### Recomendables
- [ ] HA parcial o estrategia de replica
- [ ] Pruebas de carga
- [ ] Dashboards operativos

## Orden recomendado de evolucion
1. Secretos, despliegue reproducible y hardening de admin.
2. Backups y restore probado.
3. Cache y tuning de rendimiento.
4. Alertas reales y runbooks.
5. HA o estrategia de recuperacion mas robusta.

## Conclusion
La POC ya esta suficientemente definida para implementarse y demostrarse. Para produccion, los huecos no estan en la arquitectura conceptual sino en la operacion real: seguridad, despliegue, recuperacion, persistencia y capacidad.
