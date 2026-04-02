#!/bin/sh
set -eu

if [ "${1:-}" = "--print" ]; then
  shift
  exec python3 -m ops.cli.ia_ops render-sync-crontab "$@"
fi

if [ "${1:-}" = "--remove" ]; then
  shift
  exec python3 -m ops.cli.ia_ops remove-sync-crontab "$@"
fi

exec python3 -m ops.cli.ia_ops install-sync-crontab "$@"
