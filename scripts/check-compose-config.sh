#!/bin/sh
set -eu

PROJECT_ROOT="${1:-.}"
SECRETS_DIR="$PROJECT_ROOT/.secrets"
TEMP_FILES=""

cleanup() {
  for file in $TEMP_FILES; do
    [ -n "$file" ] || continue
    rm -f "$file"
  done
}

trap cleanup EXIT INT TERM

ensure_secret_file() {
  target="$1"
  example="$2"

  if [ -f "$target" ]; then
    return 0
  fi

  mkdir -p "$(dirname "$target")"
  if [ -f "$example" ]; then
    cp "$example" "$target"
  else
    umask 077
    printf '%s\n' "placeholder-secret" > "$target"
  fi
  chmod 600 "$target"
  TEMP_FILES="$TEMP_FILES $target"
}

echo "==> compose config"
ensure_secret_file "$SECRETS_DIR/db-live-root-password" "$SECRETS_DIR/db-live-root-password.example"
ensure_secret_file "$SECRETS_DIR/db-live-user-password" "$SECRETS_DIR/db-live-user-password.example"
ensure_secret_file "$SECRETS_DIR/db-archive-root-password" "$SECRETS_DIR/db-archive-root-password.example"
ensure_secret_file "$SECRETS_DIR/db-archive-user-password" "$SECRETS_DIR/db-archive-user-password.example"

docker compose config >/dev/null

echo "==> ok"
