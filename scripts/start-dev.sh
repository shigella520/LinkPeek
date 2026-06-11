#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_DIR="$ROOT_DIR/.cache/linkpeek-dev"

export PORT="${PORT:-8080}"
export BASE_URL="${BASE_URL:-http://localhost:${PORT}}"
export CACHE_DIR="${CACHE_DIR:-$DEV_DIR/cache}"
export STATS_DB_PATH="${STATS_DB_PATH:-$DEV_DIR/stats/linkpeek.db}"
export LOG_FILE_PATH="${LOG_FILE_PATH:-$DEV_DIR/logs/linkpeek.log}"
export STATS_ADMIN_PASSWORD="${STATS_ADMIN_PASSWORD:-test}"

mkdir -p "$CACHE_DIR" "$(dirname "$STATS_DB_PATH")" "$(dirname "$LOG_FILE_PATH")"

echo "Starting LinkPeek dev server"
echo "Admin URL: $BASE_URL/admin"
echo "Admin password: $STATS_ADMIN_PASSWORD"

cd "$ROOT_DIR"
exec ./mvnw -pl linkpeek-server -am spring-boot:run
