#!/bin/sh
set -eu

MODE_EXPECTED="post"
TARGET_YEAR=""
BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ROUTING_CONFIG_FILE="${ROUTING_CONFIG_FILE:-./config/routing-cutover.env}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/smoke-rollover-year.sh --year <YYYY> [--state <pre|post>] [--base-url <url>] [--routing-config <path>]
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --year)
      TARGET_YEAR="${2:-}"
      shift 2
      ;;
    --state)
      MODE_EXPECTED="${2:-}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --routing-config)
      ROUTING_CONFIG_FILE="${2:-}"
      shift 2
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

if ! printf '%s' "$TARGET_YEAR" | grep -Eq '^20[0-9]{2}$'; then
  printf '%s\n' "A valid --year is required." >&2
  exit 1
fi

if [ "$MODE_EXPECTED" != "pre" ] && [ "$MODE_EXPECTED" != "post" ]; then
  printf '%s\n' "--state must be pre or post." >&2
  exit 1
fi

if [ ! -f "$ROUTING_CONFIG_FILE" ]; then
  printf 'Routing config not found: %s\n' "$ROUTING_CONFIG_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
. "$ROUTING_CONFIG_FILE"

wait_for_service() {
  service_name="$1"
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$service_name" 2>/dev/null || true)"
  if [ "$status" != "healthy" ] && [ "$status" != "running" ]; then
    printf 'Service %s is not ready (status=%s)\n' "$service_name" "${status:-unknown}" >&2
    exit 1
  fi
}

wp_eval_file() {
  path="$1"
  script_path="$2"

  docker compose exec -T \
    --user root \
    -e ROLLOVER_TARGET_YEAR="$TARGET_YEAR" \
    cron-master \
    wp --allow-root eval-file "$script_path" --path="$path"
}

extract_json_number() {
  key="$1"
  json="$2"
  printf '%s' "$json" | sed -n "s/.*\"$key\":\\([0-9][0-9]*\\).*/\\1/p" | head -n 1
}

extract_first_sample_url() {
  json="$1"
  printf '%s' "$json" | sed -n 's/.*"sample_urls":\["\([^"]*\)".*/\1/p' | head -n 1
}

slug_to_query() {
  url="$1"
  slug="$(printf '%s' "$url" | awk -F/ '{print $(NF-1)}')"
  printf '%s' "$slug" | tr '-' '+'
}

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-elastic
wait_for_service n9-cron-master

live_summary="$(wp_eval_file /srv/wp/live /opt/project/scripts/internal/rollover/collect-year-summary.php)"
archive_summary="$(wp_eval_file /srv/wp/archive /opt/project/scripts/internal/rollover/collect-year-summary.php)"

live_count="$(extract_json_number selected_post_count "$live_summary")"
archive_count="$(extract_json_number selected_post_count "$archive_summary")"
live_sample_url="$(extract_first_sample_url "$live_summary")"
archive_sample_url="$(extract_first_sample_url "$archive_summary")"

printf '==> routing cutover state for %s (%s)\n' "$TARGET_YEAR" "$MODE_EXPECTED"
if [ "$MODE_EXPECTED" = "pre" ]; then
  [ "$TARGET_YEAR" -ge "$LIVE_MIN_YEAR" ]
  [ "$TARGET_YEAR" -gt "$ARCHIVE_MAX_YEAR" ]
else
  [ "$TARGET_YEAR" -le "$ARCHIVE_MAX_YEAR" ]
  [ "$TARGET_YEAR" -lt "$LIVE_MIN_YEAR" ]
fi
printf '%s\n' "ok"

printf '==> content distribution for %s (%s)\n' "$TARGET_YEAR" "$MODE_EXPECTED"
if [ "$MODE_EXPECTED" = "pre" ]; then
  [ "${live_count:-0}" -gt 0 ]
  [ "${archive_count:-0}" -eq 0 ]
else
  [ "${archive_count:-0}" -gt 0 ]
  [ "${live_count:-0}" -eq 0 ]
fi
printf '%s\n' "ok"

printf '==> canonical URL resolves via expected origin policy\n'
if [ "$MODE_EXPECTED" = "pre" ]; then
  sample_url="$live_sample_url"
  expected_policy="live-public"
else
  sample_url="$archive_sample_url"
  expected_policy="archive-public"
fi

if [ -z "$sample_url" ]; then
  printf '%s\n' "Missing sample URL for validation." >&2
  exit 1
fi

curl -fsS -D - "$sample_url" -o /dev/null | grep -q "X-Origin-Cache-Policy: $expected_policy"
printf '%s\n' "ok"

printf '==> unified search resolves moved year content\n'
search_query="$(slug_to_query "$sample_url")"
search_html="$(curl -fsSL "$BASE_URL/?s=$search_query")"
printf '%s' "$search_html" | grep -Fq "$sample_url"
printf '%s\n' "ok"

printf '==> platform smoke tests remain green\n'
./scripts/smoke-routing.sh >/dev/null
./scripts/smoke-search.sh >/dev/null
printf '%s\n' "ok"
