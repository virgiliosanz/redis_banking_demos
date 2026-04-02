#!/bin/sh
set -eu

exec python3 -m ops.cli.ia_ops send-telegram-test "$@"
