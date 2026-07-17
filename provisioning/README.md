# provisioning

One-shot **federation wiring**, run as the `init` service (Compose `init`
profile). `start.sh` invokes it after the stack is healthy; it is idempotent.

## Scripts

| File | Role |
|------|------|
| `provision.sh` | Entry point. Waits for the mock gateway, Kong Admin, and the control plane to be reachable, then runs `deploy-apis.sh`. |
| `deploy-apis.sh` | Registers the federated gateway environments and creates → publishes → deploys the six APIs, each to its one gateway. |
| `openapi/` | OpenAPI 3.0 specs for the six APIs (`employee`, `leave`, `vehicle`, `parking`, `citizen`, `payment`). |

## What `deploy-apis.sh` does

1. Authenticates to WSO2 (`apictl` + a Publisher/Admin REST token).
2. **Registers two federated environments** (`Kong` → `KongLocal`,
   `ThirdParty` → `HomeGrown`) with `provider: external` and the connector's
   `admin_url`/`proxy_url` in `additionalProperties`. `provider: external` is
   what makes revision deployments dispatch to the connector's `GatewayDeployer`.
3. For each API: `apictl init` from its OpenAPI spec, sets context / backend /
   (for federated APIs) `gatewayVendor: external` + `gatewayType`, imports it,
   opens resource security, deploys a revision to its target environment, and
   publishes it to the Dev Portal.

| API | Environment | Gateway type | Backend |
|-----|-------------|--------------|---------|
| EmployeeAPI, LeaveAPI | `Default` | native WSO2 | `http://backend:8080` |
| VehicleAPI, ParkingAPI | `Kong` | `KongLocal` | `http://backend:8080` |
| CitizenAPI, PaymentAPI | `ThirdParty` | `HomeGrown` | `http://backend:8080` |

## Run

Normally automatic (`./start.sh`). To run/re-run manually:

```bash
docker compose run --rm init
```

Idempotent — environments are upserted and API imports use `--update`, so
re-running reconciles rather than duplicates. Requires `bin/apictl` (downloaded
by `start.sh` if missing).
