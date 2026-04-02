#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  printf '%s\n' "Usage: ./scripts/write-heartbeat.sh <job-name>" >&2
  exit 1
fi

CONFIG_FILE="${IA_OPS_CONFIG_FILE:-./config/ia-ops-sources.env}"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="./config/ia-ops-sources.env.example"
# shellcheck disable=SC1090
[ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"

HEARTBEAT_DIR="${CRON_HEARTBEAT_DIR:-./runtime/heartbeats}"
JOB_NAME="$1"

mkdir -p "$HEARTBEAT_DIR"
date +%s >"$HEARTBEAT_DIR/$JOB_NAME.success"
