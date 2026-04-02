#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  printf '%s\n' "Usage: ./scripts/collect-service-logs.sh <service> [pattern]" >&2
  exit 1
fi

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

SERVICE="$1"
PATTERN="${2:-ERROR|FATAL|CRITICAL}"
LOG_LINES="${LOG_TAIL_LINES:-500}"

docker compose logs --tail "$LOG_LINES" "$SERVICE" 2>&1 | grep -E "$PATTERN" | ./scripts/redact-sensitive.sh || true
