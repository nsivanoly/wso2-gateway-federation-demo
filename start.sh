#!/usr/bin/env bash
# ============================================================================
# One-command bring-up for the WSO2 Gateway Federation demo.
#
#   First run : verifies prerequisites, generates .env, builds the connector
#               JARs and images, starts every service, then wires federation.
#   Later runs: reuses .env, skips one-time setup, and just starts the stack
#               (federation wiring is self-healing and only re-runs if needed).
#
# Safe to run repeatedly (idempotent).
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MARKER=".demo-initialized"
COMPOSE="docker compose"

# ---- output helpers ----------------------------------------------------------
c_ok(){   printf "  \033[32m✓\033[0m %s\n" "$1"; }
c_wait(){ printf "  \033[33m…\033[0m %s\n" "$1"; }
c_err(){  printf "  \033[31m✗\033[0m %s\n" "$1"; }
hr(){ echo "------------------------------------------------------------"; }
ask(){ # prompt default -> echoes the answer (default when non-interactive)
  local prompt=$1 def=$2 ans=""
  if [ -t 0 ]; then read -r -p "$prompt" ans || true; fi
  echo "${ans:-$def}"
}

# ---- 1. prerequisites --------------------------------------------------------
echo "Checking prerequisites..."
command -v docker >/dev/null 2>&1 || { c_err "docker not found — install Docker Desktop / Engine"; exit 1; }
docker compose version >/dev/null 2>&1 || { c_err "docker compose v2 not found"; exit 1; }
docker info >/dev/null 2>&1 || { c_err "Docker daemon not running — start Docker and retry"; exit 1; }
c_ok "Docker and Docker Compose available"

# ---- 2. environment file (generate from sample on first run) -----------------
if [ ! -f .env ]; then
  cp .env.sample .env
  c_ok ".env created from .env.sample (using default values — edit .env to customise)"
else
  c_ok ".env found — reusing existing configuration"
fi
# shellcheck disable=SC1091
set -a; . ./.env; set +a

# ---- build option ------------------------------------------------------------
hr
echo "⚙️  Choose build option"
echo "   1) Build with cache"
echo "   2) Build without cache"
echo "   3) Skip build (default)"
case "$(ask "Select [1/2/3] (default 3): " 3)" in
  1) BUILD_FLAGS="--build" ;;
  2) BUILD_FLAGS="--build --no-cache" ;;
  *) BUILD_FLAGS="" ;;
esac

# ---- cleanup option ----------------------------------------------------------
hr
echo "🧹 Choose cleanup option before starting"
echo "   1) Clean start (remove containers + volumes, re-initialize)"
echo "   2) Keep existing (default)"
echo "   3) Exit"
case "$(ask "Select [1/2/3] (default 2): " 2)" in
  1) c_wait "Removing containers and volumes..."; $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; rm -f "$MARKER" ;;
  3) echo "Bye."; exit 0 ;;
  *) : ;;
esac
hr

# ---- 3. build the custom gateway connector JARs (once, cached) ---------------
# The control-plane image bakes these OSGi bundles into dropins/ at image-build
# time. We build them here only if the JAR is missing, using a persistent Maven
# cache volume (gateway-federation-m2) so the first run is a one-off.
build_connector() { # dir module jarglob label
  local dir=$1 module=$2 jarglob=$3 label=$4
  if ls "$dir"/$jarglob >/dev/null 2>&1; then c_ok "$label connector JAR present"; return; fi
  c_wait "Building $label connector (first time only; Maven cache persists)..."
  if docker run --rm -v "$PWD/$dir":/build -v gateway-federation-m2:/root/.m2 -w /build \
       maven:3.9-eclipse-temurin-11 mvn -q -B -pl "$module" -am package -DskipTests >/dev/null 2>&1; then
    c_ok "$label connector built"
  else
    c_err "$label connector build failed — check Maven output"; exit 1
  fi
}
build_connector "gateway-connectors/homegrown" "components/homegrown.gw.manager" \
  "components/homegrown.gw.manager/target/homegrown.gw.manager-*.jar" HomeGrown
build_connector "gateway-connectors/konglocal" "components/konglocal.gw.manager" \
  "components/konglocal.gw.manager/target/konglocal.gw.manager-*.jar" KongLocal
hr

# ---- 4. bring up runtime services (the init profile is excluded by default) --
echo "Starting services..."
# shellcheck disable=SC2086
$COMPOSE up -d $BUILD_FLAGS \
  backend control-plane kong-database kong-migrations kong mock-gateway dashboard >/dev/null 2>&1
c_ok "Containers started"

