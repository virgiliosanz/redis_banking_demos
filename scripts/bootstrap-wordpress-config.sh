#!/bin/sh
set -eu

RUNTIME_ROOT="${1:-./runtime/wp-root}"
TEMPLATE_ROOT="${2:-./wordpress/templates}"
SECRETS_DIR="${3:-./.secrets}"

render_template() {
  template_file="$1"
  output_file="$2"
  shift 2

  content="$(cat "$template_file")"

  while [ "$#" -gt 0 ]; do
    key="$1"
    value="$2"
    content="$(printf '%s' "$content" | sed "s|{{$key}}|$value|g")"
    shift 2
  done

  printf '%s\n' "$content" > "$output_file"
}

mkdir -p \
  "$RUNTIME_ROOT/current/public/wp-content" \
  "$RUNTIME_ROOT/shared/config" \
  "$RUNTIME_ROOT/shared/uploads" \
  "$RUNTIME_ROOT/shared/mu-plugins" \
  "$RUNTIME_ROOT/live/var/cache/wp-content" \
  "$RUNTIME_ROOT/archive/var/cache/wp-content"

cp "$TEMPLATE_ROOT/wp-common.php.tpl" "$RUNTIME_ROOT/shared/config/wp-common.php"

render_context() {
  context_dir="$1"

  render_template \
    "$TEMPLATE_ROOT/wp-config.php.tpl" \
    "$context_dir/wp-config.php" \
    TABLE_PREFIX "wp_" \
    AUTH_KEY_ENV "WP_AUTH_KEY" \
    AUTH_KEY_FILE "/run/project-secrets/wp-auth-key" \
    SECURE_AUTH_KEY_ENV "WP_SECURE_AUTH_KEY" \
    SECURE_AUTH_KEY_FILE "/run/project-secrets/wp-secure-auth-key" \
    LOGGED_IN_KEY_ENV "WP_LOGGED_IN_KEY" \
    LOGGED_IN_KEY_FILE "/run/project-secrets/wp-logged-in-key" \
    NONCE_KEY_ENV "WP_NONCE_KEY" \
    NONCE_KEY_FILE "/run/project-secrets/wp-nonce-key" \
    AUTH_SALT_ENV "WP_AUTH_SALT" \
    AUTH_SALT_FILE "/run/project-secrets/wp-auth-salt" \
    SECURE_AUTH_SALT_ENV "WP_SECURE_AUTH_SALT" \
    SECURE_AUTH_SALT_FILE "/run/project-secrets/wp-secure-auth-salt" \
    LOGGED_IN_SALT_ENV "WP_LOGGED_IN_SALT" \
    LOGGED_IN_SALT_FILE "/run/project-secrets/wp-logged-in-salt" \
    NONCE_SALT_ENV "WP_NONCE_SALT" \
    NONCE_SALT_FILE "/run/project-secrets/wp-nonce-salt"
}

render_context "$RUNTIME_ROOT/current/public"

printf '%s\n' "wordpress config bootstrap created under $RUNTIME_ROOT"
