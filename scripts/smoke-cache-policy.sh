#!/bin/sh
set -eu

BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_URL="${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}"

assert_header() {
  headers="$1"
  expected="$2"

  printf '%s\n' "$headers" | grep -Fiq "$expected"
}

fetch_headers() {
  curl -fsSI "$@"
}

echo "==> live public cache policy"
live_headers="$(fetch_headers "$BASE_URL/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/")"
assert_header "$live_headers" "Cache-Control: public, max-age=60, s-maxage=300, stale-while-revalidate=30"
assert_header "$live_headers" "Surrogate-Control: max-age=300, stale-while-revalidate=30, stale-if-error=600"
assert_header "$live_headers" "X-Origin-Cache-Policy: live-public"
printf '%s\n' "ok"

echo "==> archive public cache policy"
archive_headers="$(fetch_headers "$BASE_URL/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/")"
assert_header "$archive_headers" "Cache-Control: public, max-age=300, s-maxage=86400, stale-while-revalidate=600"
assert_header "$archive_headers" "Surrogate-Control: max-age=86400, stale-while-revalidate=600, stale-if-error=86400"
assert_header "$archive_headers" "X-Origin-Cache-Policy: archive-public"
printf '%s\n' "ok"

echo "==> login bypass policy"
login_headers="$(fetch_headers "$BASE_URL/wp-login.php")"
assert_header "$login_headers" "Cache-Control: private, no-store"
assert_header "$login_headers" "Surrogate-Control: no-store"
assert_header "$login_headers" "X-Origin-Cache-Policy: bypass"
printf '%s\n' "ok"

echo "==> cookie bypass policy"
cookie_headers="$(curl -fsSI -H 'Cookie: wordpress_logged_in_fake=1' "$BASE_URL/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/")"
assert_header "$cookie_headers" "Cache-Control: private, no-store"
assert_header "$cookie_headers" "Surrogate-Control: no-store"
assert_header "$cookie_headers" "X-Origin-Cache-Policy: bypass"
printf '%s\n' "ok"

echo "==> search bypass policy"
search_headers="$(fetch_headers "$BASE_URL/?s=post")"
assert_header "$search_headers" "Cache-Control: private, no-store"
assert_header "$search_headers" "Surrogate-Control: no-store"
assert_header "$search_headers" "X-Origin-Cache-Policy: bypass"
printf '%s\n' "ok"

echo "==> static asset cache policy"
asset_headers="$(fetch_headers "$BASE_URL/wp-content/uploads/persistence-probe.txt")"
assert_header "$asset_headers" "Cache-Control: max-age=3600"
assert_header "$asset_headers" "X-Origin-Cache-Policy: static-asset"
printf '%s\n' "ok"