# ---- 5. readiness ------------------------------------------------------------
wait_http(){ # label url expected-code-glob
  local label=$1 url=$2 want=$3 i=0 code
  while :; do
    code=$(curl -sk -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo 000)
    case "$code" in $want) c_ok "$label"; return 0;; esac
    i=$((i+1)); [ $i -gt 90 ] && { c_err "$label (last HTTP $code)"; return 1; }
    sleep 3
  done
}
c_wait "Waiting for services (control plane takes ~1-2 min on first boot)..."
wait_http "Backend"             "http://localhost:${BACKEND_PORT:-4000}/health"                          "200"
wait_http "Kong admin"          "http://localhost:${KONG_ADMIN_PORT:-8001}/status"                       "200"
wait_http "Mock gateway"        "http://localhost:${MOCK_GATEWAY_PORT:-8090}/health"                     "200"
wait_http "WSO2 control plane"  "https://localhost:${CONTROL_PLANE_HTTPS_PORT:-9443}/services/Version"   "200"
wait_http "Dashboard"           "http://localhost:${DASHBOARD_PORT:-3000}/api/health"                    "200"
hr

# ---- 6. federation wiring (idempotent, self-healing) -------------------------
# Authoritative readiness: is every gateway already serving its own APIs? This
# survives container recreation / volume loss, unlike a bare marker file.
code(){ curl -sk -o /dev/null -w "%{http_code}" "$1" 2>/dev/null || echo 000; }
federation_ready(){
  [ "$(code https://localhost:${CONTROL_PLANE_GATEWAY_HTTPS_PORT:-8243}/employee/employees)" = 200 ] &&
  [ "$(code http://localhost:${KONG_PROXY_PORT:-8000}/vehicle/v1/vehicles)" = 200 ] &&
  [ "$(code http://localhost:${MOCK_GATEWAY_PORT:-8090}/citizen/v1/citizens)" = 200 ]
}

if [ -f "$MARKER" ] && federation_ready; then
  c_ok "Environment already initialized — skipping federation wiring"
else
  echo "Wiring gateway federation..."
  if $COMPOSE run --rm init; then
    date > "$MARKER"
    c_ok "Federation wiring complete"
  else
    c_err "Federation wiring reported errors (see log above)"
  fi
fi

c_wait "Waiting for federation to settle..."
s=0
until federation_ready; do
  s=$((s+1)); [ $s -gt 40 ] && { c_err "Some gateways not serving yet; give it a few more seconds"; break; }
  sleep 3
done
federation_ready && c_ok "All gateways serving their APIs"
hr

# ---- 7. summary --------------------------------------------------------------
cat <<EOF
✅ Demo Ready

CONSOLES & UIs
  ★ Federation Dashboard :  http://localhost:${DASHBOARD_PORT:-3000}
  WSO2 Publisher         :  https://localhost:${CONTROL_PLANE_HTTPS_PORT:-9443}/publisher    (${WSO2_ADMIN_USER:-admin}/${WSO2_ADMIN_PASSWORD:-admin})
  WSO2 Dev Portal        :  https://localhost:${CONTROL_PLANE_HTTPS_PORT:-9443}/devportal
  WSO2 Admin             :  https://localhost:${CONTROL_PLANE_HTTPS_PORT:-9443}/admin
  Mock gateway console   :  http://localhost:${MOCK_GATEWAY_PORT:-8090}
  Kong Manager           :  http://localhost:${KONG_ADMIN_GUI_PORT:-8002}

GATEWAY ENDPOINTS (proxy)
  WSO2 gateway   :  https://localhost:${CONTROL_PLANE_GATEWAY_HTTPS_PORT:-8243}   (HTTP: ${CONTROL_PLANE_GATEWAY_HTTP_PORT:-8280})
  Kong gateway   :  http://localhost:${KONG_PROXY_PORT:-8000}
  Third-party GW :  http://localhost:${MOCK_GATEWAY_PORT:-8090}

APIs / MANAGEMENT
  Kong Admin API :  http://localhost:${KONG_ADMIN_PORT:-8001}
  WSO2 REST APIs :  https://localhost:${CONTROL_PLANE_HTTPS_PORT:-9443}/api/am/{publisher,admin,devportal}/v4
  Backend (mock) :  http://localhost:${BACKEND_PORT:-4000}   (e.g. /employee/employees)

TRY IT
  WSO2 gateway   :  curl -k https://localhost:${CONTROL_PLANE_GATEWAY_HTTPS_PORT:-8243}/employee/employees
                    curl -k https://localhost:${CONTROL_PLANE_GATEWAY_HTTPS_PORT:-8243}/leave
  Kong gateway   :  curl http://localhost:${KONG_PROXY_PORT:-8000}/vehicle/v1/vehicles
                    curl http://localhost:${KONG_PROXY_PORT:-8000}/parking/v1/parking
  Third-party GW :  curl http://localhost:${MOCK_GATEWAY_PORT:-8090}/citizen/v1/citizens
                    curl http://localhost:${MOCK_GATEWAY_PORT:-8090}/payment/v1/payments

  (Native WSO2 APIs serve at their plain context; federated Kong/third-party
   routes are versioned as /<context>/v1/<resource>.)
  Stop the demo  :  ./stop.sh
EOF
