#!/bin/sh
set -eu

ROOT="${1:-./runtime/wp-root}"

./scripts/bootstrap-local-runtime.sh "$ROOT"
docker compose up -d --build
./scripts/bootstrap-wordpress-install.sh
./scripts/bootstrap-elasticpress.sh

printf '%s\n' "local stack bootstrapped with wordpress and search"
