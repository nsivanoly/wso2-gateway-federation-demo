# KongLocal Gateway Connector

A WSO2 API Platform 4.7.0 **custom gateway connector** that federates APIs to a
**Kong** gateway via its Admin API. It demonstrates native-style federation to a
popular third-party gateway.

- **Gateway type:** `KongLocal`
- **Target runtime:** Kong (`kong:3.7`), default Admin `http://localhost:8001`,
  proxy `http://kong:8000`
- **Java package:** `org.wso2.konglocal.client`

## What it does

The connector is an OSGi bundle that registers three WSO2 SPI services:

| Class                              | SPI                         | Role                                                     |
|------------------------------------|-----------------------------|----------------------------------------------------------|
| `KongLocalGatewayConfiguration`    | `GatewayAgentConfiguration` | `@Component` entry point; declares type `KongLocal`      |
| `KongLocalGatewayDeployer`         | `GatewayDeployer`           | **Push:** creates/updates Kong `/services` + `/routes`   |
| `KongLocalFederatedAPIDiscovery`   | `FederatedAPIDiscovery`     | **Pull:** reads Kong services/routes and imports them into WSO2 |

The connector supports both Kong modes:

- **DB-backed mode** (used in this demo — Postgres): deploys and updates APIs
  through Kong Admin API entity CRUD (`/services`, `/routes`) and discovers by
  reading them. Lets multiple APIs coexist on one Kong.
- **DB-less mode:** falls back to declarative config sync via `/config` and
  discovers from the declarative services/routes.

Connector-managed services carry a Kong `tags` entry `wso2-apim-managed` (and a
route named `<service>-route`) so discovery skips them and avoids a sync loop.
Routes created directly on Kong lack this marker and are discovered into WSO2.

> **Note:** this demo runs Kong in **DB-backed** mode (see `docker-compose.yml`).
> DB-less `/config` replaces the whole declarative config on each deploy, so only
> one API could survive on a DB-less Kong.

## Build

Built automatically by `../../start.sh` (in a Maven container) if the JAR is
missing. To build manually, use either option below (run from this connector
directory, `gateway-connectors/konglocal`).

**Option A — local Maven** (if Maven + JDK 11 are installed; no Docker needed):

```bash
mvn -q -B -pl components/konglocal.gw.manager -am package -DskipTests
```

**Option B — Maven in Docker** (no local toolchain required):

```bash
docker run --rm -v "$PWD":/build -v gateway-federation-m2:/root/.m2 -w /build \
  maven:3.9-eclipse-temurin-11 \
  mvn -q -B -pl components/konglocal.gw.manager -am package -DskipTests
```

Output: `components/konglocal.gw.manager/target/konglocal.gw.manager-*.jar`.

---

## Using this connector in your own WSO2 API Manager

This connector is a self-contained OSGi bundle — you can drop it into **any**
WSO2 API Manager 4.7.x install, wherever it runs (container, VM, bare metal,
Kubernetes). Four steps: install the bundle → allowlist the type → restart →
register the gateway. Below, `<APIM_HOME>` is your product root (e.g.
`/opt/wso2am-4.7.0` or `/home/wso2carbon/wso2am-4.7.0`).

> Version note: build with **JDK 11**; the connector targets APIM `9.32.74`
> (API Manager 4.7.x). Match your APIM version if you upgrade.

### 1. Install the bundle into `dropins/`

Copy the built JAR to `<APIM_HOME>/repository/components/dropins/`. Pick the
method for your environment:

```bash
# (a) Bare metal / VM — WSO2 stopped or running (restart applied in step 3)
cp konglocal.gw.manager-*.jar  <APIM_HOME>/repository/components/dropins/

# (b) Running Docker container
docker cp konglocal.gw.manager-*.jar  <container>:<APIM_HOME>/repository/components/dropins/

# (c) Kubernetes pod
kubectl cp konglocal.gw.manager-*.jar  <namespace>/<pod>:<APIM_HOME>/repository/components/dropins/
```

For an **image build** (bake it in), add to your Dockerfile:

```dockerfile
COPY --chown=wso2carbon:wso2 \
     konglocal.gw.manager-*.jar \
     ${APIM_HOME}/repository/components/dropins/
```

### 2. Allowlist the gateway type in `deployment.toml`

APIM only shows gateway types listed in `[apim] gateway_type`. Add `KongLocal`
to `<APIM_HOME>/repository/conf/deployment.toml` — edit in place (recommended,
upgrade-safe) rather than shipping a whole file:

