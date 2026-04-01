#!/bin/sh
set -eu

./scripts/smoke-services.sh
./scripts/smoke-routing.sh
./scripts/smoke-persistence.sh
./scripts/smoke-cache-isolation.sh
./scripts/smoke-cache-policy.sh
./scripts/smoke-search.sh

printf '%s\n' "functional validation complete"
