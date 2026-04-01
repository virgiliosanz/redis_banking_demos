#!/bin/sh
set -eu

wait_for_service() {
  service_name="$1"

  until [ "$(docker inspect --format='{{.State.Health.Status}}' "$service_name" 2>/dev/null)" = "healthy" ]; do
    sleep 2
  done
}

wp_exec() {
  docker compose exec -T --user root cron-master wp --allow-root "$@"
}

ensure_page() {
  path="$1"
  title="$2"
  slug="$3"
  parent_id="${4:-0}"
  content="$5"

  existing_id="$(wp_exec post list --path="$path" --post_type=page --name="$slug" --post_parent="$parent_id" --field=ID --posts_per_page=1 2>/dev/null || true)"
  if [ -n "$existing_id" ]; then
    wp_exec post update \
      --path="$path" \
      "$existing_id" \
      --post_status=publish \
      --post_title="$title" \
      --post_name="$slug" \
      --post_parent="$parent_id" \
      --post_content="$content" >/dev/null
    printf '%s\n' "$existing_id"
    return 0
  fi

  wp_exec post create \
    --path="$path" \
    --post_type=page \
    --post_status=publish \
    --post_title="$title" \
    --post_name="$slug" \
    --post_parent="$parent_id" \
    --post_content="$content" \
    --porcelain
}

ensure_category() {
  path="$1"
  name="$2"
  slug="$3"

  existing_id="$(wp_exec term list category --path="$path" --slug="$slug" --field=term_id 2>/dev/null || true)"
  if [ -n "$existing_id" ]; then
    printf '%s\n' "$existing_id"
    return 0
  fi

  wp_exec term create category "$name" --slug="$slug" --path="$path" --porcelain
}

ensure_post() {
  path="$1"
  title="$2"
  slug="$3"
  post_date="$4"
  category_id="$5"
  content="$6"

  existing_id="$(wp_exec post list --path="$path" --post_type=post --name="$slug" --field=ID --posts_per_page=1 2>/dev/null || true)"
  if [ -n "$existing_id" ]; then
    wp_exec post update \
      --path="$path" \
      "$existing_id" \
      --post_status=publish \
      --post_title="$title" \
      --post_name="$slug" \
      --post_date="$post_date" \
      --post_content="$content" >/dev/null
    wp_exec post term set "$existing_id" category "$category_id" --path="$path" >/dev/null
    printf '%s\n' "$existing_id"
    return 0
  fi

  post_id="$(
    wp_exec post create \
      --path="$path" \
      --post_type=post \
      --post_status=publish \
      --post_title="$title" \
      --post_name="$slug" \
      --post_date="$post_date" \
      --post_content="$content" \
      --porcelain
  )"
  wp_exec post term set "$post_id" category "$category_id" --path="$path" >/dev/null
  printf '%s\n' "$post_id"
}

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

live_actualidad_id="$(ensure_page "/srv/wp/live" "Actualidad" "actualidad" 0 "Seccion actualidad del laboratorio local.")"
ensure_page "/srv/wp/live" "Post" "post" "$live_actualidad_id" "Live sample page. Punto de prueba base para routing live."
ensure_page "/srv/wp/live" "Entrevista equipo" "entrevista-equipo" "$live_actualidad_id" "Entrevista del laboratorio live con la palabra clave rioja-laboratorio."

live_cultura_id="$(ensure_page "/srv/wp/live" "Cultura" "cultura" 0 "Seccion cultura del laboratorio.")"
ensure_page "/srv/wp/live" "Agenda local" "agenda-local" "$live_cultura_id" "Agenda local laboratorio para comprobar routing live y busqueda visible."

live_servicios_id="$(ensure_page "/srv/wp/live" "Servicios" "servicios" 0 "Seccion servicios del laboratorio.")"
ensure_page "/srv/wp/live" "Contacto redaccion" "contacto-redaccion" "$live_servicios_id" "Formulario de referencia para pruebas manuales del frontend live."

live_actualidad_category="$(ensure_category "/srv/wp/live" "Actualidad" "actualidad")"
live_cultura_category="$(ensure_category "/srv/wp/live" "Cultura" "cultura")"

ensure_post "/srv/wp/live" "Cobertura live 2026" "cobertura-live-2026" "2026-03-15 09:00:00" "$live_actualidad_category" "Cobertura live 2026 con termino compartido rioja-laboratorio y foco editorial."
ensure_post "/srv/wp/live" "Guia cultural 2025" "guia-cultural-2025" "2025-11-20 18:30:00" "$live_cultura_category" "Guia cultural 2025 para pruebas de busqueda manual y resultados live."

archive_hemeroteca_category="$(ensure_category "/srv/wp/archive" "Hemeroteca" "hemeroteca")"
archive_cultura_category="$(ensure_category "/srv/wp/archive" "Cultura" "cultura")"
archive_politica_category="$(ensure_category "/srv/wp/archive" "Politica" "politica")"

ensure_post "/srv/wp/archive" "Especial 2015" "especial-2015" "2015-02-03 08:30:00" "$archive_hemeroteca_category" "Especial 2015 de hemeroteca con claves de archivo y memoria local."
ensure_post "/srv/wp/archive" "Memoria 2018" "memoria-2018" "2018-10-21 07:45:00" "$archive_politica_category" "Memoria hemeroteca 2018 con palabra clave rioja-laboratorio para busqueda unificada."
ensure_post "/srv/wp/archive" "Noticia" "noticia" "2019-05-15 10:00:00" "$archive_hemeroteca_category" "Archive sample page. Punto de prueba base para routing archive."
ensure_post "/srv/wp/archive" "Archivo cultural 2021" "archivo-cultural-2021" "2021-06-07 12:00:00" "$archive_cultura_category" "Archivo cultural 2021 para validar rutas anuales y resultados de busqueda."
ensure_post "/srv/wp/archive" "Balance 2023" "balance-2023" "2023-12-29 20:15:00" "$archive_hemeroteca_category" "Balance 2023 del archivo historico listo para pruebas manuales."

printf '%s\n' "wordpress seed content ensured for live and archive"
