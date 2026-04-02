#!/bin/sh
set -eu

PHP_FILES="$(find scripts -type f -name '*.php' | sort)"

if [ -z "$PHP_FILES" ]; then
  echo "No PHP files found under scripts/" >&2
  exit 1
fi

run_host_php() {
  printf '%s\n' "$PHP_FILES" | while IFS= read -r file; do
    [ -n "$file" ] || continue
    php -l "$file" >/dev/null
  done
}

run_docker_php() {
  image="${PHP_LINT_DOCKER_IMAGE:-php:8.2-cli-alpine}"
  docker run --rm \
    -v "$PWD:/work" \
    -w /work \
    "$image" \
    sh -eu -c '
      find scripts -type f -name '"'"'*.php'"'"' | sort | while IFS= read -r file; do
        [ -n "$file" ] || continue
        php -l "$file" >/dev/null
      done
    '
}

echo "==> php syntax"
if command -v php >/dev/null 2>&1; then
  run_host_php
elif command -v docker >/dev/null 2>&1; then
  run_docker_php
else
  echo "php or docker is required to lint PHP scripts" >&2
  exit 1
fi

echo "==> ok"
