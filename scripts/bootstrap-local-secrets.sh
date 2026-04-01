#!/bin/sh
set -eu

SECRETS_DIR="${1:-./.secrets}"

mkdir -p "$SECRETS_DIR"

write_if_missing() {
  path="$1"
  value="$2"

  if [ ! -f "$path" ]; then
    printf '%s\n' "$value" > "$path"
    chmod 600 "$path"
  fi
}

write_if_missing "$SECRETS_DIR/db-live-root-password" "n9-live-root-local-$(date +%s)-$$"
write_if_missing "$SECRETS_DIR/db-live-user-password" "n9-live-user-local-$(date +%s)-$$"
write_if_missing "$SECRETS_DIR/db-archive-root-password" "n9-archive-root-local-$(date +%s)-$$"
write_if_missing "$SECRETS_DIR/db-archive-user-password" "n9-archive-user-local-$(date +%s)-$$"

write_if_missing "$SECRETS_DIR/wp-live-db-password" "n9-wp-live-db-local-$(date +%s)-$$"
write_if_missing "$SECRETS_DIR/wp-archive-db-password" "n9-wp-archive-db-local-$(date +%s)-$$"

for key in \
  wp-auth-key \
  wp-secure-auth-key \
  wp-logged-in-key \
  wp-nonce-key \
  wp-auth-salt \
  wp-secure-auth-salt \
  wp-logged-in-salt \
  wp-nonce-salt
do
  write_if_missing "$SECRETS_DIR/$key" "generated-${key}-$(date +%s)-$$"
done

printf '%s\n' "local secrets ensured under $SECRETS_DIR"
