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

cp "$SECRETS_DIR/db-live-user-password" "$SECRETS_DIR/wp-live-db-password"
cp "$SECRETS_DIR/db-archive-user-password" "$SECRETS_DIR/wp-archive-db-password"
chmod 600 "$SECRETS_DIR/wp-live-db-password" "$SECRETS_DIR/wp-archive-db-password"

write_if_missing "$SECRETS_DIR/wp-live-admin-password" "n9-live-admin-local-$(date +%s)-$$"
write_if_missing "$SECRETS_DIR/wp-archive-admin-password" "n9-archive-admin-local-$(date +%s)-$$"

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
