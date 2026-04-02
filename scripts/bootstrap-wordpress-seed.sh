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

delete_post_by_slug() {
  path="$1"
  slug="$2"

  post_ids="$(wp_exec post list --path="$path" --post_type=post --name="$slug" --field=ID 2>/dev/null || true)"
  if [ -z "$post_ids" ]; then
    return 0
  fi

  for post_id in $post_ids; do
    wp_exec post delete "$post_id" --force --path="$path" >/dev/null
  done
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
    printf '%s\n' "$slug"
    return 0
  fi

  wp_exec term create category "$name" --slug="$slug" --path="$path" --porcelain >/dev/null
  printf '%s\n' "$slug"
}

delete_numeric_categories() {
  path="$1"

  numeric_term_ids="$(wp_exec term list category --path="$path" --fields=term_id,slug --format=csv 2>/dev/null | awk -F, 'NR > 1 && $2 ~ /^[0-9]+$/ { print $1 }')"
  if [ -z "$numeric_term_ids" ]; then
    return 0
  fi

  for term_id in $numeric_term_ids; do
    wp_exec term delete category "$term_id" --path="$path" >/dev/null
  done
}

ensure_post() {
  path="$1"
  title="$2"
  slug="$3"
  post_date="$4"
  category_slug="$5"
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
    wp_exec post term set "$existing_id" category "$category_slug" --path="$path" >/dev/null
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
  wp_exec post term set "$post_id" category "$category_slug" --path="$path" >/dev/null
  printf '%s\n' "$post_id"
}

wait_for_service n9-db-live
wait_for_service n9-db-archive
wait_for_service n9-cron-master

delete_numeric_categories "/srv/wp/live"
delete_numeric_categories "/srv/wp/archive"

delete_post_by_slug "/srv/wp/live" "hello-world"
delete_post_by_slug "/srv/wp/live" "cobertura-live-2026"
delete_post_by_slug "/srv/wp/live" "guia-cultural-2025"

delete_post_by_slug "/srv/wp/archive" "hello-world"
delete_post_by_slug "/srv/wp/archive" "especial-2015"
delete_post_by_slug "/srv/wp/archive" "memoria-2018"
delete_post_by_slug "/srv/wp/archive" "noticia"
delete_post_by_slug "/srv/wp/archive" "archivo-cultural-2021"
delete_post_by_slug "/srv/wp/archive" "balance-2023"

live_actualidad_id="$(ensure_page "/srv/wp/live" "Actualidad" "actualidad" 0 "Seccion actualidad del laboratorio local.")"
ensure_page "/srv/wp/live" "Post" "post" "$live_actualidad_id" "Live sample page. Punto de prueba base para routing live."
ensure_page "/srv/wp/live" "Entrevista equipo" "entrevista-equipo" "$live_actualidad_id" "Entrevista del laboratorio live con la palabra clave rioja-laboratorio."

live_cultura_id="$(ensure_page "/srv/wp/live" "Cultura" "cultura" 0 "Seccion cultura del laboratorio.")"
ensure_page "/srv/wp/live" "Agenda local" "agenda-local" "$live_cultura_id" "Agenda local laboratorio para comprobar routing live y busqueda visible."

live_servicios_id="$(ensure_page "/srv/wp/live" "Servicios" "servicios" 0 "Seccion servicios del laboratorio.")"
ensure_page "/srv/wp/live" "Contacto redaccion" "contacto-redaccion" "$live_servicios_id" "Formulario de referencia para pruebas manuales del frontend live."

live_actualidad_category="$(ensure_category "/srv/wp/live" "Actualidad" "actualidad")"
live_cultura_category="$(ensure_category "/srv/wp/live" "Cultura" "cultura")"
live_sociedad_category="$(ensure_category "/srv/wp/live" "Sociedad" "sociedad")"

archive_hemeroteca_category="$(ensure_category "/srv/wp/archive" "Hemeroteca" "hemeroteca")"
archive_cultura_category="$(ensure_category "/srv/wp/archive" "Cultura" "cultura")"
archive_politica_category="$(ensure_category "/srv/wp/archive" "Politica" "politica")"
archive_sociedad_category="$(ensure_category "/srv/wp/archive" "Sociedad" "sociedad")"

