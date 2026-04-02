#!/bin/sh
set -eu

MODE=""
TARGET_YEAR=""
REPORT_DIR="${REPORT_DIR:-./runtime/reports/rollover}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/rollover-content-year.sh --mode <dry-run|report-only|execute> --year <YYYY> [--report-dir <path>]

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

if [ "$MODE" = "execute" ]; then
  printf '%s\n' "Mode execute is not enabled yet. Close editorial/platform sync phases first." >&2
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
- \`execute\` remains blocked until editorial and platform sync phases are closed.
EOF

printf '%s\n' "rollover $MODE report written to $report_file"
