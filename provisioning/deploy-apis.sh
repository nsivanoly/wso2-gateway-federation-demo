#!/usr/bin/env bash
# Control-plane-centric federation using WSO2's OWN custom gateway connectors.
# WSO2 API Platform is the SINGLE control plane + marketplace: all six APIs are
# created in the Publisher and published to the Dev Portal. Each API is deployed
# to ONE gateway environment:
#     Employee, Leave   -> Default    (native WSO2 gateway, served on :8243)
#     Vehicle, Parking  -> Kong       (federated via the KongLocal connector)
#     Citizen, Payment  -> ThirdParty (federated via the HomeGrown connector)
# The federated environments use custom gateway TYPES (KongLocal / HomeGrown)
# provided by connector bundles baked into the control plane. Deploying a revision
# to such an environment invokes the connector's GatewayDeployer, which pushes the
# API straight to the runtime (Kong Admin API / mock gateway) — no external agent.
set -uo pipefail

CP=${CONTROL_PLANE_URL:-https://control-plane:9443}
USER=${WSO2_ADMIN_USER:-admin}
PASS=${WSO2_ADMIN_PASSWORD:-admin}
PUB="$CP/api/am/publisher/v4"
ADMIN="$CP/api/am/admin/v4"
WORK=/tmp/wso2-projects; mkdir -p "$WORK"
log() { echo "    $*"; }

# ---- apictl login ----
apictl add env demo --apim "$CP" >/dev/null 2>&1 || true
apictl login demo -u "$USER" -p "$PASS" -k >/dev/null 2>&1 || { log "ERROR: apictl login failed"; exit 1; }

# ---- Publisher/Admin REST token ----
CREDS=$(curl -sk -u "$USER:$PASS" -H "Content-Type: application/json" \
  -d '{"callbackUrl":"https://localhost","clientName":"init","owner":"'"$USER"'","grantType":"password refresh_token","saasApp":true}' \
  "$CP/client-registration/v0.17/register")
CID=$(echo "$CREDS" | jq -r .clientId); CSEC=$(echo "$CREDS" | jq -r .clientSecret)
SCOPES="apim:api_view apim:api_create apim:api_publish apim:api_manage apim:api_import_export apim:admin"
TOK=$(curl -sk -u "$CID:$CSEC" --data-urlencode "grant_type=password" --data-urlencode "username=$USER" \
  --data-urlencode "password=$PASS" --data-urlencode "scope=$SCOPES" "$CP/oauth2/token" | jq -r .access_token)
[ -n "$TOK" ] && [ "$TOK" != "null" ] || { log "ERROR: could not obtain token"; exit 1; }
AUTH="Authorization: Bearer $TOK"

# ---- register the two federated gateway environments (idempotent) ----
# Each federated env uses a custom gateway TYPE (KongLocal / HomeGrown) and carries
# the connector's connection config in additionalProperties (admin_url/proxy_url).
register_env() { # name display desc gatewayType host httpPort httpsPort adminUrl proxyUrl
  # provider MUST be "external" for federated gateways — this is what makes WSO2
  # dispatch revision deployments to the connector's GatewayDeployer instead of the
  # internal synapse gateway manager. (Verified against a working WSO2 pack.)
  local body='{"name":"'"$1"'","displayName":"'"$2"'","provider":"external","type":"hybrid","gatewayType":"'"$4"'","mode":"READ_WRITE","apiDiscoveryScheduledWindow":1,"description":"'"$3"'",
         "additionalProperties":[
            {"key":"admin_url","value":"'"$8"'"},
            {"key":"proxy_url","value":"'"$9"'"},
            {"key":"stage","value":"default"}],
         "vhosts":[{"host":"'"$5"'","httpContext":"","httpPort":'"$6"',"httpsPort":'"$7"',"wsPort":9099,"wssPort":8099}]}'
  # Upsert: update in place if an env of this name already exists, else create.
  local eid
  eid=$(curl -sk -H "$AUTH" "$ADMIN/environments" | jq -r '.list[]|select(.name=="'"$1"'").id // empty')
  if [ -n "$eid" ]; then
    curl -sk -o /dev/null -X PUT -H "$AUTH" -H "Content-Type: application/json" -d "$body" "$ADMIN/environments/$eid" 2>/dev/null || true
  else
    curl -sk -o /dev/null -X POST -H "$AUTH" -H "Content-Type: application/json" -d "$body" "$ADMIN/environments" 2>/dev/null || true
  fi
}
# NOTE: admin_url/proxy_url must be docker SERVICE names (not localhost) — the
# connectors run inside the control-plane container and resolve peers over the
# compose network. localhost would point back at the control plane itself.
log "Registering federated gateway environments (Kong=KongLocal, ThirdParty=HomeGrown)..."
register_env Kong       "Kong Gateway (federated)"  "Agency-managed Kong runtime"     KongLocal  kong                8000 8443 "http://kong:8001"                "http://kong:8000"
register_env ThirdParty "Mock Third-Party Gateway"  "Unsupported gateway (HomeGrown)" HomeGrown  mock-gateway        8090 8091 "http://mock-gateway:8090"        "http://mock-gateway:8090"

# vhost for a given environment name
vhost_of() { case "$1" in Default) echo localhost;; Kong) echo kong;; ThirdParty) echo mock-gateway;; *) echo localhost;; esac; }