ensure_post "/srv/wp/archive" "Logrono revive la noche de San Mateo en su casco antiguo" "logrono-revive-la-noche-de-san-mateo-en-su-casco-antiguo" "2015-02-03 08:30:00" "$archive_hemeroteca_category" "Hemeroteca 2015 con cronica local y referencia historica."
ensure_post "/srv/wp/archive" "El Ebro marca un verano de contrastes en Logrono" "el-ebro-marca-un-verano-de-contrastes-en-logrono" "2016-07-14 09:15:00" "$archive_sociedad_category" "Archivo 2016 con foco social y urbano."
ensure_post "/srv/wp/archive" "Las cuadrillas vuelven a llenar de musica las calles del centro" "las-cuadrillas-vuelven-a-llenar-de-musica-las-calles-del-centro" "2017-09-09 11:20:00" "$archive_cultura_category" "Archivo 2017 con cobertura cultural y popular."
ensure_post "/srv/wp/archive" "La vendimia abre una nueva etapa para el Rioja metropolitano" "la-vendimia-abre-una-nueva-etapa-para-el-rioja-metropolitano" "2018-10-21 07:45:00" "$archive_politica_category" "Memoria hemeroteca 2018 con palabra clave rioja-laboratorio para busqueda unificada."
ensure_post "/srv/wp/archive" "Logrono activa su plan de barrios con inversiones en movilidad" "logrono-activa-su-plan-de-barrios-con-inversiones-en-movilidad" "2019-05-15 10:00:00" "$archive_hemeroteca_category" "Archivo 2019. Punto de prueba base para routing archive."
ensure_post "/srv/wp/archive" "El comercio local resiste un verano marcado por la incertidumbre" "el-comercio-local-resiste-un-verano-marcado-por-la-incertidumbre" "2020-08-27 12:10:00" "$archive_sociedad_category" "Archivo 2020 con trazas de recuperacion economica."
ensure_post "/srv/wp/archive" "La agenda cultural recupera el pulso con una temporada expandida" "la-agenda-cultural-recupera-el-pulso-con-una-temporada-expandida" "2021-06-07 12:00:00" "$archive_cultura_category" "Archivo cultural 2021 para validar rutas anuales y resultados de busqueda."
ensure_post "/srv/wp/archive" "La redonda cierra un ano de reformas con mas actividad vecinal" "la-redonda-cierra-un-ano-de-reformas-con-mas-actividad-vecinal" "2022-11-18 18:05:00" "$archive_sociedad_category" "Archivo 2022 con referencia urbana y vecinal."
ensure_post "/srv/wp/archive" "El archivo municipal consolida 2023 como ano de transicion digital" "el-archivo-municipal-consolida-2023-como-ano-de-transicion-digital" "2023-12-29 20:15:00" "$archive_hemeroteca_category" "Balance 2023 del archivo historico listo para pruebas manuales."

ensure_post "/srv/wp/live" "Logrono impulsa 2024 con nuevas rutas peatonales y comercio abierto" "logrono-impulsa-2024-con-nuevas-rutas-peatonales-y-comercio-abierto" "2024-04-11 08:50:00" "$live_actualidad_category" "Cobertura live 2024 con continuidad editorial en el sitio principal."
ensure_post "/srv/wp/live" "La programacion cultural de 2025 lleva el teatro a todos los barrios" "la-programacion-cultural-de-2025-lleva-el-teatro-a-todos-los-barrios" "2025-09-19 19:30:00" "$live_cultura_category" "Cobertura live 2025 para pruebas de busqueda manual y resultados recientes."
ensure_post "/srv/wp/live" "Logrono venera la imagen del Cristo del Santo Sepulcro en La Redonda" "logrono-venera-la-imagen-del-cristo-del-santo-sepulcro-en-la-redonda" "2026-04-01 07:30:00" "$live_sociedad_category" "Cobertura live 2026 con termino compartido rioja-laboratorio y foco editorial."

printf '%s\n' "wordpress seed content ensured for live and archive"
