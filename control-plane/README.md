# control-plane

Builds the **WSO2 API Platform 4.7.0** control-plane image for the demo. This is
the single control plane — Publisher, Dev Portal, Admin, Key Manager — and it
also hosts the **native WSO2 gateway** (the `Default` environment).

## What the image adds to stock WSO2

`Dockerfile` starts from `wso2/wso2am:4.7.0-alpine` and:

1. **Bakes in the two custom gateway connectors** — copies the prebuilt OSGi
   bundles into `repository/components/dropins/`:
   - `homegrown.gw.manager-*.jar` (from [`../gateway-connectors/homegrown`](../gateway-connectors/homegrown))
   - `konglocal.gw.manager-*.jar` (from [`../gateway-connectors/konglocal`](../gateway-connectors/konglocal))
2. **Injects the custom gateway types** — a `sed` appends `HomeGrown,KongLocal`
   to the `[apim] gateway_type` allowlist in the stock `deployment.toml` (APIM
   filters the Admin Portal's gateway-type list by this allowlist). We inject
   in place rather than shipping a full replacement file — minimal and
   upgrade-safe, and it avoids committing an encryption key (WSO2 generates one
   at first boot).

The build context is the **repository root** (see `docker-compose.yml`) so the
Dockerfile can reach the connector JARs under `gateway-connectors/`.

## Build

Via the normal flow:

```bash
./start.sh          # builds connector JARs if missing, then this image
```

Or directly:

```bash
docker compose build control-plane
```

## Ports

| Port | Purpose |
|------|---------|
| 9443 | Management consoles + REST APIs (Publisher/DevPortal/Admin) |
| 8243 | Native WSO2 gateway (HTTPS) — serves the `Default`-env APIs |
| 8280 | Native WSO2 gateway (HTTP) |

Configurable via `CONTROL_PLANE_*` in [`../.env.sample`](../.env.sample).

See [`../docs/architecture.md`](../docs/architecture.md) for how connectors
register and dispatch.
