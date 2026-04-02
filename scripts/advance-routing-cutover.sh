#!/bin/sh
set -eu

CONFIG_FILE="${1:-./config/routing-cutover.env}"
TARGET_YEAR="${2:-}"

if [ -z "$TARGET_YEAR" ]; then
  printf '%s\n' "Usage: ./scripts/advance-routing-cutover.sh [config-file] <target-year>" >&2
  exit 1
fi

case "$TARGET_YEAR" in
  20[0-9][0-9]) ;;
  *)
    printf '%s\n' "Invalid target year: $TARGET_YEAR" >&2
    exit 1
    ;;
esac

if [ ! -f "$CONFIG_FILE" ]; then
  printf '%s\n' "Missing config file: $CONFIG_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
. "$CONFIG_FILE"

: "${ARCHIVE_MIN_YEAR:?Missing ARCHIVE_MIN_YEAR}"
: "${ARCHIVE_MAX_YEAR:?Missing ARCHIVE_MAX_YEAR}"
: "${LIVE_MIN_YEAR:?Missing LIVE_MIN_YEAR}"
: "${LIVE_MAX_YEAR:?Missing LIVE_MAX_YEAR}"

if [ "$TARGET_YEAR" -ne "$LIVE_MIN_YEAR" ]; then
  printf '%s\n' "Target year must match LIVE_MIN_YEAR ($LIVE_MIN_YEAR) to advance cutover safely." >&2
  exit 1
fi

next_archive_max="$TARGET_YEAR"
next_live_min=$((TARGET_YEAR + 1))

cat >"$CONFIG_FILE" <<EOF
ARCHIVE_MIN_YEAR=$ARCHIVE_MIN_YEAR
ARCHIVE_MAX_YEAR=$next_archive_max
LIVE_MIN_YEAR=$next_live_min
LIVE_MAX_YEAR=$LIVE_MAX_YEAR
EOF

printf '%s\n' "routing cutover advanced: archive<=${next_archive_max}, live>=${next_live_min}"
