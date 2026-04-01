#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"

./scripts/bootstrap-local-secrets.sh
./scripts/bootstrap-wordpress-config.sh "$ROOT"
./scripts/bootstrap-wordpress-stubs.sh "$ROOT"

printf '%s\n' "runtime bootstrap completed under $ROOT"
