#!/bin/sh
set -eu

./scripts/check-python-tooling.sh
./scripts/check-shell-syntax.sh
./scripts/check-scripts-layout.sh
./scripts/check-wordpress-entrypoints.sh
./scripts/check-php-syntax.sh
./scripts/check-compose-config.sh
