#!/bin/sh
set -eu

PYTHON_BIN="${PYTHON_BIN:-python3}"
PY_FILES="$(find ops -type f -name '*.py' | sort)"

if [ -z "$PY_FILES" ]; then
  echo "No Python files found under ops/" >&2
  exit 1
fi

echo "==> py_compile"
# shellcheck disable=SC2086
$PYTHON_BIN -m py_compile $PY_FILES

echo "==> cli help"
$PYTHON_BIN -m ops.cli.ia_ops --help >/dev/null

if [ -d "./tests" ]; then
  echo "==> unit tests"
  $PYTHON_BIN -m unittest discover -s tests -p 'test_*.py'
fi

echo "==> ok"
