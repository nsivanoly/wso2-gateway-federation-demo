# mock-gateway

A **mock third-party ("home-grown") gateway** — the `Option C` runtime. It stands
in for any gateway WSO2 has no native connector for. The control plane's
[HomeGrown connector](../../gateway-connectors/homegrown) federates APIs to it by
calling `/mock/deploy`; this service then proxies runtime traffic to the backend.

Reached in-cluster as `http://mock-gateway:8090`.

## Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /` (or `/ui`, `/console`) | **Admin console** (HTML) — live view of deployed routes and their federation state |
| `GET /health` | health probe |
| `POST /mock/deploy` | deploy/replace a route `{ name, version, context, backendUrl, resources, managedBy? }` |
| `POST /mock/undeploy` | remove a route by `context` or `name` |
| `GET /mock/registry` | raw route registry |
| `GET /mock/status` | gateway status + routes, each annotated with its federation state (cross-checked against the control plane) |
| `/<context>/…` | any other path → proxied to the matching route's backend |

## Admin console

Served at `http://localhost:8090`. Shows each deployed route with a state badge,
computed by cross-checking the WSO2 control plane:

- **⇩ pushed by WSO2** — deployed by the HomeGrown connector (`managedBy` marker present)
- **direct / discovered** — created directly on the gateway and since imported into WSO2 by reverse discovery
- **direct / pending** — created directly, not yet discovered

It also has an **Add / Import API** form that deploys a route directly onto the
gateway (no `managedBy` marker) to demonstrate reverse discovery.

## Configuration

| Var | Default | Purpose |
|-----|---------|---------|
| `PORT` | `8090` | listen port |
| `MOCK_GATEWAY_PORT` | `8090` | host-exposed port |
| `CONTROL_PLANE_URL` | `https://control-plane:9443` | for the console's state cross-check |
| `WSO2_ADMIN_USER` / `WSO2_ADMIN_PASSWORD` | `admin` / `admin` | control-plane credentials |

> The route registry is **in memory** — restarting the container drops all routes
> until they are re-pushed or re-created. See
> [`../../docs/federation-semantics.md`](../../docs/federation-semantics.md).

## Run

```bash
docker compose up -d --build mock-gateway
open http://localhost:8090
```
