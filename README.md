# WSO2 API Platform 4.7.0 — Gateway Federation Demo

> One control plane, three heterogeneous gateways (WSO2, Kong, custom), one command.

A **one-command**, self-contained Docker Compose demo of WSO2 API Platform 4.7.0
**Gateway Federation**. A single WSO2 control plane governs six APIs — all visible
in the Publisher and Dev Portal — and deploys each to a *different kind* of runtime
gateway, proving that heterogeneous gateways coexist under one control plane while
each hosts only its own APIs.

```
                WSO2 API Platform 4.7.0  (Control Plane)
                Publisher · Dev Portal · Admin · Key Manager
                          https://localhost:9443
                                   │
        ┌──────────────────────────┼───────────────────────────┐
        ▼                          ▼                            ▼
  WSO2 Gateway              Kong Gateway                 Mock Third-Party GW
  (native, built-in)        (KongLocal connector)        (HomeGrown connector)
   :8243                     :8000                         :8090
   Employee, Leave           Vehicle, Parking             Citizen, Payment
```

| Gateway environment | Federation mechanism                          | APIs             | Serves at                        |
|---------------------|-----------------------------------------------|------------------|----------------------------------|
| `Default` (WSO2)    | Native synapse gateway built into the CP      | Employee, Leave  | `/<context>` (`/employee/employees`, `/leave`) |
| `Kong`              | `KongLocal` connector → Kong Admin API        | Vehicle, Parking | `/<context>/v1/<resource>`       |
| `ThirdParty`        | `HomeGrown` connector → mock gateway          | Citizen, Payment | `/<context>/v1/<resource>`       |

See [`docs/architecture.md`](docs/architecture.md) for how the connectors work and
[`docs/federation-semantics.md`](docs/federation-semantics.md) for push/pull sync
behaviour (lifecycle, discovery, known limitations).

---

## Prerequisites

- **Docker** + **Docker Compose v2**
- ~6 GB memory available to Docker (WSO2 APIM is a large image; first boot ~1–2 min)
- Free host ports: `9443, 8243, 8280, 8000, 8001, 8002, 8090, 4000, 3000`

No other tooling is required — federation wiring uses the WSO2 REST API directly
from a throwaway container (no CLI to install).

---

## Quick start

```bash
git clone <repository>
cd wso2-gateway-federation-demo
./start.sh      # first run: build + start + federate;  later runs: just start
```

`start.sh` is interactive (build / cleanup options) but takes sensible defaults on
Enter, so a plain `./start.sh` works end-to-end. On completion it prints a
"Demo Ready" summary with copy-paste curl commands.

- **First run** verifies prerequisites, generates `.env` from `.env.sample`,
  builds the connector JARs and images, starts every service, then wires
  federation (via the WSO2 REST API).
- **Later runs** reuse `.env`, skip one-time setup, and just start the stack.
  Federation wiring is **self-healing**: it re-runs only if a gateway is not
  already serving its APIs.

Then open the **Federation Dashboard** at <http://localhost:3000>.

---

## Verify

```bash
# Each gateway serves ONLY its own APIs
curl -k https://localhost:8243/employee/employees      # WSO2 -> employees
curl -k https://localhost:8243/leave                   # WSO2 -> leave
curl    http://localhost:8000/vehicle/v1/vehicles      # Kong -> vehicles
curl    http://localhost:8000/parking/v1/parking       # Kong -> parking
curl    http://localhost:8090/citizen/v1/citizens      # 3rd  -> citizens
curl    http://localhost:8090/payment/v1/payments      # 3rd  -> payments

# Isolation: an API 404s on a gateway that does not own it
curl -k https://localhost:8243/vehicle/vehicles        # 404 on WSO2
curl    http://localhost:8000/employee/v1/employees    # 404 on Kong
```

| Console                | URL                                            |
|------------------------|------------------------------------------------|
| Federation dashboard   | <http://localhost:3000>                        |
| WSO2 Publisher         | <https://localhost:9443/publisher> (admin/admin) |
| WSO2 Dev Portal        | <https://localhost:9443/devportal>             |
| Mock gateway console   | <http://localhost:8090>                        |
| Kong Manager           | <http://localhost:8002>                        |

---

## Shutdown

```bash
./stop.sh
```

Interactive, defaulting to a **graceful stop that preserves all data**:

1. **Graceful stop** (default) — stops containers, keeps volumes, `.env`, and state.
2. **Stop + remove volumes** — wipes application state (re-initializes next start).
3. **Full cleanup** — stops, removes volumes **and** images built by this demo.

The **connector build cache** (the Maven cache volume `gateway-federation-m2` and
the compiled connector JARs) is a separate, expensive-to-rebuild artifact. It is
**always preserved by default**; options 2 and 3 ask a distinct `y/N` question
before touching it, so a cleanup never triggers a slow cold connector rebuild
unless you opt in. `start.sh`'s **Clean start** asks the same question.

