# PAS - Business Document Management System

A centralized system for managing the **lifecycle of business documents** at Company ABC,
a logistics provider offering port operations, transportation, warehousing, and cargo handling services.

The lifecycle runs end-to-end: customers → contracts (+ addenda) → versioned price lists →
per-period operational volumes → payment statements → configurable approval → asynchronous
e-signature → issuance and archiving, with notifications and an audit trail throughout.

> Course project for the Distributed Applications course (Ứng dụng phân tán).

## Group info: UDPT-06

| Student ID | Name                  |
|------------|-----------------------|
| 22127218   | Văn Bá Đức Kiên       |
| 22127327   | Trần Quốc Phong       |
| 22127411   | Lê Thị Thanh Thuỳ     |
| 22127452   | Lê Ngọc Vĩ            |

## Scope

- Customer, contract, and contract-addendum management
- Price list management (multiple versions, time-based validity)
- Recording actual operational volume per period (lockable periods)
- Creating and reconciling payment statements
- Configurable approval workflows (no hard-coded logic)
- E-signature integration (asynchronous, via a mock provider)
- Notifications, logging, and audit trail

## Architecture

| Service | Owns | REST | gRPC |
|---|---|---|---|
| Traefik (edge gateway) | Routing, RS256 JWT validation, rate limiting | `:18080` (host) | - |
| identity-service | Users, departments, roles, permissions, JWT issue | 8001 | 50051 |
| contract-service | Customers, contracts, addenda, attachments | 8002 | 50052 |
| pricing-service | Service catalog, price lists / versions / lines | 8003 | 50053 |
| operations-service | Operation periods, volume records | 8004 | 50054 |
| billing-service | Payment statements, statement lines (price snapshots) | 8005 | 50055 |
| workflow-service | Document types, workflow definitions / instances / steps | 8006 | 50056 |
| esign-service | Signing sessions, callback log | 8007 | 50057 |
| notification-service | Per-user notifications (event consumer) | 8008 | - |
| audit-service | The audit trail (read model) | 8009 | - |
| esign-mock-provider | External signing mock (outside the system boundary) | 9001 | - |
| web | Single-page frontend (role-based menus) | via gateway | - |

Supporting infrastructure (see `docker-compose.yml`): one PostgreSQL 16 instance with a
**separate database per service** (`pas_identity`, `pas_contract`, … — created by
`infra/docker/postgres/init`), Redis 7, and single-broker Kafka (KRaft) with topics
`pas.events` and `pas.audit` (plus DLTs).

Conventions worth knowing:

- External traffic goes through the edge as `/api/v1/{resource}` (the gateway strips
  `/api/v1` before forwarding). Service-to-service calls use gRPC, never the edge.
- Every state-changing service writes an outbox row in the business transaction; a relay
  publishes it to Kafka. Consumers deduplicate via a `processed_event` table.
- Backend: Java 25 + Spring Boot, Flyway migrations, images built with Jib.
  Frontend: React + TypeScript + Vite (`web/`).
- Kubernetes manifests: one Helm chart per deployable under `services/<svc>/helm` and
  `web/helm`, sharing `infra/helm/pas-common` (see `infra/helm/README.md`).

## Prerequisites

- Docker Compose v2
- GNU Make, run from Git Bash on Windows (`make help` lists all targets)
- OpenSSL (Git for Windows provides it) — needed once for `make keys`
- JDK 25 — only needed to build/test backend code directly (`make test`)
- Node.js + bun — only needed for frontend development in `web/`

## Start the services locally

```sh
# 1. Generate the JWT RS256 keypair (once per machine; files are gitignored).
#    Without this, the stack refuses to start.
make keys

# 2. Build all service images (Jib, local Docker daemon) and start the stack.
make up

# 3. Open the app.
#    Web UI (via gateway):      http://localhost:18080
#    Traefik dashboard:         http://localhost:18090
```

Useful commands:

```sh
make ps            # show containers
make logs          # tail all logs (or make logs-identity / logs-workflow / ...)
make down          # stop containers, keep DB volumes and image cache
make down-v        # stop containers AND wipe volumes (full DB reset)
make test          # run backend unit tests (excludes integration tests)
make test-integration  # run integration tests (needs Docker for Testcontainers)
```

To reset to a clean demo state: `make down-v && make up` — databases are recreated and
demo users/seed data are applied by Flyway migrations.

### Demo accounts

Demo users are seeded by migration (`services/identity/.../V8__seed_demo_users.sql`).
Each demo user's password is its username (e.g. `sales_officer` / `sales_officer`).

| Username | Role | Department |
|---|---|---|
| `admin` | `SYSTEM_ADMIN` (bootstrapped, default password `admin12345`, see `ADMIN_USERNAME`/`ADMIN_PASSWORD`) | IT |
| `sales_officer` | `SALES_OFFICER` | Sales |
| `sales_manager` | `SALES_MANAGER` | Sales |
| `legal_reviewer` | `LEGAL_REVIEWER` | Legal |
| `director` | `DIRECTOR` | Board |
| `accountant` | `ACCOUNTANT` | Accounting |
| `ops_officer` | `OPS_OFFICER` | Operations |
| `ops_privileged` | `OPS_OFFICER` + extra `volume:edit_locked`, `statement:cancel_approved` | Operations |

### Configuration

- `PAGINATION_CURSOR_SECRET`: must be the same private value of at least 32 characters on
  every contract-service replica. It signs pagination cursors; changing it invalidates
  cursors currently in use. Docker Compose supplies a local-development default that must
  be overridden in shared environments.
- `ADMIN_USERNAME` / `ADMIN_PASSWORD` (`identity-service`): bootstrap admin credentials,
  default `admin` / `admin12345`.

## Repository layout

```
docker-compose.yml   # full local stack (infra + all services + web)
Makefile             # keys / up / build / test / down targets
proto/               # shared gRPC contracts
libs/                # shared Java libraries (e.g. outbox relay)
services/            # the 10 backend services (+ esign mock provider)
web/                 # React + TypeScript + Vite frontend
infra/               # docker (traefik, postgres init), k8s (helm library), keys
docs/                # requirement.md, design docs (design/, diagrams/, figma/)
scripts/             # draw.io generation helpers
```
