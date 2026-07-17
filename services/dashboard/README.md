# dashboard

A live **federation console** over the WSO2 Publisher/Admin REST APIs. It shows
every API, which gateway hosts it, the original and gateway URLs, a "try it" on
both, and one-click deploy/remove of the on-demand **template** APIs — the
showcase for federated discovery.

Served at `http://localhost:3000`.

## Deploy model (important)

Template APIs are pushed **directly to the chosen gateway's runtime** (Kong Admin
API or the mock gateway's `/mock/deploy`) **without** a WSO2 ownership marker.
WSO2's reverse discovery then imports them within ~1 min. Deploying *through* the
Publisher would tag them managed and discovery would skip them — so the dashboard
deliberately does not. (The WSO2-native target has no separate runtime, so
"Deploy to WSO2" does go through the Publisher.) Remove deletes from the gateway
runtime **and** WSO2, so it does not re-discover.

## HTTP API

| Route | Purpose |
|-------|---------|
| `GET /` | the SPA (`public/index.html`) |
| `GET /api/health` | health probe |
| `GET /api/catalog` | every API with gateway, URLs, resources, deploy state |
| `POST /api/tryout` | server-side proxy invoking an API on its original or gateway URL |
| `POST /api/deploy` | direct-deploy a template `{ name, gateway }` |
| `POST /api/remove` | remove a template `{ name }` from gateway + WSO2 |

## Configuration

| Var | Default | Purpose |
|-----|---------|---------|
| `PORT` | `3000` | listen port |
| `CONTROL_PLANE_URL` | `https://control-plane:9443` | WSO2 REST base |
| `KONG_ADMIN_URL` | `http://kong:8001` | Kong Admin API (direct deploy) |
| `THIRD_PARTY_GW_URL` | `http://mock-gateway:8090` | mock gateway (direct deploy) |
| `WSO2_ADMIN_USER` / `WSO2_ADMIN_PASSWORD` | `admin` / `admin` | WSO2 credentials |
| `PUBLIC_WSO2_GW_URL` / `PUBLIC_KONG_URL` / `PUBLIC_MOCK_GW_URL` | `localhost:8243/8000/8090` | browser-clickable gateway URLs shown in the UI |
| `BACKEND_PORT` | `4000` | host port used to render clickable "original" URLs |

All are wired from [`../../.env.sample`](../../.env.sample) via
`docker-compose.yml`.

## Run

```bash
docker compose up -d --build dashboard
open http://localhost:3000
```
