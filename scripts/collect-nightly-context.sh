#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

REPORT_ROOT="${REPORT_ROOT:-./runtime/reports/ia-ops}"
WRITE_REPORT="no"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --write-report)
      WRITE_REPORT="yes"
      shift
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

generated_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"

host_json="$(./scripts/collect-host-health.sh)"
runtime_json="$(./scripts/collect-runtime-health.sh)"
app_json="$(./scripts/collect-app-health.sh)"
elastic_json="$(./scripts/collect-elastic-health.sh)"
cron_json="$(./scripts/collect-cron-health.sh)"

context_json="$(
  jq -n \
    --arg generated_at "$generated_at" \
    --argjson host "$host_json" \
    --argjson runtime "$runtime_json" \
    --argjson app "$app_json" \
    --argjson elastic "$elastic_json" \
    --argjson cron "$cron_json" \
    '{
      generated_at: $generated_at,
      host: $host,
      runtime: $runtime,
      app: $app,
      elastic: $elastic,
      cron: $cron
    }'
)"

if [ "$WRITE_REPORT" = "yes" ]; then
  mkdir -p "$REPORT_ROOT"
  report_file="$REPORT_ROOT/nightly-context-$report_stamp.json"
  printf '%s\n' "$context_json" >"$report_file"
  printf '%s\n' "nightly context written to $report_file" >&2
fi

printf '%s\n' "$context_json"
