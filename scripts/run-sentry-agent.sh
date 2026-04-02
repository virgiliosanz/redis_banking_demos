#!/bin/sh
set -eu

usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-sentry-agent.sh --service <compose-service> [--pattern <regex>] [--summary <text>] [--no-write-report]

Examples:
  ./scripts/run-sentry-agent.sh --service lb-nginx
  ./scripts/run-sentry-agent.sh --service elastic --summary "alias ausente tras reindexado"
EOF
}

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

REPORT_ROOT="${REPORT_ROOT:-./runtime/reports/ia-ops}"
SERVICE=""
PATTERN=""
USER_SUMMARY=""
WRITE_REPORT="yes"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --service)
      SERVICE="$2"
      shift 2
      ;;
    --pattern)
      PATTERN="$2"
      shift 2
      ;;
    --summary)
      USER_SUMMARY="$2"
      shift 2
      ;;
    --no-write-report)
      WRITE_REPORT="no"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [ -z "$SERVICE" ]; then
  usage >&2
  exit 1
fi

timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"

host_json="$(./scripts/collect-host-health.sh)"
runtime_json="$(./scripts/collect-runtime-health.sh)"
app_json="$(./scripts/collect-app-health.sh)"
elastic_json="$(./scripts/collect-elastic-health.sh)"
cron_json="$(./scripts/collect-cron-health.sh)"
service_logs="$(./scripts/collect-service-logs.sh "$SERVICE" "${PATTERN:-ERROR|FATAL|CRITICAL}")"

container_health="$(
  printf '%s' "$runtime_json" | jq -r --arg service "$SERVICE" '
    .containers[]
    | select(
        ($service == "lb-nginx" and .container_name == "/n9-lb-nginx") or
        ($service == "fe-live" and .container_name == "/n9-fe-live") or
        ($service == "fe-archive" and .container_name == "/n9-fe-archive") or
        ($service == "be-admin" and .container_name == "/n9-be-admin") or
        ($service == "db-live" and .container_name == "/n9-db-live") or
        ($service == "db-archive" and .container_name == "/n9-db-archive") or
        ($service == "elastic" and .container_name == "/n9-elastic") or
        ($service == "cron-master" and .container_name == "/n9-cron-master")
      )
    | .health_status
  ' | head -n 1
)"
container_health="${container_health:-unknown}"

severity="info"
summary="${USER_SUMMARY:-incidencia sin hallazgo concluyente}"
cause="sin causa probable cerrada con el contexto actual"
validations="$(mktemp)"
actions="$(mktemp)"
evidence="$(mktemp)"
trap 'rm -f "$validations" "$actions" "$evidence"' EXIT

printf '%s\n' "- health_status del servicio: $container_health" >>"$evidence"

if [ -n "$service_logs" ]; then
  printf '%s\n' "- logs acotados del servicio contienen coincidencias con el patron seleccionado" >>"$evidence"
fi

