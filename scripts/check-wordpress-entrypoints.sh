#!/bin/sh
set -eu

if [ ! -d "./runtime/wp-root/current/public" ]; then
  printf '%s\n' "==> skipped (no runtime bootstrapped)"
  exit 0
fi

check_index() {
  target="$1"
  grep -q "wp-blog-header.php" "$target"
}

check_index "./runtime/wp-root/current/public/index.php"

printf '%s\n' "==> ok"
