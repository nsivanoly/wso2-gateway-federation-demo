# Architecture

## Overview

A single **WSO2 API Platform 4.7.0** control plane governs **six APIs** — all
created and published in the Publisher/Dev Portal — and federates each one to a
*different kind* of runtime gateway. The demo proves that heterogeneous gateways
can coexist under one control plane while each hosts only its own APIs.

```
                WSO2 API Platform 4.7.0  (Control Plane)
                Publisher · Dev Portal · Admin · Key Manager
                          https://localhost:9443
                                   │
        ┌──────────────────────────┼───────────────────────────┐
        ▼                          ▼                            ▼
  WSO2 Gateway              Kong Gateway                 Mock Third-Party GW
  (native, built-in)        (KongLocal connector →       (HomeGrown connector →
   :8243                     Kong Admin API) :8000         mock gateway) :8090
        │                          │                            │
   Employee, Leave           Vehicle, Parking             Citizen, Payment
```

| Gateway environment | Federation mechanism                              | APIs             | Serves at                          |
|---------------------|---------------------------------------------------|------------------|------------------------------------|
| `Default`           | Native WSO2 synapse gateway (built into the CP)   | Employee, Leave  | `/<context>` (e.g. `/employee/employees`) |
| `Kong`              | `KongLocal` connector → Kong Admin API            | Vehicle, Parking | `/<context>/v1/<resource>`         |
| `ThirdParty`        | `HomeGrown` connector → mock gateway `/mock/deploy` | Citizen, Payment | `/<context>/v1/<resource>`         |

## Custom gateway connectors

Each federated gateway type is provided by an **OSGi bundle** baked into the
control-plane image (`repository/components/dropins/`). Two things make a custom
type usable:

1. The bundle registers a `GatewayAgentConfiguration` DS service (`@Component`).
2. The type name is present in the `[apim] gateway_type` allowlist (APIM filters
   the Admin Portal dropdown by it). `control-plane/Dockerfile` injects
   `HomeGrown,KongLocal` into the stock `deployment.toml` line in place (via
   `sed`), rather than shipping a full replacement file.

A federated gateway **environment** must be registered with `provider: external`
so WSO2 dispatches revision deployments to the connector's `GatewayDeployer`
instead of the internal synapse manager. Each connector implements:

- `GatewayDeployer.deploy() / undeploy()` — **push** WSO2 → gateway runtime.
- `FederatedAPIDiscovery.discoverAPI()` — **pull** gateway → WSO2 (reverse discovery).

The connector sources live in [`gateway-connectors/`](../gateway-connectors);
`start.sh` builds them with Maven (in a container) if the JARs are missing.

## Components

| Path                        | Role                                                             |
|-----------------------------|------------------------------------------------------------------|
| `control-plane/`            | WSO2 image build (bakes in connectors; injects the gateway types) |
| `gateway-connectors/`       | HomeGrown + KongLocal connector Maven projects (OSGi bundles)    |
| `services/backend/`         | Unified mock backend for every "original" upstream               |
| `services/mock-gateway/`    | Mock third-party ("home-grown") gateway + admin console          |
| `services/dashboard/`       | Live federation console over the WSO2 REST APIs                  |
| `provisioning/`             | One-shot federation wiring (`provision.sh` → `deploy-apis.sh`) + OpenAPI specs |
| `bin/apictl`                | WSO2 CLI used by provisioning (downloaded by `start.sh` if absent) |

## Request flow (example: Vehicle API on Kong)

1. `deploy-apis.sh` creates VehicleAPI in the Publisher (`gatewayVendor: external`,
   `gatewayType: KongLocal`) and deploys a revision to the `Kong` environment.
2. WSO2 dispatches to the KongLocal connector, which calls the Kong Admin API to
   create a service (→ `http://backend:8080`) and a route (`/vehicle/v1`).
3. A client calls `http://localhost:8000/vehicle/v1/vehicles`; Kong proxies to the
   backend, which infers the `vehicles` collection from the path and responds.
