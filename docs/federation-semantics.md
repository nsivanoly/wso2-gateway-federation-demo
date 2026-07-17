# Federation sync semantics

How state moves between the WSO2 control plane and the federated gateways
(Kong, mock third-party). These behaviours were verified empirically against
this demo.

The connector SPI has only four hooks — `deploy()`, `undeploy()`,
`discoverAPI()`, `isAPIUpdated()`. There is **no lifecycle hook**, so most
lifecycle transitions never reach the external gateways.

## Control plane → gateway (push)

| WSO2 lifecycle change | Connector called? | Effect on Kong / mock gateway |
|-----------------------|-------------------|-------------------------------|
| CREATED               | No                | Nothing (never deployed yet)  |
| PRE-RELEASED          | No                | Nothing (portal visibility)   |
| PUBLISHED             | No                | Nothing — *deploy-a-revision* is a separate action from *publish* |
| DEPRECATED            | No                | Route stays live and keeps serving |
| BLOCKED               | No                | ⚠️ Traffic still flows — WSO2 blocks at its own gateway/Key Manager, but a federated gateway has no WSO2 component in the request path |
| **RETIRED**           | **Yes** (`undeploy`) | **Route removed**, runtime returns 404 |

Removal from a gateway happens only on **RETIRE** or **API delete**. A bare
`undeploy-revision` and DEPRECATE do **not** remove the external route.

## Gateway → control plane (pull / reverse discovery)

`FederatedAPIDiscovery.discoverAPI()` runs on a fixed interval
(`apiDiscoveryScheduledWindow`, in minutes; the demo uses `1`).

| Direct action on the gateway | What discovery does next cycle |
|------------------------------|--------------------------------|
| **Add** a route              | Imported as a new API in WSO2  |
| **Modify** a route           | `isAPIUpdated()` sees the changed reference artifact → WSO2 updates the API |
| **Delete** a route           | ⚠️ **Not pruned.** Discovery only adds/updates; the WSO2 API lingers as stale (shown deployed) while the runtime 404s |

## Key takeaways

- **Push and pull are independent one-way channels.** There is no continuous
  bidirectional reconciliation of full state.
- To cleanly remove a federated API, **retire or delete it in WSO2** — do not
  delete the route directly on the gateway (that causes drift).
- **BLOCKED / unsubscribed is not enforced** on a federated data plane in this
  demo (APIs use `authType: None` and WSO2 is control-plane only).
- The mock gateway keeps its route registry **in memory**, so restarting that
  container drops all routes until they are re-pushed or re-created.

## Debugging discovery

Nothing logs at INFO per cycle. Set these to DEBUG in
`repository/conf/log4j2.properties` (WSO2 hot-reloads it):

```
org.wso2.carbon.apimgt.federated.gateway
org.wso2.homegrown
org.wso2.konglocal
```

If discovery silently stalls, a control-plane restart clears any orphaned lock
in `AM_TASK_LOCK` left by a cycle that was cancelled mid-flight.
