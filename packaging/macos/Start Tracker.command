#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TRACKER_JAR="$(ls "$SCRIPT_DIR"/lib/*-tracker.jar | head -n 1)"

exec java -jar "$TRACKER_JAR"
