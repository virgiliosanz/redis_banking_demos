#!/bin/sh
set -eu

if [ "${1:-}" = "--print" ]; then
  shift
  exec python3 -m ops.cli.ia_ops render-nightly-crontab "$@"
fi

if [ "${1:-}" = "--remove" ]; then
  shift
  exec python3 -m ops.cli.ia_ops remove-nightly-crontab "$@"
fi

exec python3 -m ops.cli.ia_ops install-nightly-crontab "$@"
