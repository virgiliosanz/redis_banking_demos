#!/bin/sh
set -eu

RUNTIME_ROOT="${1:-./runtime/wp-root}"
MU_PLUGIN_SOURCE_ROOT="${2:-./wordpress/mu-plugins}"

mkdir -p \
  "$RUNTIME_ROOT/shared/config" \
  "$RUNTIME_ROOT/shared/uploads" \
  "$RUNTIME_ROOT/shared/mu-plugins" \
  "$RUNTIME_ROOT/live/var/cache/wp-content" \
  "$RUNTIME_ROOT/archive/var/cache/wp-content" \
  "$RUNTIME_ROOT/admin-live/var/cache/wp-content" \
  "$RUNTIME_ROOT/admin-archive/var/cache/wp-content"

printf '%s\n' "Shared uploads persistence probe" > "$RUNTIME_ROOT/shared/uploads/persistence-probe.txt"

if [ -d "$MU_PLUGIN_SOURCE_ROOT" ]; then
  rsync -a --delete "$MU_PLUGIN_SOURCE_ROOT/" "$RUNTIME_ROOT/shared/mu-plugins/"
else
  cat > "$RUNTIME_ROOT/shared/mu-plugins/.bootstrap-placeholder.php" <<'EOF'
<?php
// Shared mu-plugin bootstrap placeholder.
EOF
fi

printf '%s\n' "wordpress layout prepared under $RUNTIME_ROOT"
