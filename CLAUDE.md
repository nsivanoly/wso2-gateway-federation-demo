# CLAUDE.md

# WSO2 API Platform v4.7.0+ Gateway Federation Demo

> **Implementation note.** This document is the original design brief. The built
> implementation evolved in one respect: the conceptual *translation adapter*
> service (referenced below as "Adapter Service / Port 3002") is **not** a
> separate service. Its role — translating WSO2 API metadata into a third-party
> gateway's format and deploying it — is performed **inside the control plane by
> the HomeGrown gateway connector**, which pushes directly to the mock gateway's
> `/mock/deploy`. There is no `:3002` adapter and no `adapter/` directory. For the
> authoritative current-state architecture and layout, see
> [README.md](README.md) and [docs/architecture.md](docs/architecture.md).

## Objective

Build a self-contained Proof of Concept (PoC) demonstrating the new **Gateway Federation** capability introduced in **WSO2 API Platform v4.7.0+**.

The objective is **not** to showcase API development, authentication, or gateway features individually.

The objective is to clearly demonstrate that:

- WSO2 API Platform operates as a centralized **Control Plane**
- Multiple heterogeneous gateways can participate as independent **Data Planes**
- Configuration distribution differs depending on gateway type
- Native WSO2 federation and third-party gateway federation can coexist simultaneously
- APIs remain deployed only to their intended runtime

This demo should be simple enough to run locally using Docker Compose while clearly illustrating enterprise federation concepts.

---

# Demo Architecture

The demo contains three runtime groups.

```
                    +--------------------------------+
                    | WSO2 API Platform 4.7 Control  |
                    |                                |
                    | Publisher                      |
                    | Dev Portal                     |
                    | Admin                          |
                    | Key Manager                    |
                    +---------------+----------------+
                                    |
                     API Metadata / Federation
                                    |
        ---------------------------------------------------------
        |                         |                            |
        |                         |                            |
        ▼                         ▼                            ▼

 +----------------+     +----------------------+     +----------------------+
 | WSO2 GW  |     | Kong Gateway         |     | Mock Third Party GW  |
 | (Option A)     |     | Native Federation    |     | Custom Adapter       |
 +----------------+     +----------------------+     +----------------------+
                                                |
                                                |
                                     Translation Service
                                            Port 3002
```

---

# Gateway Types

## Control Plane

WSO2 API Platform 4.7.x

Responsibilities

- API Publisher
- Developer Portal
- API Lifecycle
- API Products
- Policies
- Application Management
- Subscription Management
- Global Governance

No application traffic should pass through the Control Plane.

---

## Option A

### WSO2 Gateway

Represents a centralized shared runtime.

Characteristics

- Native WSO2 runtime
- Receives deployments directly from Control Plane
- Executes APIs locally
- Demonstrates official WSO2 gateway federation

---

## Option B

### Kong Gateway

Represents an agency-managed runtime.

Characteristics

- Native Gateway Federation support
- Receives deployments directly
- Synchronizes APIs from Control Plane
- No custom adapter required

---

## Option C

### Mock Third-Party Gateway

Represents any unsupported API Gateway.

Examples

- NGINX
- APISIX
- Gravitee
- Mule
- Apigee
- Azure APIM
- AWS API Gateway
- Custom Gateway

Since WSO2 has no native connector for these platforms, federation is demonstrated using a custom translation layer.

---

# Third Party Federation Flow

```
WSO2 Control Plane

      |

Export API Metadata

(api.yaml)

      |

HTTP POST

      |

Adapter Service

localhost:3002

      |

Translate

api.yaml

↓

Target Gateway REST API

↓

Deploy API

↓

Runtime Ready
```

The adapter is intentionally lightweight.

Its responsibility is only to:

- receive metadata
- parse api.yaml
- convert into gateway-specific format
- invoke mock deployment APIs

No actual gateway implementation is required.

---

# APIs

To clearly demonstrate federation, every gateway owns a unique API set.

**Do NOT deploy the same API to multiple gateways.**

---

## WSO2 Gateway

### Employee API

Base Path

```
/employee
```

Endpoints

```
GET /employees

GET /employees/{id}

POST /employees
```

---

### Leave API

Base Path

```
/leave
```

Endpoints

```
GET /leave

POST /leave

DELETE /leave/{id}
```

---

## Kong Gateway

### Vehicle API

Base Path

```
/vehicle
```

Endpoints

```
GET /vehicles

GET /vehicles/{id}

POST /vehicles
```

---

### Parking API

Base Path

```
/parking
```

Endpoints

```
GET /parking

POST /parking

DELETE /parking/{id}
```

---

## Third Party Gateway

### Citizen API

Base Path

```
/citizen
```

Endpoints

