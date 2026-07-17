# backend

A single **unified mock backend** that stands in for every "original" upstream in
the demo. One image, reached in-cluster as `http://backend:8080`; all six core
APIs (and the on-demand dashboard templates) point at it.

## Behaviour

It **infers the collection from the request path**, tolerating gateway context
prefixes and versioned segments, then serves deterministic seed data:

```
/employee/employees      -> collection "employees"
/vehicle/v1/vehicles     -> collection "vehicles"   (drops the "v1" segment)
/citizen/v1/citizens/3   -> item 3 of "citizens"
/leave                   -> collection "leave"
```

| Verb + path | Behaviour |
|-------------|-----------|
| `GET /…/<collection>` | list all items |
| `GET /…/<collection>/{id}` | fetch one (404 if absent) |
| `POST /…/<collection>` | create (auto-increment id), returns 201 |
| `DELETE /…/<collection>/{id}` | remove (404 if absent) |
| `GET /health` | `{ status: "UP", service: "backend" }` |

Seeded collections: `employees`, `leave`, `vehicles`, `parking`, `citizens`,
`payments` (the six core APIs) plus `departments`, `payrolls`, `fuels`, `tolls`,
`taxes`, `licenses` (dashboard templates). Unknown collections get generic
placeholder items. State is in-memory (POST/DELETE mutate at runtime; a restart
resets to seed).

## Configuration

| Var | Default | Purpose |
|-----|---------|---------|
| `PORT` | `8080` | in-container listen port |
| `BACKEND_PORT` | `4000` | host-exposed port (set in `docker-compose.yml` / `.env`) |

## Run

```bash
docker compose up -d --build backend
curl http://localhost:4000/employee/employees
```
