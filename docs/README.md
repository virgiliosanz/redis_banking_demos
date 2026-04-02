# Documentacion viva

Este directorio queda reservado para documentacion estable del sistema:
- arquitectura vigente
- contratos operativos
- runbooks
- checklists de operacion que siguen activas

No debe usarse para historico de ejecucion de proyectos o notas transitorias de fases ya cerradas.

## Documentos principales
- `project.md`: arquitectura y estado global de la plataforma
- `poc-local-runbook.md`: operacion local, validacion manual y pruebas base
- `annual-content-rollover.md`: contrato funcional y tecnico del rollover anual
- `annual-content-rollover-checklist.md`: checklist previa, posterior y rollback del rollover
- `live-archive-sync-contract.md`: contrato de sincronizacion editorial y de plataforma
- `search-architecture-live-archive.md`: busqueda unificada con indices separados y alias comun
- `cache-policy-by-context.md`: politica de cache en origen por contexto
- `wordpress-persistence-layout.md`: persistencia y mounts compartidos/aislados
- `origin-behind-cloudflare-tunnel.md`: modelo objetivo de origen privado detras de Cloudflare Tunnel
- `ia-ops-bootstrap-contract.md`: contrato operativo del bootstrap IA-Ops

## Historico y planes
- Los planes de ejecucion y el historico de proyectos viven en `tasks/`
- Los documentos de transicion o notas de fase antiguas deben archivarse en `tasks/archive/`
