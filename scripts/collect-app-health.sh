#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_URL="${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}"

run_check() {
  name="$1"
  shift

  if "$@" >/dev/null 2>&1; then
    jq -n --arg name "$name" '{name: $name, status: "ok"}'
  else
    jq -n --arg name "$name" '{name: $name, status: "critical"}'
  fi
}

live_login_code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/wp-login.php" || printf '000')"
archive_login_code="$(curl -s -o /dev/null -w '%{http_code}' "$ARCHIVE_URL/wp-login.php" || printf '000')"
search_code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/?s=rioja-laboratorio" || printf '000')"

check_rows='[]'
for command_name in routing smoke-routing.sh search smoke-search.sh services smoke-services.sh
do
  :
done

row="$(run_check routing ./scripts/smoke-routing.sh)"
check_rows="$(printf '%s' "$check_rows" | jq --argjson row "$row" '. + [$row]')"
row="$(run_check search ./scripts/smoke-search.sh)"
check_rows="$(printf '%s' "$check_rows" | jq --argjson row "$row" '. + [$row]')"
row="$(run_check services ./scripts/smoke-services.sh)"
check_rows="$(printf '%s' "$check_rows" | jq --argjson row "$row" '. + [$row]')"

jq -n \
  --arg generated_at "$GENERATED_AT" \
  --argjson checks "$check_rows" \
  --argjson live_login_code "${live_login_code:-0}" \
  --argjson archive_login_code "${archive_login_code:-0}" \
  --argjson search_code "${search_code:-0}" \
  '{
    generated_at: $generated_at,
    checks: {
      live_login: {
        http_code: $live_login_code,
        status: (if $live_login_code == 200 then "ok" else "critical" end)
      },
      archive_login: {
        http_code: $archive_login_code,
        status: (if $archive_login_code == 200 then "ok" else "critical" end)
      },
      unified_search_endpoint: {
        http_code: $search_code,
        status: (if $search_code == 200 then "ok" else "critical" end)
      },
      smoke_scripts: $checks
    }
  }'
