#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
LB_SERVICE="${LB_SERVICE:-lb-nginx}"
LOG_LINES="${LOG_TAIL_LINES:-500}"

inspect_container() {
  container="$1"
  docker inspect "$container" 2>/dev/null | jq '.[0] | {
    container_name: .Name,
    running: .State.Running,
    status: .State.Status,
    health_status: (.State.Health.Status // .State.Status),
    started_at: .State.StartedAt,
    finished_at: .State.FinishedAt
  }'
}

container_rows='[]'
for container in \
  "${CONTAINER_LB_NGINX:-n9-lb-nginx}" \
  "${CONTAINER_FE_LIVE:-n9-fe-live}" \
  "${CONTAINER_FE_ARCHIVE:-n9-fe-archive}" \
  "${CONTAINER_BE_ADMIN:-n9-be-admin}" \
  "${CONTAINER_DB_LIVE:-n9-db-live}" \
  "${CONTAINER_DB_ARCHIVE:-n9-db-archive}" \
  "${CONTAINER_ELASTIC:-n9-elastic}" \
  "${CONTAINER_CRON_MASTER:-n9-cron-master}"
do
  row="$(inspect_container "$container")"
  container_rows="$(printf '%s' "$container_rows" | jq --argjson row "$row" '. + [$row]')"
done

live_health_code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL:-http://nuevecuatrouno.test}/healthz" || printf '000')"
archive_health_code="$(curl -s -o /dev/null -w '%{http_code}' "${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}/healthz" || printf '000')"
nginx_log_tail="$(docker compose logs --tail "$LOG_LINES" "$LB_SERVICE" 2>&1 || true)"
recent_5xx_count="$(printf '%s\n' "$nginx_log_tail" | grep -Ec '(^|[[:space:]])5[0-9][0-9]([[:space:]]|$)' || true)"

jq -n \
  --arg generated_at "$GENERATED_AT" \
  --argjson containers "$container_rows" \
  --argjson live_health_code "${live_health_code:-0}" \
  --argjson archive_health_code "${archive_health_code:-0}" \
  --argjson recent_5xx_count "${recent_5xx_count:-0}" \
  '{
    generated_at: $generated_at,
    containers: $containers,
    checks: {
      live_healthz: {
        http_code: $live_health_code,
        status: (if $live_health_code == 200 then "ok" else "critical" end)
      },
      archive_healthz: {
        http_code: $archive_health_code,
        status: (if $archive_health_code == 200 then "ok" else "critical" end)
      },
      lb_nginx_recent_5xx: {
        count: $recent_5xx_count,
        status: (
          if $recent_5xx_count >= 10 then "critical"
          elif $recent_5xx_count > 0 then "warning"
          else "ok"
          end
        )
      }
    }
  }'
