#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
NOW_EPOCH="$(date +%s)"
HEARTBEAT_DIR="${CRON_HEARTBEAT_DIR:-./runtime/heartbeats}"
LOG_LINES="${LOG_TAIL_LINES:-500}"
WARNING_DELAY="${CRON_WARNING_DELAY_MINUTES:-30}"
CRITICAL_DELAY="${CRON_CRITICAL_DELAY_MINUTES:-120}"

job_warning_threshold() {
  case "$1" in
    "${CRON_JOB_EDITORIAL_SYNC:-sync-editorial-users}") printf '%s\n' "${CRON_JOB_EDITORIAL_SYNC_WARNING_MINUTES:-$WARNING_DELAY}" ;;
    "${CRON_JOB_PLATFORM_SYNC:-sync-platform-config}") printf '%s\n' "${CRON_JOB_PLATFORM_SYNC_WARNING_MINUTES:-$WARNING_DELAY}" ;;
    "${CRON_JOB_ROLLOVER:-rollover-content-year}") printf '%s\n' "${CRON_JOB_ROLLOVER_WARNING_MINUTES:-$WARNING_DELAY}" ;;
    *) printf '%s\n' "$WARNING_DELAY" ;;
  esac
}

job_critical_threshold() {
  case "$1" in
    "${CRON_JOB_EDITORIAL_SYNC:-sync-editorial-users}") printf '%s\n' "${CRON_JOB_EDITORIAL_SYNC_CRITICAL_MINUTES:-$CRITICAL_DELAY}" ;;
    "${CRON_JOB_PLATFORM_SYNC:-sync-platform-config}") printf '%s\n' "${CRON_JOB_PLATFORM_SYNC_CRITICAL_MINUTES:-$CRITICAL_DELAY}" ;;
    "${CRON_JOB_ROLLOVER:-rollover-content-year}") printf '%s\n' "${CRON_JOB_ROLLOVER_CRITICAL_MINUTES:-$CRITICAL_DELAY}" ;;
    *) printf '%s\n' "$CRITICAL_DELAY" ;;
  esac
}

job_missing_status() {
  case "$1" in
    "${CRON_JOB_EDITORIAL_SYNC:-sync-editorial-users}") printf '%s\n' "${CRON_JOB_EDITORIAL_SYNC_MISSING_STATUS:-warning}" ;;
    "${CRON_JOB_PLATFORM_SYNC:-sync-platform-config}") printf '%s\n' "${CRON_JOB_PLATFORM_SYNC_MISSING_STATUS:-warning}" ;;
    "${CRON_JOB_ROLLOVER:-rollover-content-year}") printf '%s\n' "${CRON_JOB_ROLLOVER_MISSING_STATUS:-info}" ;;
    *) printf '%s\n' "warning" ;;
  esac
}

job_rows='[]'
for job_name in \
  "${CRON_JOB_EDITORIAL_SYNC:-sync-editorial-users}" \
  "${CRON_JOB_PLATFORM_SYNC:-sync-platform-config}" \
  "${CRON_JOB_ROLLOVER:-rollover-content-year}"
do
  heartbeat_file="$HEARTBEAT_DIR/$job_name.success"
  warning_threshold="$(job_warning_threshold "$job_name")"
  critical_threshold="$(job_critical_threshold "$job_name")"
  status="$(job_missing_status "$job_name")"
  last_success_epoch=null
  delay_minutes=null
  source="heartbeat_missing"

  if [ -f "$heartbeat_file" ]; then
    heartbeat_content="$(tr -d '\n' < "$heartbeat_file")"
    if printf '%s' "$heartbeat_content" | grep -Eq '^[0-9]+$'; then
      last_success_epoch="$heartbeat_content"
      delay_minutes="$(
        awk -v now="$NOW_EPOCH" -v ts="$heartbeat_content" '
          BEGIN {
            delay = (now - ts) / 60;
            if (delay < 0) {
              delay = 0;
            }
            printf "%.0f", delay;
          }
        '
      )"
      status="$(
        awk -v delay="$delay_minutes" -v warning="$warning_threshold" -v critical="$critical_threshold" '
          BEGIN {
            if (delay >= critical) {
              print "critical";
            } else if (delay >= warning) {
              print "warning";
            } else {
              print "ok";
            }
          }
        '
      )"
      source="heartbeat"
    fi
  fi

  job_rows="$(printf '%s' "$job_rows" | jq \
    --arg job_name "$job_name" \
    --arg source "$source" \
    --arg status "$status" \
    --argjson warning_minutes "${warning_threshold:-0}" \
    --argjson critical_minutes "${critical_threshold:-0}" \
    --argjson last_success_epoch "$last_success_epoch" \
    --argjson delay_minutes "$delay_minutes" \
    '. + [{
      job_name: $job_name,
      source: $source,
      last_success_epoch: $last_success_epoch,
      delay_minutes: $delay_minutes,
      warning_minutes: $warning_minutes,
      critical_minutes: $critical_minutes,
      status: $status
    }]'
  )"
done

cron_log_tail="$(docker compose logs --tail "$LOG_LINES" cron-master 2>&1 || true)"
recent_error_count="$(printf '%s\n' "$cron_log_tail" | grep -Eic 'ERROR|FATAL|CRITICAL' || true)"

jq -n \
  --arg generated_at "$GENERATED_AT" \
  --arg heartbeat_dir "$HEARTBEAT_DIR" \
  --argjson jobs "$job_rows" \
  --argjson recent_error_count "${recent_error_count:-0}" \
  '{
    generated_at: $generated_at,
    heartbeat_dir: $heartbeat_dir,
    jobs: $jobs,
    recent_log_errors: {
      count: $recent_error_count,
      status: (
        if $recent_error_count >= 5 then "critical"
        elif $recent_error_count > 0 then "warning"
        else "ok"
        end
      )
    }
  }'
