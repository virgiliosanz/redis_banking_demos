#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"

live_probe="$ROOT/live/var/cache/wp-content/live-cache-probe.txt"
archive_probe="$ROOT/archive/var/cache/wp-content/archive-cache-probe.txt"

cleanup() {
  rm -f "$live_probe" "$archive_probe"
}

trap cleanup EXIT INT TERM

echo "==> cache probe write on live"
printf '%s\n' "live-cache-probe" > "$live_probe"
grep -q "live-cache-probe" "$live_probe"
printf '%s\n' "ok"

echo "==> cache probe does not leak to archive"
test ! -f "$archive_probe"
printf '%s\n' "ok"

echo "==> cache probe write on archive"
printf '%s\n' "archive-cache-probe" > "$archive_probe"
grep -q "archive-cache-probe" "$archive_probe"
printf '%s\n' "ok"

echo "==> archive probe does not overwrite live probe"
grep -q "live-cache-probe" "$live_probe"
printf '%s\n' "ok"

echo "==> cache paths are discardable"
rm -f "$live_probe" "$archive_probe"
test ! -f "$live_probe"
test ! -f "$archive_probe"
printf '%s\n' "ok"
