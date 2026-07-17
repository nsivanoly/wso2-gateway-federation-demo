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

## Manual deploy into `dropins/`

Normally `control-plane/Dockerfile` copies this JAR into the image's
`repository/components/dropins/` at build time. To iterate on the connector
without rebuilding the image, drop the freshly built JAR into a **running**
control-plane container and restart it (OSGi bundles in `dropins/` are resolved
at server startup, so a restart is required — it is not hot-swapped):

```bash
# from the repo root, with the stack running
JAR=$(ls gateway-connectors/homegrown/components/homegrown.gw.manager/target/homegrown.gw.manager-*.jar)
DROPINS=/home/wso2carbon/wso2am-4.7.0/repository/components/dropins

# copy the bundle in (overwrites the baked-in one)
docker compose cp "$JAR" "control-plane:$DROPINS/"

# restart so OSGi picks up the new bundle
docker compose restart control-plane
```

For the `HomeGrown` type to appear in the Admin Portal it must also be in the
`[apim] gateway_type` allowlist in
`repository/conf/deployment.toml` (the image injects it via `sed` — see
`control-plane/Dockerfile`). If you edit that file inside a running container,
restart the control plane afterward.

> Tip: after a source change, delete the existing `target/*.jar` so `start.sh`
> rebuilds it, or run the manual build above.

## Registration

The gateway **type** must also appear in the `[apim] gateway_type` allowlist —
`control-plane/Dockerfile` injects `HomeGrown` into the stock `deployment.toml`.
Federated environments are registered with `provider: external` (in
`provisioning/deploy-apis.sh`) so revision deployments dispatch to this connector.

See [`../../docs/architecture.md`](../../docs/architecture.md) and
[`../../docs/federation-semantics.md`](../../docs/federation-semantics.md).
