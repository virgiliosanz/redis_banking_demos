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
run_case "front live" "$BASE_URL/2026/04/01/logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda/" "rioja-laboratorio"
run_case "front live cultura" "$BASE_URL/cultura/agenda-local/" "Agenda local laboratorio"
run_case "front archive" "$BASE_URL/2019/05/15/logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad/" "Archivo 2019"
run_case "front archive 2018" "$BASE_URL/2018/10/21/la-vendimia-abre-una-nueva-etapa-para-el-rioja-metropolitano/" "rioja-laboratorio"
run_case "admin live login" "$BASE_URL/wp-login.php" "user_login"
run_case "admin archive login" "$ARCHIVE_URL/wp-login.php" "user_login"

redirect_headers="$(curl -fsSI "$ARCHIVE_URL/2018/10/mi-articulo/")"
echo "==> archive host public redirect"
echo "$redirect_headers" | grep -q '302'
echo "$redirect_headers" | grep -q 'Location: http://nuevecuatrouno.test/2018/10/mi-articulo/'
printf '%s\n' "ok"
