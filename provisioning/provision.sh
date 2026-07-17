#!/usr/bin/env bash
# Federation wiring, driven by the WSO2 control plane.
# All six APIs are created in the WSO2 Publisher and published to the Dev Portal.
# Each API is deployed to ONE gateway environment; for the federated (Kong,
# ThirdParty) environments the control plane's own custom gateway connector
# (KongLocal / HomeGrown) pushes the API to the real runtime.
set -uo pipefail

CP=${CONTROL_PLANE_URL:-https://control-plane:9443}
TPGW=${THIRD_PARTY_GW_URL:-http://mock-gateway:8090}
KONG_ADMIN=${KONG_ADMIN_URL:-http://kong:8001}

wait_for() { # name url
  local name=$1 url=$2 i=0
  printf "waiting for %s " "$name"
  until curl -sk -o /dev/null "$url" 2>/dev/null; do
    i=$((i+1)); [ $i -gt 120 ] && { echo " TIMEOUT"; return 1; }
    printf "."; sleep 2
  done
  echo " ready"
}

echo ""
echo "============================================================"
echo "  WSO2 GATEWAY FEDERATION — control-plane driven init"
echo "============================================================"

wait_for "mock-gateway"   "$TPGW/health"           || echo "!! mock gateway not ready"
wait_for "kong-admin"     "$KONG_ADMIN"            || echo "!! kong admin not ready"
wait_for "control-plane"  "$CP/services/Version"   || { echo "!! control plane not reachable"; exit 1; }

bash /provisioning/deploy-apis.sh || echo "!! federation reported errors (see log above)"

echo "============================================================"
echo "  FEDERATION INIT COMPLETE"
echo "============================================================"
