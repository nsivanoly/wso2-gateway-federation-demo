# Documentation

Reference documentation for the WSO2 Gateway Federation demo. Start with the
root [`../README.md`](../README.md) for setup and run instructions.

| Document | What it covers |
|----------|----------------|
| [architecture.md](architecture.md) | System overview, the three gateway environments, how the custom connectors register and dispatch, request flow. |
| [federation-semantics.md](federation-semantics.md) | How state moves between the control plane and gateways: lifecycle → gateway effects (push), reverse discovery (pull), and known limitations. |

## Component READMEs

Each component also has its own README:

- [`../control-plane/`](../control-plane/README.md) — WSO2 image build
- [`../gateway-connectors/homegrown/`](../gateway-connectors/homegrown/README.md) — HomeGrown connector
- [`../gateway-connectors/konglocal/`](../gateway-connectors/konglocal/README.md) — KongLocal connector
- [`../services/backend/`](../services/backend/README.md) — unified mock backend
- [`../services/mock-gateway/`](../services/mock-gateway/README.md) — mock third-party gateway
- [`../services/dashboard/`](../services/dashboard/README.md) — federation dashboard
- [`../provisioning/`](../provisioning/README.md) — federation wiring scripts
