#!/bin/sh
set -eu

root_php_files="$(find scripts -maxdepth 1 -type f -name '*.php' | sort)"

echo "==> scripts layout"

if [ -n "$root_php_files" ]; then
  printf '%s\n' "Root scripts/ must not contain internal PHP helpers:" >&2
  printf '%s\n' "$root_php_files" >&2
  exit 1
fi

if [ ! -f "scripts/internal/README.md" ]; then
  printf '%s\n' "Missing scripts/internal/README.md" >&2
  exit 1
fi

echo "==> ok"
