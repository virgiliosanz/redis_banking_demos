#!/bin/sh
set -eu

REPORT_DIR="${REPORT_DIR:-./runtime/reports/sync}"
EXCLUDED_LOGINS="${SYNC_EXCLUDE_USER_LOGINS:-n9liveadmin,n9archiveadmin}"

wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_eval_json() {
  path="$1"
  script_path="$2"

  docker compose exec -T \
    --user root \
    -e SYNC_EXCLUDE_USER_LOGINS="$EXCLUDED_LOGINS" \
    cron-master \
    wp --allow-root eval-file "$script_path" --path="$path"
}

mkdir -p "$REPORT_DIR"
timestamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
report_stamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_file="$REPORT_DIR/live-archive-sync-$report_stamp.md"

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

live_editorial="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-editorial-snapshot.php)"
archive_editorial="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-editorial-snapshot.php)"
live_platform="$(wp_eval_json /srv/wp/live /opt/project/scripts/sync-platform-snapshot.php)"
archive_platform="$(wp_eval_json /srv/wp/archive /opt/project/scripts/sync-platform-snapshot.php)"

editorial_drift="yes"
platform_drift="yes"

if [ "$live_editorial" = "$archive_editorial" ]; then
  editorial_drift="no"
fi

if [ "$live_platform" = "$archive_platform" ]; then
  platform_drift="no"
fi

cat >"$report_file" <<EOF
# Drift report live/archive

- generated_at: $timestamp_utc
- excluded_bootstrap_logins: $EXCLUDED_LOGINS
- editorial_drift: $editorial_drift
- platform_drift: $platform_drift

## Live editorial snapshot
\`\`\`json
$live_editorial
\`\`\`

## Archive editorial snapshot
\`\`\`json
$archive_editorial
\`\`\`

## Live platform snapshot
\`\`\`json
$live_platform
\`\`\`

## Archive platform snapshot
\`\`\`json
$archive_platform
\`\`\`
EOF

printf '%s\n' "sync drift report written to $report_file"
