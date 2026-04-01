#!/bin/sh
set -eu

BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_URL="${ARCHIVE_URL:-http://archive.nuevecuatrouno.test}"

run_case() {
  description="$1"
  url="$2"
  expect="$3"

  echo "==> $description"
  output="$(curl -fsSL "$url")"
  echo "$output" | grep -q "$expect"
  printf '%s\n' "ok"
}

run_case "health live" "$BASE_URL/healthz" "ok"
run_case "health archive" "$ARCHIVE_URL/healthz" "ok"
run_case "front live" "$BASE_URL/actualidad/post/" "Live sample page"
run_case "front live cultura" "$BASE_URL/cultura/agenda-local/" "Agenda local laboratorio"
run_case "front archive" "$BASE_URL/2019/05/noticia/" "Archive sample page"
run_case "front archive 2018" "$BASE_URL/2018/10/memoria-2018/" "Memoria hemeroteca 2018"
run_case "admin live login" "$BASE_URL/wp-login.php" "user_login"
run_case "admin archive login" "$ARCHIVE_URL/wp-login.php" "user_login"

redirect_headers="$(curl -fsSI "$ARCHIVE_URL/2018/10/mi-articulo/")"
echo "==> archive host public redirect"
echo "$redirect_headers" | grep -q '302'
echo "$redirect_headers" | grep -q 'Location: http://nuevecuatrouno.test/2018/10/mi-articulo/'
printf '%s\n' "ok"