# ---- create + deploy + publish one API ----
# deploy_api <Name> <oas> <context> <backendUrl> <env> <gwType|-> [deploy=yes|no]
# gwType: the external gateway type (KongLocal / HomeGrown) so the API is created as
# an external-gateway API whose revision deployment invokes that connector's
# GatewayDeployer. Use "-" for the native WSO2 Default gateway.
# deploy=no creates + publishes the API but leaves it UNDEPLOYED (its gatewayType
# still records the intended runtime) so the dashboard can deploy it on demand.
deploy_api() {
  local name=$1 oas=$2 ctx=$3 backend=$4 env=$5 gwtype=$6 do_deploy=${7:-yes} proj="$WORK/$1"
  log "----------------------------------------------------------"
  log "$name  ->  env=$env  (context=$ctx${gwtype:+, type=$gwtype})"
  rm -rf "$proj"; apictl init "$proj" --oas "$oas" --force >/dev/null 2>&1
  sed -i \
    -e "s#context: /$name#context: $ctx#" \
    -e "s#url: http://localhost:8080#url: $backend#" \
    -e "s#url: http://localhost:8081#url: $backend#" \
    -e "s#lifeCycleStatus: CREATED#lifeCycleStatus: PUBLISHED#" \
    "$proj/api.yaml"
  # For a federated gateway, create the API as an external-gateway API of the
  # connector's type; this is what makes the revision deployment call the connector.
  if [ "$gwtype" != "-" ]; then
    sed -i "/^data:/a\\  gatewayType: $gwtype" "$proj/api.yaml"
    sed -i "/^data:/a\\  gatewayVendor: external" "$proj/api.yaml"
  fi
  # We control deployment via REST, so don't let import auto-deploy to Default.
  printf "type: deployment_environments\nversion: v4.7.0\ndata: []\n" > "$proj/deployment_environments.yaml"
  apictl import api -f "$proj" -e demo -k --update --preserve-provider=false >/dev/null 2>&1

  # Resolve id (Publisher search index lags on a fresh server).
  local id="" i=0
  while :; do
    id=$(curl -sk -H "$AUTH" "$PUB/apis?query=name:$name" | jq -r '.list[0].id // empty')
    [ -n "$id" ] && break
    i=$((i+1)); [ $i -gt 20 ] && { log "WARN: could not resolve id for $name"; return; }
    sleep 3
  done

  # Open resource security + mark default version.
  # securityScheme=["oauth2"] drops the mandatory API-key scheme so the native WSO2
  # gateway serves the API without a token (federated runtimes serve directly anyway).
  local api patched
  api=$(curl -sk -H "$AUTH" "$PUB/apis/$id")
  patched=$(echo "$api" | jq '.isDefaultVersion=true | .securityScheme=["oauth2"] | .operations=[.operations[]|.authType="None"]')
  curl -sk -o /dev/null -X PUT -H "$AUTH" -H "Content-Type: application/json" -d "$patched" "$PUB/apis/$id"

  # Deploy a revision to the API's target gateway environment. For a federated
  # environment (KongLocal / HomeGrown) this invokes the connector's GatewayDeployer,
  # which pushes the API to the real runtime (Kong Admin API / mock gateway).
  if [ "$do_deploy" = yes ]; then
    local rev
    rev=$(curl -sk -X POST -H "$AUTH" -H "Content-Type: application/json" -d '{"description":"init"}' "$PUB/apis/$id/revisions" | jq -r '.id // empty')
    if [ -n "$rev" ]; then
      curl -sk -o /dev/null -X POST -H "$AUTH" -H "Content-Type: application/json" \
        -d '[{"name":"'"$env"'","vhost":"'"$(vhost_of "$env")"'","displayOnDevportal":true}]' \
        "$PUB/apis/$id/deploy-revision?revisionId=$rev"
      log "deployed revision to '$env' -> connector federated $name to its runtime"
    fi
  else
    log "created (UNDEPLOYED) -> deploy later from the dashboard to '$env'"
  fi

  # Publish to the Dev Portal marketplace (tolerate already-published).
  curl -sk -o /dev/null -X POST -H "$AUTH" "$PUB/apis/change-lifecycle?apiId=$id&action=Publish"
}

deploy_api EmployeeAPI /provisioning/openapi/employee.yaml /employee http://backend:8080/employee Default    -
deploy_api LeaveAPI    /provisioning/openapi/leave.yaml    /leave    http://backend:8080/leave    Default    -
deploy_api VehicleAPI  /provisioning/openapi/vehicle.yaml  /vehicle  http://backend:8080          Kong       KongLocal
deploy_api ParkingAPI  /provisioning/openapi/parking.yaml  /parking  http://backend:8080          Kong       KongLocal
deploy_api CitizenAPI  /provisioning/openapi/citizen.yaml  /citizen  http://backend:8080          ThirdParty HomeGrown
deploy_api PaymentAPI  /provisioning/openapi/payment.yaml  /payment  http://backend:8080          ThirdParty HomeGrown

# NOTE: 6 further APIs (Department, Payroll, Fuel, Toll, Tax, License) are NOT
# created here. They are offered by the dashboard as "templates" that the operator
# instantiates onto ANY chosen gateway at demo time (create-with-type + deploy),
# and can Remove to redeploy the same API to a different gateway.

log "----------------------------------------------------------"
log "All APIs in the control plane (marketplace):"
apictl get apis -e demo -k 2>/dev/null | sed 's/^/        /' || true
