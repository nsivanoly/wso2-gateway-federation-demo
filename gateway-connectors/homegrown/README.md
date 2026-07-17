# HomeGrown Gateway Connector

A WSO2 API Platform 4.7.0 **custom gateway connector** that federates APIs to the
demo's mock third-party ("home-grown") gateway. It represents the pattern for
integrating *any* gateway WSO2 has no native connector for (NGINX, APISIX,
Apigee, …).

- **Gateway type:** `HomeGrown`
- **Target runtime:** the mock gateway (`services/mock-gateway`), default
  `http://mock-gateway:8090` (admin + proxy)
- **Java package:** `org.wso2.homegrown.client`

## What it does

The connector is an OSGi bundle that registers three WSO2 SPI services:

| Class                             | SPI                        | Role                                          |
|-----------------------------------|----------------------------|-----------------------------------------------|
| `HomeGrownGatewayConfiguration`   | `GatewayAgentConfiguration`| `@Component` entry point; declares type `HomeGrown` |
| `HomeGrownGatewayDeployer`        | `GatewayDeployer`          | **Push:** `deploy()` → `POST /mock/deploy`; `undeploy()` → `POST /mock/undeploy` |
| `HomeGrownFederatedAPIDiscovery`  | `FederatedAPIDiscovery`    | **Pull:** reads the gateway's route registry and imports external routes into WSO2 |

Self-managed routes (those the connector itself pushed) are tagged with
`managedBy = wso2-apim-homegrown-connector` and skipped during discovery to avoid
a sync loop. Routes created directly on the gateway lack this marker and are
discovered into WSO2.

## Build

Built automatically by `../../start.sh` (in a Maven container) if the JAR is
missing. To build manually, use either option below (run from this connector
directory, `gateway-connectors/homegrown`).

**Option A — local Maven** (if Maven + JDK 11 are installed; no Docker needed):

```bash
mvn -q -B -pl components/homegrown.gw.manager -am package -DskipTests
```

**Option B — Maven in Docker** (no local toolchain required):

```bash
docker run --rm -v "$PWD":/build -v gateway-federation-m2:/root/.m2 -w /build \
  maven:3.9-eclipse-temurin-11 \
  mvn -q -B -pl components/homegrown.gw.manager -am package -DskipTests
```

Output: `components/homegrown.gw.manager/target/homegrown.gw.manager-*.jar`.

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
cp homegrown.gw.manager-*.jar  <APIM_HOME>/repository/components/dropins/

# (b) Running Docker container
docker cp homegrown.gw.manager-*.jar  <container>:<APIM_HOME>/repository/components/dropins/

# (c) Kubernetes pod
kubectl cp homegrown.gw.manager-*.jar  <namespace>/<pod>:<APIM_HOME>/repository/components/dropins/
```

For an **image build** (bake it in), add to your Dockerfile:

```dockerfile
COPY --chown=wso2carbon:wso2 \
     homegrown.gw.manager-*.jar \
     ${APIM_HOME}/repository/components/dropins/
```

### 2. Allowlist the gateway type in `deployment.toml`

APIM only shows gateway types listed in `[apim] gateway_type`. Add `HomeGrown`
to `<APIM_HOME>/repository/conf/deployment.toml` — edit in place (recommended,
upgrade-safe) rather than shipping a whole file:

```bash
# append HomeGrown to the existing gateway_type line (idempotent)
sed -i '/^gateway_type = ".*APIPlatform/ { /HomeGrown/! s/"\s*$/,HomeGrown"/ }' \
  <APIM_HOME>/repository/conf/deployment.toml
```

or edit it by hand:

```toml
[apim]
gateway_type = "Regular,APK,AWS,Azure,Kong,Envoy,APIPlatform,HomeGrown"
```

> `deployment.toml` must stay readable/writable by the WSO2 runtime user
> (usually `wso2carbon`); a root-owned file causes an `AccessDeniedException` at
> boot. `chown wso2carbon:wso2 deployment.toml` if needed.

### 3. Restart WSO2

OSGi resolves `dropins/` bundles at startup, so a restart is required (not
hot-swapped): `sh <APIM_HOME>/bin/api-manager.sh` (or restart the
container/pod). After boot, `HomeGrown` appears in the Admin Portal's gateway
type dropdown and under `GET /api/am/admin/v4/settings` → `gatewayTypes`.

### 4. Register a gateway environment (Admin REST API)

Create an environment of type `HomeGrown` with **`provider: external`** — that is
what makes WSO2 dispatch revision deployments to this connector's
`GatewayDeployer` instead of the internal synapse gateway. Put your gateway's
reachable URLs in `additionalProperties`:

```bash
# $TOK = an Admin REST token (scope: apim:admin). $CP = https://<apim-host>:9443
curl -sk -X POST "$CP/api/am/admin/v4/environments" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{
    "name": "HomeGrownProd",
    "displayName": "Home-grown Gateway",
    "provider": "external",
    "type": "hybrid",
    "gatewayType": "HomeGrown",
    "mode": "READ_WRITE",
    "apiDiscoveryScheduledWindow": 5,
    "additionalProperties": [
      { "key": "admin_url",    "value": "http://<your-gateway-host>:8090" },
      { "key": "proxy_url",    "value": "http://<your-gateway-host>:8090" },
      { "key": "stage",        "value": "default" },
      { "key": "auto_publish", "value": "false" }
    ],
    "vhosts": [ { "host": "<your-gateway-host>", "httpPort": 8090, "httpsPort": 8091 } ]
  }'
```

Config keys this connector understands (declared by
`HomeGrownGatewayConfiguration`, shown in the Admin UI):

| Key | Type | Meaning |
|-----|------|---------|
| `admin_url` | input | Gateway admin API URL the connector calls to deploy/undeploy |
| `proxy_url` | input | Gateway proxy URL used to build API execution URLs |
| `stage` | input | Optional free-text label (default `default`) |
| `auto_publish` | checkbox | **`true`** → discovered APIs are published to the Dev Portal automatically; **`false`** (default) → left in `CREATED` for manual review |

`apiDiscoveryScheduledWindow` is the reverse-discovery interval in **minutes**
(omit or set `0` to disable discovery).

### 5. Deploy an API to it

Create the API as an **external-gateway** API of this type (these fields are
immutable after creation), then deploy a revision to the environment — that call
invokes the connector and pushes the API to your gateway:

```bash
# at creation: "gatewayVendor": "external", "gatewayType": "HomeGrown"
# then: POST /api/am/publisher/v4/apis/{id}/deploy-revision
#       body: [{ "name": "HomeGrownProd", "vhost": "<your-gateway-host>" }]
```

See [`provisioning/deploy-apis.sh`](../../provisioning/deploy-apis.sh) for a
complete, working REST example (create-from-OpenAPI → deploy → publish).

---

## Adapting it to a real gateway

This connector talks to the demo's mock gateway (`/mock/deploy`,
`/mock/undeploy`, `/mock/registry`). To target a real gateway, reimplement the
HTTP calls in `HomeGrownGatewayDeployer` (deploy/undeploy) and
`HomeGrownFederatedAPIDiscovery` (list routes) against that gateway's admin API,
keeping the same SPI methods. Preserve a **managed marker** on pushed routes
(here `managedBy = wso2-apim-homegrown-connector`) so discovery can skip
connector-owned routes and avoid a sync loop.

See [`../../docs/architecture.md`](../../docs/architecture.md) and
[`../../docs/federation-semantics.md`](../../docs/federation-semantics.md).
