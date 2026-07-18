#!/usr/bin/env bash
# ============================================================================
# Teardown for the WSO2 Gateway Federation demo.
#
# The default is a graceful stop that PRESERVES everything (containers, volumes,
# images, .env, and the connector build cache), so the next ./start.sh resumes
# in seconds. Every destructive step is opt-in and clearly labelled.
#
# The connector build cache (the Maven cache volume + the compiled connector
# JARs) is treated separately from application state: it is expensive to rebuild
# (a cold Maven build pulls WSO2 dependencies) but cheap to keep, so it is only
# ever removed when you explicitly ask. Safe to run repeatedly (idempotent).
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MARKER=".demo-initialized"
COMPOSE="docker compose"
M2_VOLUME="gateway-federation-m2"
CONNECTOR_TARGETS=(
  "gateway-connectors/homegrown/components/homegrown.gw.manager/target"
  "gateway-connectors/konglocal/components/konglocal.gw.manager/target"
)

# ---- output helpers ----------------------------------------------------------
c_ok(){   printf "  \033[32m✓\033[0m %s\n" "$1"; }
c_info(){ printf "  \033[36m•\033[0m %s\n" "$1"; }
c_warn(){ printf "  \033[33m!\033[0m %s\n" "$1"; }
hr(){ echo "------------------------------------------------------------"; }
ask(){ local prompt=$1 def=$2 ans=""; if [ -t 0 ]; then read -r -p "$prompt" ans || true; fi; echo "${ans:-$def}"; }
yesish(){ case "$1" in [Yy]|[Yy][Ee][Ss]) return 0;; *) return 1;; esac; }

# ---- remove the connector build cache (Maven cache volume + compiled JARs) ----
clean_connector_build(){
  local removed=0
  for t in "${CONNECTOR_TARGETS[@]}"; do
    if [ -d "$t" ]; then rm -rf "$t" && removed=1; fi
  done
  docker volume rm "$M2_VOLUME" >/dev/null 2>&1 && removed=1 || true
  if [ "$removed" = 1 ]; then
    c_ok "Connector build cache removed (JARs + Maven cache volume '$M2_VOLUME')"
    c_warn "Next ./start.sh will do a full connector rebuild (slower, one-off)"
  else
    c_info "No connector build cache found — nothing to remove"
  fi
}

# ============================================================================
echo "🛑 WSO2 Gateway Federation demo — shutdown"
hr
echo "How much do you want to tear down?"
echo "   1) Graceful stop (default)  — stop containers; keep volumes, images, .env"
echo "                                 → fastest resume, nothing is lost"
echo "   2) Stop + remove volumes    — wipe application state (re-provisions next start)"
echo "   3) Full cleanup             — also remove images built by this demo"
echo
echo "   The connector build cache is always kept unless you choose otherwise below."
CHOICE=$(ask "Select [1/2/3] (default 1): " 1)
hr

case "$CHOICE" in
  2)
    echo "Stopping containers and removing volumes..."
    $COMPOSE --profile init down -v --remove-orphans
    rm -f "$MARKER"
    c_ok "Containers and application-state volumes removed (.env preserved)"
    CLEAN_BUILD=$(ask "Also remove the connector build cache (forces a full rebuild)? [y/N]: " n)
    yesish "$CLEAN_BUILD" && clean_connector_build || c_info "Connector build cache kept — next start reuses the built JARs"
    SUMMARY="Application state wiped. Run ./start.sh to re-provision."
    ;;
  3)
    echo "Full cleanup: containers, volumes, and demo images..."
    $COMPOSE --profile init down -v --rmi local --remove-orphans
    rm -f "$MARKER"
    c_ok "Containers, volumes, and demo images removed (.env preserved)"
    CLEAN_BUILD=$(ask "Also remove the connector build cache (forces a full rebuild)? [y/N]: " n)
    yesish "$CLEAN_BUILD" && clean_connector_build || c_info "Connector build cache kept — next start reuses the built JARs"
    SUMMARY="Containers, volumes, and images removed. Run ./start.sh to rebuild and re-provision."
    ;;
  *)
    echo "Graceful stop (all data preserved)..."
    $COMPOSE stop
    c_ok "Services stopped"
    SUMMARY="Nothing was removed — run ./start.sh to resume in seconds."
    ;;
esac

hr
echo "✅ $SUMMARY"
