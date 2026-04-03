#!/bin/sh
set -eu

EP_HOST="${EP_HOST:-http://elastic:9200}"
EP_SEARCH_ALIAS="${EP_SEARCH_ALIAS:-n9-search-posts}"
LIVE_PREFIX="${LIVE_EP_PREFIX:-n9-live}"
ARCHIVE_PREFIX="${ARCHIVE_EP_PREFIX:-n9-archive}"
WP_ROOT_HOST_PATH="${WP_ROOT_HOST_PATH:-./runtime/wp-root}"

LIVE_PATH="/srv/wp/live"
ARCHIVE_PATH="/srv/wp/archive"
LIVE_HOST_PATH="$WP_ROOT_HOST_PATH/live/current/public"
ARCHIVE_HOST_PATH="$WP_ROOT_HOST_PATH/archive/current/public"


wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_exec() {
  docker compose exec -T --user root cron-master wp --allow-root "$@"
}

ensure_plugin() {
  path="$1"

  if ! wp_exec plugin is-installed elasticpress --path="$path" >/dev/null 2>&1; then
    wp_exec plugin install elasticpress --activate --path="$path"
    return 0
  fi

  if ! wp_exec plugin is-active elasticpress --path="$path" >/dev/null 2>&1; then
    wp_exec plugin activate elasticpress --path="$path"
  fi
}

sync_plugin_code() {
  source_path="$1"
  target_path="$2"

  mkdir -p "$target_path/wp-content/plugins"
  rsync -a --delete \
    "$source_path/wp-content/plugins/elasticpress/" \
    "$target_path/wp-content/plugins/elasticpress/"
}

get_index_name() {
  path="$1"

  wp_exec elasticpress get-indices --path="$path" | tr -d '[]"' | cut -d',' -f1
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

ensure_plugin "$LIVE_PATH"
ensure_plugin "$ARCHIVE_PATH"

wp_exec elasticpress sync --setup --yes --path="$LIVE_PATH" --ep-host="$EP_HOST" --ep-prefix="$LIVE_PREFIX"
wp_exec elasticpress sync --setup --yes --path="$ARCHIVE_PATH" --ep-host="$EP_HOST" --ep-prefix="$ARCHIVE_PREFIX"

live_index="$(get_index_name "$LIVE_PATH")"
archive_index="$(get_index_name "$ARCHIVE_PATH")"

publish_read_alias "$live_index" "$archive_index"

printf '%s\n' "elasticpress bootstrap completed: $live_index + $archive_index -> $EP_SEARCH_ALIAS"
