#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"

write_stub() {
  target_dir="$1"
  context_name="$2"

  mkdir -p "$target_dir"

  cat > "$target_dir/index.php" <<EOF
<?php
header('Content-Type: text/plain;charset=UTF-8');
echo "context=${context_name}\n";
echo "host=" . (\$_SERVER['HTTP_HOST'] ?? '') . "\n";
echo "request_uri=" . (\$_SERVER['REQUEST_URI'] ?? '') . "\n";
if (file_exists(__DIR__ . '/wp-config.php')) {
    echo "wp_config=present\n";
}
EOF
}

write_stub "$ROOT/live/current/public" "live"
write_stub "$ROOT/archive/current/public" "archive"
write_stub "$ROOT/admin-live/current/public" "admin-live"
write_stub "$ROOT/admin-archive/current/public" "admin-archive"

printf '%s\n' "wordpress stubs refreshed under $ROOT"
