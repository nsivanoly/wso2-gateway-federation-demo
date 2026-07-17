#!/usr/bin/env bash
# ============================================================================
# Teardown for the WSO2 Gateway Federation demo.
#
# Default is a graceful stop that PRESERVES all data (volumes, .env, state), so
# the next ./start.sh resumes instantly. Destructive options require an explicit
# choice. Safe to run repeatedly (idempotent).
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MARKER=".demo-initialized"
COMPOSE="docker compose"

ask(){ local prompt=$1 def=$2 ans=""; if [ -t 0 ]; then read -r -p "$prompt" ans || true; fi; echo "${ans:-$def}"; }

echo "Choose shutdown option"
echo "   1) Graceful stop (default) — preserves all data; resume with ./start.sh"
echo "   2) Stop + remove volumes    — wipes application state (re-initializes next start)"
echo "   3) Full cleanup             — stop, remove volumes AND images built by this demo"
CHOICE=$(ask "Select [1/2/3] (default 1): " 1)

case "$CHOICE" in
  2)
    echo "Stopping containers and removing volumes..."
    $COMPOSE --profile init down -v --remove-orphans
    rm -f "$MARKER"
    SUMMARY="Containers and volumes removed. Configuration (.env) preserved."
    ;;
  3)
    echo "Full cleanup: containers, volumes, and demo images..."
    $COMPOSE --profile init down -v --rmi local --remove-orphans
    rm -f "$MARKER"
    SUMMARY="Containers, volumes, and demo images removed. Configuration (.env) preserved."
    ;;
  *)
    echo "Graceful stop (all data preserved)..."
    $COMPOSE stop
    SUMMARY="Services stopped. Data, volumes, and .env preserved — run ./start.sh to resume."
    ;;
esac

echo "------------------------------------------------------------"
echo "✅ $SUMMARY"
