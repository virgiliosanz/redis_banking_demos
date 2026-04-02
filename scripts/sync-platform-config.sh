#!/bin/sh
set -eu

MODE=""
REPORT_DIR="${REPORT_DIR:-./runtime/reports/sync}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/sync-platform-config.sh --mode <report-only|dry-run|apply> [--report-dir <path>]

Notes:
  - This sync only applies a DB option allowlist.
  - Theme/plugin code drift remains report-only and is expected to be handled by deployment.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      MODE="$2"
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

if [ -z "$MODE" ]; then
  usage >&2
  exit 1
fi

case "$MODE" in
  report-only|dry-run|apply) ;;
  *)
    printf '%s\n' "Unsupported mode: $MODE" >&2
    exit 1
    ;;
esac

wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_eval_json() {
  path="$1"
  script_path="$2"
  snapshot_json="${3:-}"

  if [ -n "$snapshot_json" ]; then
    docker compose exec -T \
      --user root \
      -e SYNC_SOURCE_SNAPSHOT_JSON="$snapshot_json" \
      cron-master \
      wp --allow-root eval-file "$script_path" --path="$path"
    return 0
  fi

  docker compose exec -T \
    --user root \
    cron-master \
    wp --allow-root eval-file "$script_path" --path="$path"
}

mkdir -p "$REPORT_DIR"
timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_file="$REPORT_DIR/platform-sync-$MODE-$report_stamp.md"

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

source_snapshot="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-platform-source-snapshot.php)"
sanitized_live_snapshot="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-platform-snapshot.php)"
sanitized_archive_snapshot_before="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-platform-snapshot.php)"
plan_json="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-platform-plan.php "$source_snapshot")"
apply_json=""
sanitized_archive_snapshot_after=""

if [ "$MODE" = "apply" ]; then
  apply_json="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-platform-apply.php "$source_snapshot")"
  sanitized_archive_snapshot_after="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-platform-snapshot.php)"
fi

cat >"$report_file" <<EOF
# Platform sync $MODE

- generated_at: $timestamp_utc
- mode: $MODE

## Live platform snapshot
\`\`\`json
$sanitized_live_snapshot
\`\`\`

## Archive platform snapshot before
\`\`\`json
$sanitized_archive_snapshot_before
\`\`\`

## Plan
\`\`\`json
$plan_json
\`\`\`
EOF

if [ -n "$apply_json" ]; then
  ./scripts/write-heartbeat.sh "${CRON_JOB_PLATFORM_SYNC:-sync-platform-config}"
  cat >>"$report_file" <<EOF

## Apply result
\`\`\`json
$apply_json
\`\`\`

## Archive platform snapshot after
\`\`\`json
$sanitized_archive_snapshot_after
\`\`\`
EOF
fi

printf '%s\n' "platform sync $MODE report written to $report_file"
