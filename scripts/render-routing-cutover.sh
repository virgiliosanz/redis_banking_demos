#!/bin/sh
set -eu

CONFIG_FILE="${1:-./config/routing-cutover.env}"
OUTPUT_FILE="${2:-./nginx/lb/generated/path-context-map.conf}"

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

case "$ARCHIVE_MIN_YEAR:$ARCHIVE_MAX_YEAR:$LIVE_MIN_YEAR:$LIVE_MAX_YEAR" in
  20[0-9][0-9]:20[0-9][0-9]:20[0-9][0-9]:20[0-9][0-9]) ;;
  *)
    printf '%s\n' "Invalid year configuration in $CONFIG_FILE" >&2
    exit 1
    ;;
esac

if [ "$ARCHIVE_MIN_YEAR" -gt "$ARCHIVE_MAX_YEAR" ]; then
  printf '%s\n' "ARCHIVE_MIN_YEAR must be <= ARCHIVE_MAX_YEAR" >&2
  exit 1
fi

if [ "$LIVE_MIN_YEAR" -gt "$LIVE_MAX_YEAR" ]; then
  printf '%s\n' "LIVE_MIN_YEAR must be <= LIVE_MAX_YEAR" >&2
  exit 1
fi

if [ $((ARCHIVE_MAX_YEAR + 1)) -ne "$LIVE_MIN_YEAR" ]; then
  printf '%s\n' "LIVE_MIN_YEAR must be exactly ARCHIVE_MAX_YEAR + 1" >&2
  exit 1
fi

join_years() {
  start_year="$1"
  end_year="$2"
  years=""
  year="$start_year"

  while [ "$year" -le "$end_year" ]; do
    if [ -n "$years" ]; then
      years="$years|$year"
    else
      years="$year"
    fi
    year=$((year + 1))
  done

  printf '%s\n' "$years"
}

archive_years="$(join_years "$ARCHIVE_MIN_YEAR" "$ARCHIVE_MAX_YEAR")"
live_years="$(join_years "$LIVE_MIN_YEAR" "$LIVE_MAX_YEAR")"

mkdir -p "$(dirname "$OUTPUT_FILE")"
cat >"$OUTPUT_FILE" <<EOF
~^/(${archive_years})/[0-1]?[0-9]/[0-3]?[0-9]/ archive;
~^/(${live_years})/[0-1]?[0-9]/[0-3]?[0-9]/ live;
EOF

printf '%s\n' "routing cutover rendered to $OUTPUT_FILE"
