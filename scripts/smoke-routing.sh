#!/bin/sh
set -eu

BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_URL="${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}"

run_case() {
  description="$1"
  url="$2"
  expect="$3"

  echo "==> $description"
  output="$(curl -fsS "$url")"
  echo "$output"
  echo "$output" | grep -q "$expect"
}

run_case "health live" "$BASE_URL/healthz" "ok"
run_case "health archive" "$ARCHIVE_URL/healthz" "ok"
run_case "front live" "$BASE_URL/actualidad/post/" "context=live"
run_case "front archive" "$BASE_URL/2019/05/noticia/" "context=archive"
run_case "admin live" "$BASE_URL/wp-admin/" "context=admin-live"
run_case "admin archive" "$ARCHIVE_URL/wp-admin/" "context=admin-archive"

redirect_headers="$(curl -fsSI "$ARCHIVE_URL/2018/10/mi-articulo/")"
echo "==> archive host public redirect"
echo "$redirect_headers"
echo "$redirect_headers" | grep -q '302'
echo "$redirect_headers" | grep -q 'Location: http://nuevecuatrouno.test/2018/10/mi-articulo/'
