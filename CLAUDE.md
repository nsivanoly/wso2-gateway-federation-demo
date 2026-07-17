# CLAUDE.md

Project guidance for Claude Code (and any engineer) working in this repo. This is
the authoritative current-state summary; deeper detail lives in
[README.md](README.md) and [docs/](docs/).

## What this is

A self-contained, one-command Docker Compose demo of **WSO2 API Platform 4.7.0
Gateway Federation**. A single WSO2 control plane governs **six APIs** and
federates each to a *different kind* of runtime gateway, proving heterogeneous
gateways can coexist under one control plane while each hosts only its own APIs.

It is a **demo/PoC**, not production software. Optimise for clarity of the
federation story and a clean one-command run. Do **not** add business features.

## Architecture

```
        WSO2 API Platform 4.7.0 control plane (Publisher/DevPortal/Admin/KM)
                          https://localhost:9443
                                   │
        ┌──────────────────────────┼───────────────────────────┐
        ▼                          ▼                            ▼
  WSO2 Gateway (native)     Kong Gateway                 Mock Third-Party GW
  built into the CP :8243   (KongLocal connector)        (HomeGrown connector)
   Employee, Leave          Vehicle, Parking :8000       Citizen, Payment :8090
```

| Gateway env | Federation mechanism | APIs | Serves at |
|-------------|----------------------|------|-----------|
| `Default` | native WSO2 synapse gateway (in the CP image) | Employee, Leave | `/<context>` |
| `Kong` | `KongLocal` connector → Kong Admin API | Vehicle, Parking | `/<context>/v1/<resource>` |
| `ThirdParty` | `HomeGrown` connector → mock gateway `/mock/deploy` | Citizen, Payment | `/<context>/v1/<resource>` |

## Repository layout

```
control-plane/          WSO2 image build (bakes in connectors; injects gateway types)
gateway-connectors/     custom connectors (OSGi bundle Maven projects)
  homegrown/            -> mock third-party gateway (type HomeGrown)
  konglocal/            -> Kong Admin API           (type KongLocal)
services/
  backend/              unified mock backend for every "original" upstream (backend:8080)
  mock-gateway/         mock third-party gateway + admin console (:8090)
  dashboard/            live federation console (:3000)
provisioning/           provision.sh -> deploy-apis.sh (pure REST) + openapi/
docs/                   architecture.md, federation-semantics.md, index
docker-compose.yml · start.sh · stop.sh · .env.sample · .gitignore
back/                   parked reference files (gitignored; safe to delete)
```

Every top-level component has its own README. `back/`, `.env`, `bin/`, build
artifacts (`**/target/`, `*.jar`) are gitignored.

## Running

```bash
./start.sh     # first run: prereqs -> .env from .env.sample -> build JARs+images -> up -> REST provisioning
./stop.sh      # graceful stop (data preserved) by default; options to wipe volumes/images
```

`start.sh` is idempotent and self-healing: it re-runs federation wiring only if a
gateway isn't already serving. Consoles: dashboard `:3000`, Publisher/DevPortal
`:9443`, mock-gateway `:8090`, Kong Manager `:8002`. Creds `admin/admin`.

## Key concepts & how it actually works

- **Custom gateway connectors** are OSGi bundles baked into the CP image's
  `repository/components/dropins/`. Each registers three SPI services:
  `GatewayAgentConfiguration` (declares the type + env config fields),
  `GatewayDeployer` (push: deploy/undeploy), `FederatedAPIDiscovery` (pull:
  reverse discovery). Packages: `org.wso2.homegrown.client`,
  `org.wso2.konglocal.client`. Built against APIM `9.32.74`, JDK 11 source level.
- **Two things make a custom type usable:** (1) the bundle in `dropins/`, and
  (2) the type in the `[apim] gateway_type` allowlist in `deployment.toml`.
  `control-plane/Dockerfile` **injects** `HomeGrown,KongLocal` into the stock
  toml in place via `sed` (no full-file replacement, no committed encryption key).