case "$SERVICE" in
  lb-nginx)
    recent_5xx="$(printf '%s' "$runtime_json" | jq -r '.checks.lb_nginx_recent_5xx.count')"
    printf '%s\n' "- lb_nginx_recent_5xx: $recent_5xx" >>"$evidence"
    if [ "$container_health" != "healthy" ]; then
      severity="critical"
      summary="${USER_SUMMARY:-lb-nginx no esta sano}"
      cause="caida o degradacion directa del balanceador"
    elif [ "$recent_5xx" -gt 0 ] || [ -n "$service_logs" ]; then
      severity="warning"
      summary="${USER_SUMMARY:-lb-nginx muestra errores recientes}"
      cause="errores recientes en frontend o upstream degradado"
    else
      summary="${USER_SUMMARY:-lb-nginx sano sin errores recientes}"
      cause="sin evidencia actual de fallo en lb-nginx"
    fi
    printf '%s\n' "- revisar request_id, host y php_upstream de las peticiones afectadas" >>"$validations"
    printf '%s\n' "- repetir smoke-routing y verificar /healthz en ambos hosts" >>"$validations"
    printf '%s\n' "- inspeccionar logs recientes de lb-nginx y del upstream implicado" >>"$actions"
    ;;
  elastic)
    alias_status="$(printf '%s' "$elastic_json" | jq -r '.alias.status')"
    cluster_status="$(printf '%s' "$elastic_json" | jq -r '.cluster_health.status')"
    printf '%s\n' "- elastic alias status: $alias_status" >>"$evidence"
    printf '%s\n' "- elastic cluster status: $cluster_status" >>"$evidence"
    if [ "$container_health" != "healthy" ] || [ "$alias_status" != "ok" ]; then
      severity="critical"
      summary="${USER_SUMMARY:-elastic o el alias de lectura no estan sanos}"
      cause="busqueda degradada por caida de elastic o alias ausente"
    elif [ "$cluster_status" != "green" ] && [ "$cluster_status" != "yellow" ]; then
      severity="warning"
      summary="${USER_SUMMARY:-elastic reporta estado no nominal}"
      cause="salud de cluster distinta del baseline de laboratorio"
    else
      summary="${USER_SUMMARY:-elastic sano en el baseline del laboratorio}"
      cause="sin evidencia actual de fallo de busqueda"
    fi
    printf '%s\n' "- confirmar _cluster/health, indices live/archive y alias n9-search-posts" >>"$validations"
    printf '%s\n' "- repetir smoke-search para validar la capa publica" >>"$validations"
    printf '%s\n' "- revisar el ultimo reindexado y republicar alias si falta" >>"$actions"
    ;;
  cron-master)
    delayed_jobs="$(printf '%s' "$cron_json" | jq -r '.jobs[] | select(.status == "warning" or .status == "critical") | .job_name' | paste -sd ', ' -)"
    printf '%s\n' "- delayed_jobs: ${delayed_jobs:-none}" >>"$evidence"
    if [ "$container_health" != "healthy" ]; then
      severity="critical"
      summary="${USER_SUMMARY:-cron-master no esta sano}"
      cause="caida del runtime que ejecuta jobs criticos"
    elif [ -n "$delayed_jobs" ] || [ -n "$service_logs" ]; then
      severity="warning"
      summary="${USER_SUMMARY:-cron-master presenta retrasos o errores recientes}"
      cause="jobs fuera de ventana o errores en logs del cron"
    else
      summary="${USER_SUMMARY:-cron-master sano sin retrasos visibles}"
      cause="sin evidencia actual de fallo en cron-master"
    fi
    printf '%s\n' "- confirmar heartbeats de sync editorial, sync de plataforma y rollover" >>"$validations"
    printf '%s\n' "- revisar los logs recientes y reejecutar manualmente solo el job afectado si procede" >>"$actions"
    ;;
  *)
    if [ "$container_health" != "healthy" ]; then
      severity="critical"
      summary="${USER_SUMMARY:-$SERVICE no esta sano}"
      cause="contenedor degradado o caido"
    elif [ -n "$service_logs" ]; then
      severity="warning"
      summary="${USER_SUMMARY:-$SERVICE contiene errores recientes}"
      cause="errores del servicio detectados en logs acotados"
    else
      summary="${USER_SUMMARY:-$SERVICE sano sin errores recientes}"
      cause="sin evidencia actual de fallo en el servicio"
    fi
    printf '%s\n' "- revisar healthcheck y logs recientes del servicio $SERVICE" >>"$validations"
    printf '%s\n' "- repetir el smoke funcional relacionado con el servicio afectado" >>"$actions"
    ;;
esac

if [ "$severity" = "critical" ]; then
  risk="el servicio puede quedar caido o degradar rutas base del sitio"
elif [ "$severity" = "warning" ]; then
  risk="el problema puede escalar a degradacion visible si persiste"
else
  risk="sin impacto inmediato confirmado"
fi

report_file="$REPORT_ROOT/sentry-$SERVICE-$report_stamp.md"
if [ "$WRITE_REPORT" = "yes" ]; then
  mkdir -p "$REPORT_ROOT"
fi

report_content="$(cat <<EOF
# Sentry Agent

- generated_at: $timestamp_utc
- resumen: $summary
- severidad: $severity
- servicio_afectado: $SERVICE

## Evidencias
$(cat "$evidence")

## Causa probable
$cause

## Validaciones recomendadas
$(cat "$validations")

## Acciones manuales
$(cat "$actions")

## Playbook ansible sugerido
- revisar y traducir el diagnostico a un playbook especifico del servicio antes de automatizar cualquier remediacion

## Riesgo si no se actua
$risk

## Contexto adicional
\`\`\`json
$(jq -n \
  --argjson host "$host_json" \
  --argjson runtime "$runtime_json" \
  --argjson app "$app_json" \
  --argjson elastic "$elastic_json" \
  --argjson cron "$cron_json" \
  '{host: $host, runtime: $runtime, app: $app, elastic: $elastic, cron: $cron}')
\`\`\`

## Logs acotados
\`\`\`
${service_logs:-sin coincidencias}
\`\`\`
EOF
)"

if [ "$WRITE_REPORT" = "yes" ]; then
  printf '%s\n' "$report_content" >"$report_file"
fi

printf '%s\n' "$report_content"
if [ "$WRITE_REPORT" = "yes" ]; then
  printf '%s\n' "sentry report written to $report_file" >&2
fi
