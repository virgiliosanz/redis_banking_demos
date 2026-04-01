#!/bin/sh
set -eu

RUNTIME_ROOT="${1:-./runtime/wp-root}"

mkdir -p \
  "$RUNTIME_ROOT/shared/config" \
  "$RUNTIME_ROOT/shared/uploads" \
  "$RUNTIME_ROOT/shared/mu-plugins" \
  "$RUNTIME_ROOT/live/var/cache/wp-content" \
  "$RUNTIME_ROOT/archive/var/cache/wp-content" \
  "$RUNTIME_ROOT/admin-live/var/cache/wp-content" \
  "$RUNTIME_ROOT/admin-archive/var/cache/wp-content"

printf '%s\n' "Shared uploads persistence probe" > "$RUNTIME_ROOT/shared/uploads/persistence-probe.txt"
cat > "$RUNTIME_ROOT/shared/mu-plugins/.bootstrap-placeholder.php" <<'EOF'
<?php
// Shared mu-plugin bootstrap placeholder.
EOF

printf '%s\n' "wordpress layout prepared under $RUNTIME_ROOT"
