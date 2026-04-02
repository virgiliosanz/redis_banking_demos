#!/bin/sh
set -eu

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

severity_from_pct() {
  value="$1"
  warning="$2"
  critical="$3"

  awk -v value="$value" -v warning="$warning" -v critical="$critical" '
    BEGIN {
      if (value >= critical) {
        print "critical";
      } else if (value >= warning) {
        print "warning";
      } else {
        print "ok";
      }
    }
  '
}

PROJECT_PATH="${PROJECT_ROOT:-$PWD}"
if [ ! -d "$PROJECT_PATH" ]; then
  PROJECT_PATH="$PWD"
fi
HOST_OS="$(uname -s)"
GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
DOCKER_STATUS="down"
CPU_COUNT=0
LOAD_1=0
LOAD_5=0
LOAD_15=0
CPU_USER_PCT=0
CPU_SYS_PCT=0
CPU_IDLE_PCT=0
IOWAIT_PCT=null
MEM_TOTAL_BYTES=0
MEM_USED_BYTES=0
MEM_USED_PCT=0
MEM_STATUS="unknown"
DISK_USED_PCT=0
DISK_STATUS="unknown"
LOAD_STATUS="unknown"
IOWAIT_STATUS="not_supported"

if docker info >/dev/null 2>&1; then
  DOCKER_STATUS="ok"
fi

if [ "$HOST_OS" = "Darwin" ]; then
  CPU_COUNT="$(sysctl -n hw.logicalcpu)"
  MEM_TOTAL_BYTES="$(sysctl -n hw.memsize)"

  VM_OUTPUT="$(vm_stat)"
  PAGE_SIZE="$(printf '%s\n' "$VM_OUTPUT" | awk -F'[()]' '/page size of/ {gsub(/[^0-9]/, "", $2); print $2; exit}')"
  FREE_PAGES="$(printf '%s\n' "$VM_OUTPUT" | awk '/Pages free/ {gsub("\\.", "", $3); print $3; exit}')"
  SPECULATIVE_PAGES="$(printf '%s\n' "$VM_OUTPUT" | awk '/Pages speculative/ {gsub("\\.", "", $3); print $3; exit}')"

  PAGE_SIZE="${PAGE_SIZE:-16384}"
  FREE_PAGES="${FREE_PAGES:-0}"
  SPECULATIVE_PAGES="${SPECULATIVE_PAGES:-0}"

  MEM_USED_BYTES="$(
    awk -v total="$MEM_TOTAL_BYTES" -v free_pages="$FREE_PAGES" -v speculative="$SPECULATIVE_PAGES" -v page_size="$PAGE_SIZE" '
      BEGIN {
        unused = (free_pages + speculative) * page_size;
        used = total - unused;
        if (used < 0) {
          used = 0;
        }
        printf "%.0f", used;
      }
    '
  )"

  MEM_USED_PCT="$(
    awk -v used="$MEM_USED_BYTES" -v total="$MEM_TOTAL_BYTES" '
      BEGIN {
        if (total <= 0) {
          print "0";
        } else {
          printf "%.2f", (used / total) * 100;
        }
      }
    '
  )"

  IOSTAT_LINE="$(iostat -w 1 -c 2 | tail -n 1)"
  CPU_USER_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $(NF-5)}')"
  CPU_SYS_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $(NF-4)}')"
  CPU_IDLE_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $(NF-3)}')"
  LOAD_1="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $(NF-2)}')"
  LOAD_5="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $(NF-1)}')"
  LOAD_15="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $NF}')"
