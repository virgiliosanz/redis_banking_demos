#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"
BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_URL="${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}"

probe_path="$ROOT/shared/uploads/persistence-probe.txt"

echo "==> shared uploads probe on host"
test -f "$probe_path"
grep -q "Shared uploads persistence probe" "$probe_path"
printf '%s\n' "ok"

echo "==> shared uploads visible on live host"
curl -fsSL "$BASE_URL/wp-content/uploads/persistence-probe.txt" | grep -q "Shared uploads persistence probe"
printf '%s\n' "ok"

echo "==> shared uploads visible on archive host"
curl -fsSL "$ARCHIVE_URL/wp-content/uploads/persistence-probe.txt" | grep -q "Shared uploads persistence probe"
printf '%s\n' "ok"

echo "==> isolated cache directories exist"
for cache_dir in \
  "$ROOT/live/var/cache/wp-content" \
  "$ROOT/archive/var/cache/wp-content"
do
  test -d "$cache_dir"
done
printf '%s\n' "ok"

echo "==> cache mounts are exposed in containers"
docker compose exec -T fe-live test -d /var/www/html/live/wp-content/cache
docker compose exec -T fe-archive test -d /var/www/html/archive/wp-content/cache
printf '%s\n' "ok"
