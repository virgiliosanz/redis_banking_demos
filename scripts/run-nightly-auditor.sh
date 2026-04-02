#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

REPORT_ROOT="${REPORT_ROOT:-./runtime/reports/ia-ops}"
WRITE_REPORT="yes"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-write-report)
      WRITE_REPORT="no"
      shift
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"

context_json="$(./scripts/collect-nightly-context.sh)"
drift_stdout="$(./scripts/report-live-archive-sync-drift.sh)"
drift_report_file="$(printf '%s\n' "$drift_stdout" | awk '{print $NF}')"
editorial_drift="$(grep -E '^- editorial_drift:' "$drift_report_file" | awk -F': ' '{print $2}')"
platform_drift="$(grep -E '^- platform_drift:' "$drift_report_file" | awk -F': ' '{print $2}')"

critical_count="$(printf '%s' "$context_json" | jq '[.. | objects | .status? | select(. == "critical")] | length')"
warning_count="$(printf '%s' "$context_json" | jq '[.. | objects | .status? | select(. == "warning")] | length')"
host_memory_status="$(printf '%s' "$context_json" | jq -r '.host.checks.memory.status')"
docker_status="$(printf '%s' "$context_json" | jq -r '.host.checks.docker_daemon.status')"
recent_5xx="$(printf '%s' "$context_json" | jq -r '.runtime.checks.lb_nginx_recent_5xx.count')"
elastic_alias_status="$(printf '%s' "$context_json" | jq -r '.elastic.alias.status')"
elastic_cluster_status="$(printf '%s' "$context_json" | jq -r '.elastic.cluster_health.status')"
cron_warning_jobs="$(printf '%s' "$context_json" | jq -r '.cron.jobs[] | select(.status == "warning" or .status == "critical") | .job_name' | paste -sd ', ' -)"

smoke_failures="$(
  printf '%s' "$context_json" | jq -r '.app.checks.smoke_scripts[] | select(.status != "ok") | .name' | paste -sd ', ' - || true
)"

global_severity="info"
summary="plataforma sana sin hallazgos relevantes"

if [ "$critical_count" -gt 0 ]; then
  global_severity="critical"
  summary="plataforma degradada con checks criticos"
elif [ "$warning_count" -gt 0 ]; then
  global_severity="warning"
  summary="plataforma sana con warnings operativos"
fi

risks="$(mktemp)"
actions="$(mktemp)"
trap 'rm -f "$risks" "$actions"' EXIT

if [ "$host_memory_status" = "critical" ]; then
  printf '%s\n' "- Memoria del host en umbral critico; el laboratorio puede falsear otros sintomas por presion local." >>"$risks"
  printf '%s\n' "- Revisar consumo de memoria del host y cerrar procesos locales ajenos al stack antes de diagnosticar degradaciones de aplicacion." >>"$actions"
fi

if [ "$docker_status" != "ok" ]; then
  printf '%s\n' "- Docker no responde; el contexto de runtime deja de ser fiable." >>"$risks"
  printf '%s\n' "- Recuperar el daemon Docker y repetir el ciclo completo de colectores." >>"$actions"
fi

if [ "$recent_5xx" -gt 0 ]; then
  printf '%s\n' "- Existen respuestas 5xx recientes en lb-nginx." >>"$risks"
  printf '%s\n' "- Revisar logs recientes de lb-nginx y correlacionarlos con request_id y upstream." >>"$actions"
fi

if [ "$elastic_alias_status" != "ok" ]; then
  printf '%s\n' "- El alias de lectura de Elasticsearch no esta sano." >>"$risks"
  printf '%s\n' "- Confirmar indices live/archive y republicar el alias antes de dar por buena la busqueda." >>"$actions"
fi

if [ -n "$smoke_failures" ]; then
  printf '%s\n' "- Hay smokes fallidos: $smoke_failures." >>"$risks"
  printf '%s\n' "- Repetir los smokes fallidos y revisar el servicio afectado antes de cerrar la auditoria." >>"$actions"
fi

if [ -n "$cron_warning_jobs" ]; then
  printf '%s\n' "- Hay jobs de cron fuera de ventana: $cron_warning_jobs." >>"$risks"
  printf '%s\n' "- Confirmar los heartbeats y revisar logs recientes de cron-master para los jobs retrasados." >>"$actions"
fi

if [ "$editorial_drift" = "yes" ] || [ "$platform_drift" = "yes" ]; then
  printf '%s\n' "- Existe drift entre live y archive que puede invalidar operaciones anuales o tareas editoriales." >>"$risks"
  printf '%s\n' "- Revisar el ultimo drift report y ejecutar la sync correspondiente antes de aceptar divergencia." >>"$actions"
fi

if [ ! -s "$risks" ]; then
  printf '%s\n' "- Sin riesgos adicionales fuera de los checks ya reflejados." >"$risks"
fi

if [ ! -s "$actions" ]; then
  printf '%s\n' "- Sin accion inmediata; mantener la observacion diaria y repetir smokes tras cambios de runtime." >"$actions"
fi

report_file="$REPORT_ROOT/nightly-auditor-$report_stamp.md"
if [ "$WRITE_REPORT" = "yes" ]; then
  mkdir -p "$REPORT_ROOT"
fi

report_content="$(cat <<EOF
# Nightly Auditor

- generated_at: $timestamp_utc
- resumen: $summary
- severidad_global: $global_severity

## Host
\`\`\`json
$(printf '%s' "$context_json" | jq '.host')
\`\`\`

## Servicios
\`\`\`json
$(printf '%s' "$context_json" | jq '.runtime')
\`\`\`

## Aplicacion
\`\`\`json
$(printf '%s' "$context_json" | jq '.app')
\`\`\`

## Cron
\`\`\`json
$(printf '%s' "$context_json" | jq '.cron')
\`\`\`

## Drift detectado
- editorial_drift: ${editorial_drift:-unknown}
- platform_drift: ${platform_drift:-unknown}
- drift_report: $drift_report_file

## Riesgos
$(cat "$risks")

## Acciones recomendadas
$(cat "$actions")

## Elasticsearch
\`\`\`json
$(printf '%s' "$context_json" | jq '.elastic')
\`\`\`
EOF
)"

if [ "$WRITE_REPORT" = "yes" ]; then
  printf '%s\n' "$report_content" >"$report_file"
fi

printf '%s\n' "$report_content"
if [ "$WRITE_REPORT" = "yes" ]; then
  printf '%s\n' "nightly auditor report written to $report_file" >&2
fi
