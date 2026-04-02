#!/bin/sh
set -eu

MODE=""
TARGET_YEAR=""
REPORT_DIR="${REPORT_DIR:-./runtime/reports/rollover}"
ROUTING_CONFIG_FILE="${ROUTING_CONFIG_FILE:-./config/routing-cutover.env}"
EP_HOST="${EP_HOST:-http://elastic:9200}"
EP_SEARCH_ALIAS="${EP_SEARCH_ALIAS:-n9-search-posts}"
ARCHIVE_EP_PREFIX="${ARCHIVE_EP_PREFIX:-n9-archive}"
LIVE_EP_PREFIX="${LIVE_EP_PREFIX:-n9-live}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/rollover-content-year.sh --mode <dry-run|report-only|execute> --year <YYYY> [--report-dir <path>] [--routing-config <path>]

Notes:
  - `execute` is implemented but should only be run with explicit confirmation.
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

wp_eval_file_with_snapshot() {
  path="$1"
  script_path="$2"
  snapshot_path="$3"

  docker compose exec -T \
    --user root \
    -e ROLLOVER_TARGET_YEAR="$TARGET_YEAR" \
    -e ROLLOVER_MODE="$MODE" \
    -e ROLLOVER_SNAPSHOT_FILE="$snapshot_path" \
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

write_remote_snapshot() {
  local_file="$1"
  remote_file="$2"

  docker compose exec -T cron-master sh -lc "cat > '$remote_file'" < "$local_file"
}

reindex_site() {
  path="$1"
  prefix="$2"

  docker compose exec -T --user root cron-master \
    wp --allow-root elasticpress sync --setup --yes --path="$path" --ep-host="$EP_HOST" --ep-prefix="$prefix" >/dev/null
}

get_index_name() {
  path="$1"

  docker compose exec -T --user root cron-master \
    wp --allow-root elasticpress get-indices --path="$path" | tr -d '[]"' | cut -d',' -f1
}

publish_read_alias() {
  live_index="$1"
  archive_index="$2"

  response="$(
    docker compose exec -T elastic sh -lc "
    cat <<'JSON' >/tmp/n9-alias.json
{
  \"actions\": [
    {\"remove\": {\"index\": \"*\", \"alias\": \"$EP_SEARCH_ALIAS\", \"must_exist\": false}},
    {\"add\": {\"index\": \"$live_index\", \"alias\": \"$EP_SEARCH_ALIAS\"}},
    {\"add\": {\"index\": \"$archive_index\", \"alias\": \"$EP_SEARCH_ALIAS\"}}
  ]
}
JSON
    curl -sS -H 'Content-Type: application/json' -X POST http://127.0.0.1:9200/_aliases --data-binary @/tmp/n9-alias.json
  "
  )"

  printf '%s' "$response" | grep -q '"acknowledged":true'
  docker compose exec -T elastic sh -lc "curl -fsS http://127.0.0.1:9200/_alias/$EP_SEARCH_ALIAS" >/dev/null
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
artifact_dir="$REPORT_DIR/${TARGET_YEAR}-${MODE}-${report_stamp}"
mkdir -p "$artifact_dir"

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

live_summary="$(wp_eval_file /srv/wp/live /opt/project/scripts/rollover-collect-year-summary.php)"
live_slug_csv="$(extract_json_string slugs_csv "$live_summary")"
archive_collisions="$(archive_collisions_file /srv/wp/archive "$live_slug_csv")"
source_snapshot="$(wp_eval_file /srv/wp/live /opt/project/scripts/rollover-export-year.php)"
archive_backup_snapshot="$(wp_eval_file /srv/wp/archive /opt/project/scripts/rollover-export-year.php)"

selected_posts="$(extract_json_number selected_post_count "$live_summary")"
selected_terms="$(extract_json_number selected_term_count "$live_summary")"
selected_attachments="$(extract_json_number selected_attachment_count "$live_summary")"
collision_count="$(extract_json_number collision_count "$archive_collisions")"

source_snapshot_file="$artifact_dir/source-live-${TARGET_YEAR}.json"
archive_backup_file="$artifact_dir/archive-backup-${TARGET_YEAR}.json"
printf '%s\n' "$source_snapshot" >"$source_snapshot_file"
printf '%s\n' "$archive_backup_snapshot" >"$archive_backup_file"

import_result=""
delete_result=""
execute_enabled="no"

if [ "$MODE" = "execute" ]; then
  if [ "${selected_posts:-0}" -eq 0 ]; then
    printf '%s\n' "Execute mode requires at least one selected post." >&2
    exit 1
  fi

  if [ "${collision_count:-0}" -ne 0 ]; then
    printf '%s\n' "Execute mode requires zero archive slug collisions." >&2
    exit 1
  fi

  remote_source_snapshot="/tmp/rollover-source-${TARGET_YEAR}.json"
  write_remote_snapshot "$source_snapshot_file" "$remote_source_snapshot"

  import_result="$(wp_eval_file_with_snapshot /srv/wp/archive /opt/project/scripts/rollover-import-snapshot.php "$remote_source_snapshot")"
  reindex_site /srv/wp/archive "$ARCHIVE_EP_PREFIX"
  ./scripts/advance-routing-cutover.sh "$ROUTING_CONFIG_FILE" "$TARGET_YEAR"
  ./scripts/render-routing-cutover.sh "$ROUTING_CONFIG_FILE"
  docker compose exec -T lb-nginx nginx -s reload >/dev/null
  delete_result="$(wp_eval_file_with_snapshot /srv/wp/live /opt/project/scripts/rollover-delete-source-posts.php "$remote_source_snapshot")"
  reindex_site /srv/wp/live "$LIVE_EP_PREFIX"
  live_index="$(get_index_name /srv/wp/live)"
  archive_index="$(get_index_name /srv/wp/archive)"
  publish_read_alias "$live_index" "$archive_index"
  ./scripts/write-heartbeat.sh "${CRON_JOB_ROLLOVER:-rollover-content-year}"
  execute_enabled="yes"
fi

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
- execute_enabled: $execute_enabled
- source_snapshot_file: $source_snapshot_file
- archive_backup_file: $archive_backup_file

## Live summary JSON
\`\`\`json
$live_summary
\`\`\`

## Archive collision JSON
\`\`\`json
$archive_collisions
\`\`\`

## Source snapshot JSON
\`\`\`json
$source_snapshot
\`\`\`

## Archive backup snapshot JSON
\`\`\`json
$archive_backup_snapshot
\`\`\`

EOF

if [ -n "$import_result" ]; then
  cat >>"$report_file" <<EOF

## Import result JSON
\`\`\`json
$import_result
\`\`\`
EOF
fi

if [ -n "$delete_result" ]; then
  cat >>"$report_file" <<EOF

## Delete result JSON
\`\`\`json
$delete_result
\`\`\`
EOF
fi

cat >>"$report_file" <<EOF

## Notes
- The routing cutover is now versioned via \`$ROUTING_CONFIG_FILE\`.
- \`execute\` is only valid when target year matches current \`LIVE_MIN_YEAR\`.
EOF

printf '%s\n' "rollover $MODE report written to $report_file"
