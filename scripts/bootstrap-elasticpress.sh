#!/bin/sh
set -eu

EP_HOST="${EP_HOST:-http://elastic:9200}"
EP_SEARCH_ALIAS="${EP_SEARCH_ALIAS:-n9-search-posts}"
LIVE_PREFIX="${LIVE_EP_PREFIX:-n9-live}"
ARCHIVE_PREFIX="${ARCHIVE_EP_PREFIX:-n9-archive}"

WP_PATH="/srv/wp/site"


wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_exec() {
  context="$1"; shift
  docker compose exec -T --user root -e "N9_SITE_CONTEXT=$context" cron-master wp --allow-root --path="$WP_PATH" "$@"
}

ensure_plugin() {
  context="$1"

  if ! wp_exec "$context" plugin is-installed elasticpress >/dev/null 2>&1; then
    wp_exec "$context" plugin install elasticpress --activate
    return 0
  fi

  if ! wp_exec "$context" plugin is-active elasticpress >/dev/null 2>&1; then
    wp_exec "$context" plugin activate elasticpress
  fi
}

get_index_name() {
  context="$1"

  wp_exec "$context" elasticpress get-indices | tr -d '[]"' | cut -d',' -f1
}

publish_read_alias() {
  live_index="$1"
  archive_index="$2"

  response="$(
    docker compose exec -T elastic sh -lc "
    cat <<'JSON' >/tmp/n9-alias.json
{
  \"actions\": [
    {\"remove\": {\"index\": \"*\", \"alias\": \"$EP_SEARCH_ALIAS\", \"must_exist\": false}},
    {\"add\": {\"index\": \"$live_index\", \"alias\": \"$EP_SEARCH_ALIAS\"}},
    {\"add\": {\"index\": \"$archive_index\", \"alias\": \"$EP_SEARCH_ALIAS\"}}
  ]
}
JSON
    curl -sS -H 'Content-Type: application/json' -X POST http://127.0.0.1:9200/_aliases --data-binary @/tmp/n9-alias.json
  "
  )"

  printf '%s' "$response" | grep -q '"acknowledged":true'
  docker compose exec -T elastic sh -lc "curl -fsS http://127.0.0.1:9200/_alias/$EP_SEARCH_ALIAS" >/dev/null
}

wait_for_service n9-elastic
wait_for_service n9-cron-master

ensure_plugin "live"
ensure_plugin "archive"

wp_exec "live" elasticpress sync --setup --yes --ep-host="$EP_HOST" --ep-prefix="$LIVE_PREFIX"
wp_exec "archive" elasticpress sync --setup --yes --ep-host="$EP_HOST" --ep-prefix="$ARCHIVE_PREFIX"

live_index="$(get_index_name "live")"
archive_index="$(get_index_name "archive")"

publish_read_alias "$live_index" "$archive_index"

printf '%s\n' "elasticpress bootstrap completed: $live_index + $archive_index -> $EP_SEARCH_ALIAS"
