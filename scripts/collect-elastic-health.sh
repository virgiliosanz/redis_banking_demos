#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
ALIAS_NAME="${EP_SEARCH_ALIAS:-n9-search-posts}"

cluster_health="$(docker compose exec -T elastic sh -lc 'curl -fsS http://127.0.0.1:9200/_cluster/health')"
indices="$(docker compose exec -T elastic sh -lc 'curl -fsS http://127.0.0.1:9200/_cat/indices?format=json')"
aliases_raw="$(docker compose exec -T elastic sh -lc "curl -fsS http://127.0.0.1:9200/_cat/aliases/$ALIAS_NAME?format=json" 2>/dev/null || true)"

if [ -n "$aliases_raw" ]; then
  alias_present="true"
  alias_rows="$aliases_raw"
else
  alias_present="false"
  alias_rows='[]'
fi

jq -n \
  --arg generated_at "$GENERATED_AT" \
  --argjson cluster_health "$cluster_health" \
  --argjson indices "$indices" \
  --argjson alias_rows "$alias_rows" \
  --argjson alias_present "$alias_present" \
  '{
    generated_at: $generated_at,
    cluster_health: $cluster_health,
    indices: $indices,
    alias: {
      present: $alias_present,
      rows: $alias_rows,
      status: (
        if $alias_present then "ok" else "critical" end
      )
    }
  }'
