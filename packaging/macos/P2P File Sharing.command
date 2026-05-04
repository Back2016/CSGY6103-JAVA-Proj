#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GUI_JAR="$(ls "$SCRIPT_DIR"/lib/*-gui.jar | head -n 1)"

exec java -jar "$GUI_JAR"