else
  CPU_COUNT="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '0')"
  MEM_TOTAL_KB="$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || printf '0')"
  MEM_AVAILABLE_KB="$(awk '/MemAvailable/ {print $2}' /proc/meminfo 2>/dev/null || printf '0')"
  MEM_TOTAL_BYTES="$((MEM_TOTAL_KB * 1024))"
  MEM_USED_BYTES="$(((MEM_TOTAL_KB - MEM_AVAILABLE_KB) * 1024))"
  MEM_USED_PCT="$(
    awk -v used="$MEM_USED_BYTES" -v total="$MEM_TOTAL_BYTES" '
      BEGIN {
        if (total <= 0) {
          print "0";
        } else {
          printf "%.2f", (used / total) * 100;
        }
      }
    '
  )"

  IOSTAT_LINE="$(iostat -c 1 2 | tail -n 1)"
  CPU_USER_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $1}')"
  CPU_SYS_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $3}')"
  CPU_IDLE_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $6}')"
  IOWAIT_PCT="$(printf '%s\n' "$IOSTAT_LINE" | awk '{print $4}')"
  LOAD_LINE="$(uptime | awk -F'load averages?: ' '{print $2}' | tr -d ',')"
  LOAD_1="$(printf '%s\n' "$LOAD_LINE" | awk '{print $1}')"
  LOAD_5="$(printf '%s\n' "$LOAD_LINE" | awk '{print $2}')"
  LOAD_15="$(printf '%s\n' "$LOAD_LINE" | awk '{print $3}')"
  IOWAIT_STATUS="$(severity_from_pct "${IOWAIT_PCT:-0}" "${HOST_IOWAIT_WARNING_PCT:-10}" "${HOST_IOWAIT_CRITICAL_PCT:-20}")"
fi

DISK_USED_PCT="$(df -Pk "$PROJECT_PATH" 2>/dev/null | awk 'NR==2 {gsub("%", "", $5); print $5}')"
DISK_USED_PCT="${DISK_USED_PCT:-0}"
MEM_STATUS="$(severity_from_pct "$MEM_USED_PCT" "${HOST_MEMORY_WARNING_PCT:-85}" "${HOST_MEMORY_CRITICAL_PCT:-92}")"
DISK_STATUS="$(severity_from_pct "$DISK_USED_PCT" "${HOST_DISK_WARNING_PCT:-80}" "${HOST_DISK_CRITICAL_PCT:-90}")"
LOAD_STATUS="$(
  awk -v load1="$LOAD_1" -v cpus="${CPU_COUNT:-1}" '
    BEGIN {
      if (cpus <= 0) {
        cpus = 1;
      }
      if (load1 > (cpus * 1.5)) {
        print "critical";
      } else if (load1 > cpus) {
        print "warning";
      } else {
        print "ok";
      }
    }
  '
)"

jq -n \
  --arg generated_at "$GENERATED_AT" \
  --arg host_os "$HOST_OS" \
  --arg project_path "$PROJECT_PATH" \
  --arg docker_status "$DOCKER_STATUS" \
  --arg mem_status "$MEM_STATUS" \
  --arg disk_status "$DISK_STATUS" \
  --arg load_status "$LOAD_STATUS" \
  --arg iowait_status "$IOWAIT_STATUS" \
  --argjson cpu_count "${CPU_COUNT:-0}" \
  --argjson load_1 "${LOAD_1:-0}" \
  --argjson load_5 "${LOAD_5:-0}" \
  --argjson load_15 "${LOAD_15:-0}" \
  --argjson cpu_user_pct "${CPU_USER_PCT:-0}" \
  --argjson cpu_sys_pct "${CPU_SYS_PCT:-0}" \
  --argjson cpu_idle_pct "${CPU_IDLE_PCT:-0}" \
  --argjson iowait_pct "$IOWAIT_PCT" \
  --argjson mem_total_bytes "${MEM_TOTAL_BYTES:-0}" \
  --argjson mem_used_bytes "${MEM_USED_BYTES:-0}" \
  --argjson mem_used_pct "${MEM_USED_PCT:-0}" \
  --argjson disk_used_pct "${DISK_USED_PCT:-0}" \
  '{
    generated_at: $generated_at,
    host: {
      os: $host_os,
      logical_cpus: $cpu_count,
      project_path: $project_path
    },
    checks: {
      docker_daemon: { status: $docker_status },
      memory: {
        used_bytes: $mem_used_bytes,
        total_bytes: $mem_total_bytes,
        used_pct: $mem_used_pct,
        status: $mem_status
      },
      disk: {
        used_pct: $disk_used_pct,
        status: $disk_status
      },
      load_average: {
        load_1: $load_1,
        load_5: $load_5,
        load_15: $load_15,
        status: $load_status
      },
      cpu: {
        user_pct: $cpu_user_pct,
        sys_pct: $cpu_sys_pct,
        idle_pct: $cpu_idle_pct
      },
      iowait: {
        pct: $iowait_pct,
        status: $iowait_status
      }
    }
  }'
