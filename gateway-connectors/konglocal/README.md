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

## Manual deploy into `dropins/`

Normally `control-plane/Dockerfile` copies this JAR into the image's
`repository/components/dropins/` at build time. To iterate on the connector
without rebuilding the image, drop the freshly built JAR into a **running**
control-plane container and restart it (OSGi bundles in `dropins/` are resolved
at server startup, so a restart is required — it is not hot-swapped):

```bash
# from the repo root, with the stack running
JAR=$(ls gateway-connectors/konglocal/components/konglocal.gw.manager/target/konglocal.gw.manager-*.jar)
DROPINS=/home/wso2carbon/wso2am-4.7.0/repository/components/dropins

# copy the bundle in (overwrites the baked-in one)
docker compose cp "$JAR" "control-plane:$DROPINS/"

# restart so OSGi picks up the new bundle
docker compose restart control-plane
```

For the `KongLocal` type to appear in the Admin Portal it must also be in the
`[apim] gateway_type` allowlist in
`repository/conf/deployment.toml` (the image injects it via `sed` — see
`control-plane/Dockerfile`). If you edit that file inside a running container,
restart the control plane afterward.

> Tip: after a source change, delete the existing `target/*.jar` so `start.sh`
> rebuilds it, or run the manual build above.

## Registration

The gateway **type** must also appear in the `[apim] gateway_type` allowlist —
`control-plane/Dockerfile` injects `KongLocal` into the stock `deployment.toml`.
Federated environments are registered with `provider: external` (in
`provisioning/deploy-apis.sh`) so revision deployments dispatch to this connector.

See [`../../docs/architecture.md`](../../docs/architecture.md) and
[`../../docs/federation-semantics.md`](../../docs/federation-semantics.md).