A plain `./stop.sh` never removes user data, configuration, or the build cache.

---

## Project structure

```
.
├── start.sh / stop.sh          # one-command lifecycle
├── docker-compose.yml          # all services + init profile
├── .env.sample                 # every configurable value (copied to .env on first run)
├── control-plane/              # WSO2 image build (bakes in connectors; injects gateway types)
├── gateway-connectors/         # HomeGrown + KongLocal connectors (OSGi bundle Maven projects)
│   ├── homegrown/
│   └── konglocal/
├── services/
│   ├── backend/                # unified mock backend (all "original" upstreams)
│   ├── mock-gateway/           # mock third-party gateway + admin console
│   └── dashboard/              # live federation console
├── provisioning/               # federation wiring
│   ├── provision.sh            # waits for services, then runs deploy-apis.sh
│   ├── deploy-apis.sh          # creates/publishes/deploys the six APIs
│   └── openapi/                # OpenAPI specs for the six APIs
├── docs/                       # architecture & federation semantics
└── back/                       # parked files (reference only; safe to delete)
```

Every top-level component has its own README (control-plane, each connector, each
service, provisioning) — see [Documentation](#documentation).

---

## Configuration

All configurable values live in [`.env.sample`](.env.sample) (ports, images,
credentials). `start.sh` copies it to `.env` on first run; edit `.env` to
customise. **`.env` is never committed.**

---

## Development workflow

- **Change a Node service** (`services/*`): `docker compose up -d --build <service>`
  (`backend`, `mock-gateway`, or `dashboard`).
- **Change a connector** (`gateway-connectors/*`): delete its `target/*.jar`, then
  re-run `./start.sh` with a build option — `start.sh` rebuilds missing JARs and the
  control-plane image bakes them in. (A stale JAR is reused, so the delete is
  required to pick up source changes.)
- **Re-wire federation from scratch:** `./start.sh` → **Clean start** (option **2**);
  answer `y` to the connector-rebuild prompt only if you changed a connector.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Control plane slow to answer on first boot | Normal — WSO2 APIM takes ~1–2 min. `start.sh` waits for it. |
| A gateway returns 404 right after deploy | Router/discovery settle delay; retry after a few seconds. |
| Discovery silently stalls | Restart the control plane — clears an orphaned lock in `AM_TASK_LOCK`. See [docs/federation-semantics.md](docs/federation-semantics.md). |
| Ports already in use | Edit the `*_PORT` values in `.env`. |
| Reset everything | `./stop.sh` → option 2 or 3, then `./start.sh`. |

---

## Federation features

- **Bidirectional federation** — WSO2 *pushes* APIs to a gateway (deploy a
  revision to a federated env) and *discovers* APIs created directly on a gateway
  (reverse discovery). See [docs/federation-semantics.md](docs/federation-semantics.md).
- **Live console indicators** — the dashboard labels each external API
  **⇈ pushed by WSO2**, **⇩ discovered by WSO2**, or **⧗ pending discovery**, and
  shows gateway-native routes immediately (before discovery imports them).
- **Auto-publish toggle** — each federated gateway environment has an
  `auto_publish` option: `true` publishes discovered APIs to the Dev Portal
  automatically, `false` (default) leaves them in `CREATED` for review.
- **Discover → publish** — a discovered API already carries a gateway deployment;
  publishing it (lifecycle change, no re-deploy) surfaces it in the Dev Portal.

---

## Known limitations

- The mock gateway stores its route registry **in memory**; restarting it drops
  routes until re-pushed/re-created.
- BLOCKED / unsubscribed state is **not enforced** on the federated data planes
  (APIs use `authType: None`; WSO2 is control-plane only). See the semantics doc.
- Deleting a route directly on a gateway does **not** prune the API from WSO2
  (discovery is add/update only). Retire or delete the API in WSO2 instead.

---

## Documentation

- [docs/architecture.md](docs/architecture.md) — system overview, connectors, request flow
- [docs/federation-semantics.md](docs/federation-semantics.md) — push/pull sync behaviour & limitations

Component READMEs:

| Component | README |
|-----------|--------|
| WSO2 control-plane image | [control-plane/](control-plane/README.md) |
| HomeGrown connector | [gateway-connectors/homegrown/](gateway-connectors/homegrown/README.md) |
| KongLocal connector | [gateway-connectors/konglocal/](gateway-connectors/konglocal/README.md) |
| Unified backend | [services/backend/](services/backend/README.md) |
| Mock third-party gateway | [services/mock-gateway/](services/mock-gateway/README.md) |
| Federation dashboard | [services/dashboard/](services/dashboard/README.md) |
| Provisioning scripts | [provisioning/](provisioning/README.md) |
