#!/bin/sh
set -eu

MODE=""
TARGET_YEAR=""
REPORT_DIR="${REPORT_DIR:-./runtime/reports/rollover}"
ROUTING_CONFIG_FILE="${ROUTING_CONFIG_FILE:-./config/routing-cutover.env}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/rollover-content-year.sh --mode <dry-run|report-only|execute> --year <YYYY> [--report-dir <path>] [--routing-config <path>]

Notes:
  - `dry-run` and `report-only` are currently implemented.
  - `execute` is intentionally blocked until editorial/platform sync phases are closed.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      MODE="$2"
      shift 2
      ;;
    --year)
      TARGET_YEAR="$2"
      shift 2
      ;;
    --report-dir)
      REPORT_DIR="$2"
      shift 2
      ;;
    --routing-config)
      ROUTING_CONFIG_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '%s\n' "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [ -z "$MODE" ] || [ -z "$TARGET_YEAR" ]; then
  usage >&2
  exit 1
fi

case "$MODE" in
  dry-run|report-only|execute) ;;
  *)
    printf '%s\n' "Unsupported mode: $MODE" >&2
    exit 1
    ;;
esac

case "$TARGET_YEAR" in
  20[0-9][0-9]) ;;
  *)
    printf '%s\n' "Invalid target year: $TARGET_YEAR" >&2
    exit 1
    ;;
esac

CURRENT_YEAR="$(date +%Y)"
if [ "$TARGET_YEAR" -ge "$CURRENT_YEAR" ]; then
  printf '%s\n' "Target year must be earlier than current year ($CURRENT_YEAR)." >&2
  exit 1
fi

if [ ! -f "$ROUTING_CONFIG_FILE" ]; then
  printf '%s\n' "Missing routing config: $ROUTING_CONFIG_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
. "$ROUTING_CONFIG_FILE"

: "${ARCHIVE_MIN_YEAR:?Missing ARCHIVE_MIN_YEAR}"
: "${ARCHIVE_MAX_YEAR:?Missing ARCHIVE_MAX_YEAR}"
: "${LIVE_MIN_YEAR:?Missing LIVE_MIN_YEAR}"
: "${LIVE_MAX_YEAR:?Missing LIVE_MAX_YEAR}"

next_archive_max="$ARCHIVE_MAX_YEAR"
next_live_min="$LIVE_MIN_YEAR"
cutover_warning=""

if [ "$TARGET_YEAR" -lt "$LIVE_MIN_YEAR" ]; then
  cutover_warning="target_year_is_already_below_live_cutover"
fi

if [ "$TARGET_YEAR" -gt "$LIVE_MIN_YEAR" ]; then
  cutover_warning="target_year_skips_current_live_cutover"
fi

if [ "$TARGET_YEAR" -eq "$LIVE_MIN_YEAR" ]; then
  next_archive_max="$TARGET_YEAR"
  next_live_min=$((TARGET_YEAR + 1))
fi

if [ "$MODE" = "execute" ]; then
  if [ "$TARGET_YEAR" -ne "$LIVE_MIN_YEAR" ]; then
    printf '%s\n' "Mode execute requires target year to match LIVE_MIN_YEAR ($LIVE_MIN_YEAR)." >&2
    exit 1
  fi

  printf '%s\n' "Mode execute is not enabled yet. Content import/delete branch still pending implementation." >&2
  exit 1
fi

wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_eval_file() {
  path="$1"
  script_path="$2"

  docker compose exec -T \
    --user root \
    -e ROLLOVER_TARGET_YEAR="$TARGET_YEAR" \
    -e ROLLOVER_MODE="$MODE" \
    cron-master \
    wp --allow-root eval-file "$script_path" --path="$path"
}

archive_collisions_file() {
  path="$1"
  slugs_csv="$2"

  docker compose exec -T \
    --user root \
    -e ROLLOVER_SLUGS_CSV="$slugs_csv" \
    -e ROLLOVER_TARGET_YEAR="$TARGET_YEAR" \
    cron-master \
    wp --allow-root eval-file /opt/project/scripts/rollover-detect-archive-collisions.php --path="$path"
}

extract_json_string() {
  key="$1"
  json="$2"
  printf '%s' "$json" | sed -n "s/.*\"$key\":\"\\([^\"]*\\)\".*/\\1/p"
}

extract_json_number() {
  key="$1"
  json="$2"
  printf '%s' "$json" | sed -n "s/.*\"$key\":\\([0-9][0-9]*\\).*/\\1/p"
}

timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"
mkdir -p "$REPORT_DIR"
report_file="$REPORT_DIR/${TARGET_YEAR}-${MODE}-${report_stamp}.md"

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

live_summary="$(wp_eval_file /srv/wp/live /opt/project/scripts/rollover-collect-year-summary.php)"
live_slug_csv="$(extract_json_string slugs_csv "$live_summary")"
archive_collisions="$(archive_collisions_file /srv/wp/archive "$live_slug_csv")"

selected_posts="$(extract_json_number selected_post_count "$live_summary")"
selected_terms="$(extract_json_number selected_term_count "$live_summary")"
selected_attachments="$(extract_json_number selected_attachment_count "$live_summary")"
collision_count="$(extract_json_number collision_count "$archive_collisions")"

cat >"$report_file" <<EOF
# Rollover $MODE $TARGET_YEAR

- generated_at: $timestamp_utc
- mode: $MODE
- target_year: $TARGET_YEAR
- routing_archive_min_year: $ARCHIVE_MIN_YEAR
- routing_archive_max_year: $ARCHIVE_MAX_YEAR
- routing_live_min_year: $LIVE_MIN_YEAR
- routing_live_max_year: $LIVE_MAX_YEAR
- next_archive_max_year_if_applied: $next_archive_max
- next_live_min_year_if_applied: $next_live_min
- cutover_warning: ${cutover_warning:-none}
- selected_posts: ${selected_posts:-0}
- selected_terms: ${selected_terms:-0}
- selected_attachments: ${selected_attachments:-0}
- archive_slug_collisions: ${collision_count:-0}
- execute_enabled: no

## Live summary JSON
\`\`\`json
$live_summary
\`\`\`

## Archive collision JSON
\`\`\`json
$archive_collisions
\`\`\`

## Notes
- This report is read-only and does not modify \`live\` or \`archive\`.
- The routing cutover is now versioned via \`$ROUTING_CONFIG_FILE\`.
- \`execute\` remains blocked until the content import/delete branch is implemented.
EOF

printf '%s\n' "rollover $MODE report written to $report_file"
