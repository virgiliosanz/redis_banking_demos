#!/bin/sh
set -eu

RUNTIME_ROOT="${1:-./runtime/wp-root}"
WORDPRESS_DOWNLOAD_URL="${WORDPRESS_DOWNLOAD_URL:-https://wordpress.org/latest.tar.gz}"
CACHE_ROOT="${WORDPRESS_CACHE_ROOT:-./runtime/cache/wordpress}"
ARCHIVE_PATH="$CACHE_ROOT/latest.tar.gz"
EXTRACTED_PATH="$CACHE_ROOT/latest"

mkdir -p "$CACHE_ROOT"

download_archive() {
  tmp_archive="$ARCHIVE_PATH.tmp"
  curl -fsSL "$WORDPRESS_DOWNLOAD_URL" -o "$tmp_archive"
  mv "$tmp_archive" "$ARCHIVE_PATH"
}

prepare_source_tree() {
  tmp_extract="$EXTRACTED_PATH.tmp"

  rm -rf "$tmp_extract"
  mkdir -p "$tmp_extract"
  tar -xzf "$ARCHIVE_PATH" -C "$tmp_extract"
  rm -rf "$EXTRACTED_PATH"
  mv "$tmp_extract/wordpress" "$EXTRACTED_PATH"
  rmdir "$tmp_extract"
}

install_core() {
  target_dir="$1"

  mkdir -p "$target_dir/wp-content"
  mkdir -p "$target_dir/wp-content/uploads"

  rsync -a --delete \
    --exclude 'wp-config.php' \
    --exclude 'wp-content/' \
    "$EXTRACTED_PATH/" "$target_dir/"

  rsync -a --delete \
    --exclude 'uploads/' \
    --exclude 'mu-plugins/' \
    --exclude 'cache/' \
    --exclude 'plugins/' \
    --exclude 'themes/' \
    --exclude 'languages/' \
    --exclude 'upgrade/' \
    "$EXTRACTED_PATH/wp-content/" "$target_dir/wp-content/"

  rm -f "$target_dir/wp-config-sample.php"
}

if [ ! -s "$ARCHIVE_PATH" ]; then
  download_archive
fi

prepare_source_tree

install_core "$RUNTIME_ROOT/current/public"

printf '%s\n' "wordpress core refreshed under $RUNTIME_ROOT"
