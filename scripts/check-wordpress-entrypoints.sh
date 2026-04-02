#!/bin/sh
set -eu

check_index() {
  target="$1"
  grep -q "wp-blog-header.php" "$target"
}

check_index "./runtime/wp-root/live/current/public/index.php"
check_index "./runtime/wp-root/archive/current/public/index.php"
check_index "./runtime/wp-root/admin-live/current/public/index.php"
check_index "./runtime/wp-root/admin-archive/current/public/index.php"

printf '%s\n' "==> ok"