```
GET /citizens

GET /citizens/{id}

POST /citizens
```

---

### Payment API

Base Path

```
/payment
```

Endpoints

```
GET /payments

POST /payments

GET /payments/{id}
```

---

# Demo Sequence

## Step 1

Start all containers.

---

## Step 2

Open Publisher.

Create six APIs.

---

## Step 3

Deploy

Employee API

↓

WSO2 GW

---

Deploy

Leave API

↓

WSO2 GW

---

Deploy

Vehicle API

↓

Kong Gateway

---

Deploy

Parking API

↓

Kong Gateway

---

Deploy

Citizen API

↓

Third Party Gateway

---

Deploy

Payment API

↓

Third Party Gateway

---

## Step 4

Observe

WSO2 Gateway receives deployment natively.

---

Observe

Kong receives federation natively.

---

Observe

Third Party Adapter receives

```
api.yaml
```

Translate

↓

Mock deployment

↓

Gateway updated

---

## Step 5

Invoke APIs

Each gateway exposes only its own APIs.

Example

WSO2 Gateway

```
GET /employee/employees

GET /leave
```

Kong

```
GET /vehicle/vehicles

GET /parking
```

Third Party

```
GET /citizen/citizens

GET /payment/payments
```

No API should appear on any other gateway.

---

# Mock Adapter

Port

```
3002
```

Responsibilities

- Receive deployment notification
- Accept api.yaml
- Parse metadata
- Simulate translation
- Generate gateway payload
- Deploy to mock gateway
- Return success

Example log

```
Received API:

Citizen API

Version:

v1

Converting api.yaml

↓

Mock Third Party Format

↓

Deploy Successful
```

---

# Suggested Repository Layout

```
gateway-federation-demo/
│
├── control-plane/              # WSO2 image build (bakes in connectors; injects gateway types)
│   └── Dockerfile
│
├── gateway-connectors/         # custom WSO2 gateway connectors (OSGi bundle Maven projects)
│   ├── homegrown/              #   -> mock third-party gateway (HomeGrown type)
│   └── konglocal/              #   -> Kong Admin API           (KongLocal type)
│
├── services/                   # runtime Node services
│   ├── backend/                #   unified mock backend for every "original" upstream
│   ├── mock-gateway/           #   mock third-party gateway + admin console
│   └── dashboard/              #   live federation console
│
├── provisioning/               # one-shot federation wiring
│   ├── provision.sh            #   waits for services, then runs deploy-apis.sh
│   ├── deploy-apis.sh          #   creates/publishes/deploys the six APIs
│   └── openapi/                #   OpenAPI specs for the six APIs
│
├── bin/apictl                  # WSO2 CLI (downloaded on first run; gitignored)
├── docs/                       # architecture & federation semantics
├── back/                       # parked files (reference only; safe to delete)
│
├── docker-compose.yml
├── .env.sample                 # every configurable value (copied to .env on first run)
├── start.sh
├── stop.sh
└── README.md
```

> Note: the native WSO2 gateway is built into the control-plane image (no separate
> `wso2-gateway/`), and Kong runs from the stock `kong:3.7` image via Compose (no
> `kong-gateway/` build dir). The third-party gateway needs no adapter shim — the
> HomeGrown connector federates it directly.

---

# start.sh

Interactive startup script.

```
⚙️ Choose build option

1) Build with cache

2) Build without cache

3) Skip build
   (default)

------------------------------------------------

🧹 Choose cleanup option before starting

1) Clean start
   Remove containers
   Remove volumes

2) Keep existing
   (default)

3) Exit

------------------------------------------------

Starting services...

✓ Control Plane

✓ WSO2 Gateway

✓ Kong Gateway

✓ Third Party Gateway

✓ Adapter

✓ Backend APIs

Demo Ready
```

---

# stop.sh

Interactive shutdown script.

```
Choose shutdown option

1) Graceful stop only
   (default)

   Preserves all data

2) Stop and remove volumes

   Wipes application databases

3) Full cleanup

   Stops containers

   Removes volumes

   Removes images
```

---

# Technologies

- WSO2 API Platform 4.7.x
- WSO2 Gateway
- Kong Gateway
- Docker Compose
- Node.js (Mock APIs)
- Node.js Adapter Service
- REST APIs
- YAML Metadata Translation

---

# Success Criteria

The PoC is considered successful when:

- Single WSO2 Control Plane manages all APIs.
- WSO2 GW receives deployments through native federation.
- Kong Gateway receives deployments through native federation.
- Third-party gateway receives translated metadata through the custom adapter.
- Every gateway hosts a unique set of APIs with no duplication.
- API invocation succeeds independently through each gateway.
- The adapter logs the metadata translation process, demonstrating vendor-agnostic extensibility.