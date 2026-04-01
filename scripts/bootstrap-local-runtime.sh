#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"

./scripts/bootstrap-local-secrets.sh
./scripts/bootstrap-wordpress-layout.sh "$ROOT"
./scripts/bootstrap-wordpress-core.sh "$ROOT"
./scripts/bootstrap-wordpress-config.sh "$ROOT"

printf '%s\n' "runtime bootstrap completed under $ROOT"
