#!/bin/sh
set -eu

MODE=""
REPORT_DIR="${REPORT_DIR:-./runtime/reports/sync}"
EXCLUDED_LOGINS="${SYNC_EXCLUDE_USER_LOGINS:-n9liveadmin,n9archiveadmin}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/sync-editorial-users.sh --mode <report-only|dry-run|apply> [--report-dir <path>]

Notes:
  - `report-only` and `dry-run` do not modify archive.
  - `apply` creates or updates editorial users in archive.
  - stale users in archive are reported, not deleted automatically.
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
      -e SYNC_EXCLUDE_USER_LOGINS="$EXCLUDED_LOGINS" \
      -e SYNC_SOURCE_SNAPSHOT_JSON="$snapshot_json" \
      cron-master \
      wp --allow-root eval-file "$script_path" --path="$path"
    return 0
  fi

  docker compose exec -T \
    --user root \
    -e SYNC_EXCLUDE_USER_LOGINS="$EXCLUDED_LOGINS" \
    cron-master \
    wp --allow-root eval-file "$script_path" --path="$path"
}

mkdir -p "$REPORT_DIR"
timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_file="$REPORT_DIR/editorial-sync-$MODE-$report_stamp.md"

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

source_snapshot="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-editorial-source-snapshot.php)"
sanitized_source_snapshot="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-editorial-snapshot.php)"
plan_json="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-editorial-plan.php "$source_snapshot")"
apply_json=""

if [ "$MODE" = "apply" ]; then
  apply_json="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-editorial-apply.php "$source_snapshot")"
fi

cat >"$report_file" <<EOF
# Editorial sync $MODE

- generated_at: $timestamp_utc
- mode: $MODE
- excluded_bootstrap_logins: $EXCLUDED_LOGINS

## Source snapshot
\`\`\`json
$sanitized_source_snapshot
\`\`\`

## Plan
\`\`\`json
$plan_json
\`\`\`
EOF

if [ -n "$apply_json" ]; then
  cat >>"$report_file" <<EOF

## Apply result
\`\`\`json
$apply_json
\`\`\`
EOF
fi

printf '%s\n' "editorial sync $MODE report written to $report_file"