- **Federated env must be `provider: external`** — that's what makes revision
  deployments dispatch to the connector instead of the internal synapse gateway.
  APIs must be created with `gatewayVendor: external` + `gatewayType: <type>`
  (both immutable after creation).
- **Provisioning is pure REST** (`provisioning/deploy-apis.sh`, curl + jq, no
  external CLI): create via `POST /apis/import-openapi`, deploy a revision,
  publish. Idempotent (upsert env, detect existing APIs, skip already-deployed).
- **Unified backend:** one `services/backend` image serves every collection by
  inferring it from the path (drops `/v1`), reached in-cluster as `backend:8080`.
- **Managed markers** let discovery skip connector-pushed routes (avoid loops):
  Kong service tag `wso2-apim-managed`; mock gateway `managedBy` field.
- **auto_publish** env option (both connectors, `type: options` dropdown, default
  `false`): controls the lifecycle discovered APIs land in — `false` → `CREATED`
  (review then publish), `true` → `PUBLISHED` (straight to Dev Portal). Applied in
  `discoverAPI()` via `api.setStatus(...)`, which the framework honors.
- **Dashboard** reads WSO2 **and** the live gateway runtimes, labelling each
  external API **⇈ pushed / ⇩ discovered / ⧗ pending discovery**. Its template
  deploys go **directly to the gateway** (no marker) so discovery imports them.

See [docs/federation-semantics.md](docs/federation-semantics.md) for the full
push/pull, lifecycle, and discovery behaviour.

## Working in this repo

- **Change a Node service** (`services/*`): `docker compose up -d --build <svc>`
  (`backend`, `mock-gateway`, `dashboard`).
- **Change a connector** (`gateway-connectors/*`): rebuild the JAR, then rebuild
  the control-plane image (`docker compose build control-plane`) and recreate it.
  Build options:
  - local Maven (if installed):
    `cd gateway-connectors/<c> && mvn -q -B -pl components/<c>.gw.manager -am package -DskipTests`
  - or Maven-in-Docker (see connector READMEs). A stale `target/*.jar` is reused —
    delete it (or rebuild) to pick up source changes.
- **Config** is in `.env` (from `.env.sample`); never commit `.env`. Compose uses
  `${VAR:-default}` throughout.
- **Verify** after changes by driving the real flow: `./start.sh` (or targeted
  `docker compose ...`), then curl each gateway and check the dashboard catalog.
  Clean up any test APIs/routes afterwards to keep the demo pristine.

## Known gotchas

- **No persistent volumes** for WSO2 (H2 in-container) or Kong (Postgres, no named
  volume); the mock-gateway registry is **in-memory**. Recreating those containers
  (or a Docker VM restart) loses state → re-run provisioning.
- **Discovery lock:** editing a gateway env via the Admin API mid-discovery can
  orphan the lock in `AM_TASK_LOCK`; discovery silently stalls. Fix: restart the
  control plane (`docker compose restart control-plane` — preserves H2).
- **First cold connector build** downloads WSO2 deps (slow); the Maven cache
  volume `gateway-federation-m2` (Docker) or the local Maven cache makes reruns fast.
- **Admin UI field types:** gateway-env config fields render a dropdown only with
  `type: "options"` (values as option objects). The options widget doesn't
  pre-select from `defaultValue`, so the connector default applies when unset.

## Conventions

- Keep provisioning REST-based, the backend unified, and the layout as-is
  (`control-plane/`, `gateway-connectors/`, `services/`, `provisioning/`); the CP
  image injects the gateway types into `deployment.toml` rather than replacing it.
- Keep the six-API / three-gateway story intact; each API lives on exactly one
  gateway. Don't deploy the same API to multiple gateways.
- Update the relevant README/docs when behaviour changes; keep `.env.sample`
  complete.

## Environment notes

- Repo: `git@github.com:nsivanoly/wso2-gateway-federation-demo.git` (branch `main`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- The 4.7.0-alpine base bundles Java 25; connectors build with JDK 11 source level.
