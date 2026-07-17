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
log() { echo "    $*"; }

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

# ---- create + deploy + publish one API (pure REST; no external CLI) ----
# deploy_api <Name> <oas> <context> <backendUrl> <env> <gwType|-> [deploy=yes|no]
# gwType: the external gateway type (KongLocal / HomeGrown) so the API is created as
# an external-gateway API whose revision deployment invokes that connector's
# GatewayDeployer. Use "-" for the native WSO2 Default gateway.
# deploy=no creates + publishes the API but leaves it UNDEPLOYED (its gatewayType
# still records the intended runtime) so the dashboard can deploy it on demand.
deploy_api() {
  local name=$1 oas=$2 ctx=$3 backend=$4 env=$5 gwtype=$6 do_deploy=${7:-yes}
  log "----------------------------------------------------------"
  log "$name  ->  env=$env  (context=$ctx${gwtype:+, type=$gwtype})"

  # gatewayVendor/gatewayType are immutable after creation, so they must be set
  # at import time. Native -> wso2/synapse; federated -> external + connector type.
  local gwvendor="wso2" gwtypeval="wso2/synapse"
  if [ "$gwtype" != "-" ]; then gwvendor="external"; gwtypeval="$gwtype"; fi

  # Is the API already present? (idempotent re-runs)
  local id
  id=$(curl -sk -H "$AUTH" "$PUB/apis?query=name:$name" | jq -r '.list[0].id // empty')

  if [ -z "$id" ]; then
    # Create the API from its OpenAPI definition via the Publisher REST API.
    local props
    props=$(jq -nc --arg n "$name" --arg c "$ctx" --arg u "$backend" --arg gv "$gwvendor" --arg gt "$gwtypeval" \
      '{name:$n,version:"v1",context:$c,gatewayVendor:$gv,gatewayType:$gt,policies:["Unlimited"],
        endpointConfig:{endpoint_type:"http",production_endpoints:{url:$u}}}')
    id=$(curl -sk -H "$AUTH" \
      -F "file=@$oas;type=application/x-yaml" \
      -F "additionalProperties=$props;type=application/json" \
      "$PUB/apis/import-openapi" | jq -r '.id // empty')
    # Publisher search index lags on a fresh server; retry the id resolve.
    local i=0
    while [ -z "$id" ]; do
      sleep 3; i=$((i+1)); [ $i -gt 20 ] && { log "WARN: could not create/resolve $name"; return; }
      id=$(curl -sk -H "$AUTH" "$PUB/apis?query=name:$name" | jq -r '.list[0].id // empty')
    done
  else
    log "already exists (id=$id) — reconciling"
  fi

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
  # Skip if already deployed (avoids piling up revisions on re-runs).
  local deployed
  deployed=$(curl -sk -H "$AUTH" "$PUB/apis/$id/deployments" | jq -r 'if type=="array" then length else (.list|length) end // 0')
  if [ "$do_deploy" = yes ] && [ "${deployed:-0}" = 0 ]; then
    local rev
    rev=$(curl -sk -X POST -H "$AUTH" -H "Content-Type: application/json" -d '{"description":"init"}' "$PUB/apis/$id/revisions" | jq -r '.id // empty')
    if [ -n "$rev" ]; then
      curl -sk -o /dev/null -X POST -H "$AUTH" -H "Content-Type: application/json" \
        -d '[{"name":"'"$env"'","vhost":"'"$(vhost_of "$env")"'","displayOnDevportal":true}]' \
        "$PUB/apis/$id/deploy-revision?revisionId=$rev"
      log "deployed revision to '$env' -> connector federated $name to its runtime"
    fi
  elif [ "$do_deploy" = yes ]; then
    log "already deployed to '$env' — skipping"
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
printf "        %-14s %-8s %-12s %s\n" NAME VERSION CONTEXT STATUS
curl -sk -H "$AUTH" "$PUB/apis?limit=50" \
  | jq -r '.list[] | "        \(.name|.[0:13]|. + (" "*(13-length)))  \(.version)      \(.context|.[0:11]|. + (" "*(11-length)))  \(.lifeCycleStatus)"' 2>/dev/null \
  || true
