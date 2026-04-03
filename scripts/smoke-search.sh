#!/bin/sh
set -eu

BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
EP_SEARCH_ALIAS="${EP_SEARCH_ALIAS:-n9-search-posts}"

echo "==> elasticpress status live"
docker compose exec -T -e N9_SITE_CONTEXT=live cron-master wp --allow-root elasticpress status --path=/srv/wp/site >/dev/null
printf '%s\n' "ok"

echo "==> elasticpress status archive"
docker compose exec -T -e N9_SITE_CONTEXT=archive cron-master wp --allow-root elasticpress status --path=/srv/wp/site >/dev/null
printf '%s\n' "ok"

echo "==> read alias published"
docker compose exec -T elastic sh -lc "curl -fsS http://127.0.0.1:9200/_alias/$EP_SEARCH_ALIAS" | grep -q "$EP_SEARCH_ALIAS"
printf '%s\n' "ok"

echo "==> unified search returns archive content on live"
archive_search="$(curl -fsSL "$BASE_URL/?s=rioja+metropolitano")"
printf '%s' "$archive_search" | grep -q "La vendimia abre una nueva etapa para el Rioja metropolitano"
printf '%s\n' "ok"

echo "==> unified search returns live content on live"
live_search="$(curl -fsSL "$BASE_URL/?s=Cristo+del+Santo+Sepulcro")"
printf '%s' "$live_search" | grep -q "Logrono venera la imagen del Cristo del Santo Sepulcro en La Redonda"
printf '%s\n' "ok"

echo "==> unified search returns mixed live and archive content"
mixed_search="$(curl -fsSL "$BASE_URL/?s=rioja-laboratorio")"
printf '%s' "$mixed_search" | grep -q "Logrono venera la imagen del Cristo del Santo Sepulcro en La Redonda"
printf '%s' "$mixed_search" | grep -q "La vendimia abre una nueva etapa para el Rioja metropolitano"
printf '%s\n' "ok"
