#!/bin/sh
set -eu

SECRETS_DIR="${1:-./.secrets}"
BASE_URL="${BASE_URL:-http://nuevecuatrouno.test}"
ARCHIVE_PUBLIC_URL="${ARCHIVE_PUBLIC_URL:-http://nuevecuatrouno.test}"

live_admin_password="$(cat "$SECRETS_DIR/wp-live-admin-password")"
archive_admin_password="$(cat "$SECRETS_DIR/wp-archive-admin-password")"

wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

WP_PATH="/srv/wp/site"

wp_exec() {
  context="$1"; shift
  docker compose exec -T --user root -e "N9_SITE_CONTEXT=$context" cron-master wp --allow-root --path="$WP_PATH" "$@"
}

ensure_core_install() {
  context="$1"
  url="$2"
  title="$3"
  admin_user="$4"
  admin_password="$5"
  admin_email="$6"

  if ! wp_exec "$context" core is-installed >/dev/null 2>&1; then
    wp_exec "$context" core install \
      --url="$url" \
      --title="$title" \
      --admin_user="$admin_user" \
      --admin_password="$admin_password" \
      --admin_email="$admin_email" \
      --skip-email
  fi
}

ensure_option() {
  context="$1"
  option_name="$2"
  option_value="$3"

  current_value="$(wp_exec "$context" option get "$option_name" 2>/dev/null || true)"
  if [ "$current_value" = "$option_value" ]; then
    return 0
  fi

  wp_exec "$context" option update "$option_name" "$option_value" >/dev/null
}

ensure_page() {
  context="$1"
  title="$2"
  slug="$3"
  parent_id="${4:-0}"
  content="$5"

  existing_id="$(wp_exec "$context" post list --post_type=page --name="$slug" --post_parent="$parent_id" --field=ID --posts_per_page=1 2>/dev/null || true)"
  if [ -n "$existing_id" ]; then
    printf '%s\n' "$existing_id"
    return 0
  fi

  wp_exec "$context" post create \
    --post_type=page \
    --post_status=publish \
    --post_title="$title" \
    --post_name="$slug" \
    --post_parent="$parent_id" \
    --post_content="$content" \
    --porcelain
}

ensure_post() {
  context="$1"
  title="$2"
  slug="$3"
  post_date="$4"
  content="$5"

  existing_id="$(wp_exec "$context" post list --post_type=post --name="$slug" --field=ID --posts_per_page=1 2>/dev/null || true)"
  if [ -n "$existing_id" ]; then
    wp_exec "$context" post update \
      "$existing_id" \
      --post_status=publish \
      --post_title="$title" \
      --post_name="$slug" \
      --post_date="$post_date" \
      --post_content="$content" >/dev/null
    printf '%s\n' "$existing_id"
    return 0
  fi

  wp_exec "$context" post create \
    --post_type=post \
    --post_status=publish \
    --post_title="$title" \
    --post_name="$slug" \
    --post_date="$post_date" \
    --post_content="$content" \
    --porcelain
}

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

ensure_core_install "live" "$BASE_URL" "NueveCuatroUno Live" "n9liveadmin" "$live_admin_password" "live-admin@nuevecuatrouno.test"
ensure_core_install "archive" "$ARCHIVE_PUBLIC_URL" "NueveCuatroUno Archive" "n9archiveadmin" "$archive_admin_password" "archive-admin@nuevecuatrouno.test"

ensure_option "live" "permalink_structure" "/%year%/%monthnum%/%day%/%postname%/"
ensure_option "archive" "permalink_structure" "/%year%/%monthnum%/%day%/%postname%/"
wp_exec "live" rewrite flush --hard >/dev/null
wp_exec "archive" rewrite flush --hard >/dev/null

live_parent_id="$(ensure_page "live" "Actualidad" "actualidad" 0 "Seccion actualidad live")"
ensure_page "live" "Post" "post" "$live_parent_id" "Live sample page"

ensure_post "archive" "Noticia" "noticia" "2019-05-15 10:00:00" "Archive sample page"

printf '%s\n' "wordpress install ensured for live and archive"
