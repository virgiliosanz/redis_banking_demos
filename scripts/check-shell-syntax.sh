#!/bin/sh
set -eu

SHELL_FILES="$(find scripts -type f -name '*.sh' | sort)"

if [ -z "$SHELL_FILES" ]; then
  echo "No shell files found under scripts/" >&2
  exit 1
fi

echo "==> shell syntax"
printf '%s\n' "$SHELL_FILES" | while IFS= read -r file; do
  [ -n "$file" ] || continue
  sh -n "$file"
done

echo "==> ok"