```bash
# append KongLocal to the existing gateway_type line (idempotent)
sed -i '/^gateway_type = ".*APIPlatform/ { /KongLocal/! s/"\s*$/,KongLocal"/ }' \
  <APIM_HOME>/repository/conf/deployment.toml
```

or edit it by hand:

```toml
[apim]
gateway_type = "Regular,APK,AWS,Azure,Kong,Envoy,APIPlatform,KongLocal"
```

> `deployment.toml` must stay readable/writable by the WSO2 runtime user
> (usually `wso2carbon`); a root-owned file causes an `AccessDeniedException` at
> boot. `chown wso2carbon:wso2 deployment.toml` if needed.

### 3. Restart WSO2

OSGi resolves `dropins/` bundles at startup, so a restart is required (not
hot-swapped): `sh <APIM_HOME>/bin/api-manager.sh` (or restart the
container/pod). After boot, `KongLocal` appears in the Admin Portal's gateway
type dropdown and under `GET /api/am/admin/v4/settings` → `gatewayTypes`.

### 4. Register a gateway environment (Admin REST API)

Create an environment of type `KongLocal` with **`provider: external`** — that is
what makes WSO2 dispatch revision deployments to this connector's
`GatewayDeployer` instead of the internal synapse gateway. Point `admin_url` at
your Kong **Admin API** and `proxy_url` at Kong's **proxy** listener:

```bash
# $TOK = an Admin REST token (scope: apim:admin). $CP = https://<apim-host>:9443
curl -sk -X POST "$CP/api/am/admin/v4/environments" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{
    "name": "KongProd",
    "displayName": "Kong Gateway",
    "provider": "external",
    "type": "hybrid",
    "gatewayType": "KongLocal",
    "mode": "READ_WRITE",
    "apiDiscoveryScheduledWindow": 5,
    "additionalProperties": [
      { "key": "admin_url",    "value": "http://<kong-host>:8001" },
      { "key": "proxy_url",    "value": "http://<kong-host>:8000" },
      { "key": "stage",        "value": "default" },
      { "key": "auto_publish", "value": "false" }
    ],
    "vhosts": [ { "host": "<kong-host>", "httpPort": 8000, "httpsPort": 8443 } ]
  }'
```

Config keys this connector understands (declared by
`KongLocalGatewayConfiguration`, shown in the Admin UI):

| Key | Type | Meaning |
|-----|------|---------|
| `admin_url` | input | Kong Admin API URL the connector calls to create services/routes |
| `proxy_url` | input | Kong proxy URL used to build API execution URLs |
| `stage` | input | Optional free-text label (default `default`) |
| `auto_publish` | select (`true`/`false`) | **`true`** → discovered APIs are published to the Dev Portal automatically; **`false`** (default) → left in `CREATED` for manual review |

`apiDiscoveryScheduledWindow` is the reverse-discovery interval in **minutes**
(omit or set `0` to disable discovery).

> **Kong mode:** use **DB-backed** Kong. The connector deploys via the Admin API
> `/services` + `/routes` CRUD endpoints so multiple APIs coexist. In DB-less
> mode Kong's `/config` replaces the whole declarative config on each deploy
> (only one API survives) and the CRUD endpoints return `405`.

### 5. Deploy an API to it

Create the API as an **external-gateway** API of this type (these fields are
immutable after creation), then deploy a revision to the environment — that call
invokes the connector and creates the Kong service + route:

```bash
# at creation: "gatewayVendor": "external", "gatewayType": "KongLocal"
# then: POST /api/am/publisher/v4/apis/{id}/deploy-revision
#       body: [{ "name": "KongProd", "vhost": "<kong-host>" }]
```

See [`provisioning/deploy-apis.sh`](../../provisioning/deploy-apis.sh) for a
complete, working REST example (create-from-OpenAPI → deploy → publish).

---

## Adapting it

This connector maps WSO2 APIs onto Kong `/services` + `/routes` via the Admin
API. Connector-pushed services are tagged `wso2-apim-managed` so reverse
discovery skips them and only imports routes created directly on Kong (avoiding a
sync loop). If you fork it for a different Kong setup, keep that managed tag on
everything the connector creates.

See [`../../docs/architecture.md`](../../docs/architecture.md) and
[`../../docs/federation-semantics.md`](../../docs/federation-semantics.md).
