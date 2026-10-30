#!/bin/sh
# Run the minis-mcp-cli host regression suite in a disposable Python environment.
# This keeps the user's Python and the app's sandbox dependencies untouched.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP_BASE=${TMPDIR:-/tmp}
VENV_DIR=$(mktemp -d "$TMP_BASE/minis-mcp-cli-tests.XXXXXX")
cleanup() {
    rm -rf "$VENV_DIR"
}
trap cleanup EXIT HUP INT TERM

python3 -m venv "$VENV_DIR/venv"
"$VENV_DIR/venv/bin/python" -m pip install --disable-pip-version-check -q -r "$SCRIPT_DIR/requirements-test.txt"
"$VENV_DIR/venv/bin/python" -m unittest discover -s "$SCRIPT_DIR" -p 'test_*.py' -v
