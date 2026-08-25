# PAS — Implementation Plan

> **Status:** master plan for the implementation phase. Every implementation agent session starts by reading this file.
> **Predecessor:** `design-plan.md:1` + `00-registry.md:1` defined the design phase (done). This document translates that design into executable, reviewable vertical slices.
> **Sources of truth (priority):** `requirement.md:1` (business rules MUST be followed) > `00-registry.md:1`/`01-architecture.md:1`/`mechanics.md:1` (locked decisions D1–D17) > `design/db/db-*.md` + `design/sequences/*.drawio` + `figma/*.png`. The registry is the naming authority — every service name, port, status enum, event type, permission code and gRPC method comes from it verbatim.
> **Principle for a new session:** if you are told "implement / review ABC feature", this file alone must give you full context on *what* to do, *how* to do it, *how to prove it is correct*, and *where to look*. Read it end-to-end before touching code.

---

## 1. How to use this plan

### 1.1 For a fresh agent session (copy-paste)

1.  Read this file top to bottom.
2.  Read **Tier-1** mandatory docs (§3–§5 summaries + the files themselves — do not trust the summaries alone).
3.  From §6 Session Catalog, find your assigned session card. Read its **Primary inputs** first.
4.  Then follow §8 Execution Protocol (TDD loop). Do not skip Phase A (failing tests before implementation) — tests are the review artifact.
5.  Follow §7 Quality Practices as a checklist on every commit. If this doc and a design doc conflict, the design doc + `requirement.md:1` win — record the discrepancy.
6.  Before marking done, satisfy the session card's **Done criteria** and §8.4 review gate.

### 1.2 Artifact per session

One service (or service pair) per session. One commit per logical unit, not one huge commit. A session is **not done** until `make test` + `make test-integration` green and reviewer approves the spec PR (Phase A).

### 1.3 Design vs implementation

Design is complete and frozen except via registry change notes (`00-registry.md:268` change log). If implementation reveals a gap, propose a registry delta + list affected finished artifacts — do not silently drift.

---

## 2. Business domain (condensed)

ABC Logistics: `requirement.md:9` centralized lifecycle `requirement.md:27` customers → contracts (+addenda) → price lists (versioned, time-bounded) → volumes per period (lockable) → payment statements (calculated from the three above) → configurable approval → e-signature (async) → issue & archive. Notifications + audit throughout.

Actors `requirement.md:14`: Kinh doanh (Sales), Khai thác (Operations), Kế toán (Accounting), Pháp chế (Legal), Ban Giám đốc (Board), Quản trị hệ thống (Admin), internal User (notifications), external E-Sign provider.

State machines are **not redrawn** — registry `00-registry.md:42` copies them verbatim plus D14 deviations:

* Contract/Addendum: `DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED → ACTIVE → EXPIRED` + `REJECTED, REVISION_REQUESTED, CANCELLED` (`requirement.md:96`, rules `CTR-01..07` `requirement.md:126`)
* Price list version: `DRAFT → SUBMITTED → APPROVED → EFFECTIVE → SUPERSEDED/EXPIRED` + `REJECTED` (`requirement.md:111`, `PRC-01..06` `requirement.md:140`)
* Payment statement: `DRAFT → CALCULATED → RECONCILED → SUBMITTED → APPROVED → SIGNING → SIGNED → ISSUED` + `REJECTED, REVISION, CANCELLED` (`requirement.md:153`, `PAY-01..07` `requirement.md:191`, `APR-01..07` `requirement.md:204`)
* Signing session: `PENDING_SEND → SIGNING → SIGNED/FAILED/CANCELLED` (`requirement.md:81`)

---

## 3. Architecture skeleton (locked)

Full view: `01-architecture.md:14` (mermaid) + service table `00-registry.md:7`.

| Service | DB | REST | gRPC | Owns |
|---|---|---|---|---|
| traefik (edge) | - | 80/443 | - | `00-registry.md:9` route `/api/v1/{resource}` → service, validate RS256 JWT + inject `X-User-Id/X-Roles` `mechanics.md:7`, rate-limit, TLS |
| identity-service | `pas_identity` | 8001 | 50051 | users/departments/roles/permissions, JWT issue, rotating refresh tokens |
| contract-service | `pas_contract` | 8002 | 50052 | customers, contracts, addenda, attachments |
| pricing-service | `pas_pricing` | 8003 | 50053 | service catalog, price lists/versions/lines |
| operations-service | `pas_operations` | 8004 | 50054 | operation periods, volume records |
| billing-service | `pas_billing` | 8005 | 50055 | payment statements, lines, snapshots |
| workflow-service | `pas_workflow` | 8006 | 50056 | document types, workflow definitions/instances/steps/actions |
| esign-service | `pas_esign` | 8007 | 50057 | signing sessions, callback log |
| notification-service | `pas_notification` | 8008 | - | notifications, processed_event |
| audit-service | `pas_audit` | 8009 | 50059 | audit trail (sole store, read model) |
| esign-mock-provider | - | 9001 | - | external mock (delayed webhook) |
| web-frontend | - | 3000 | - | single SPA, role-based menus |

**Locked decisions (read `design-plan.md:52` D1–D17 before coding):**

* **D1/D16** dual transport: REST/JSON `:80xx` public via edge (OpenAPI), gRPC `:505x` service-to-service only (`00-registry.md:24`).
* **D2** Kafka (KRaft) two topics `pas.events` + `pas.audit` `00-registry.md:68` key=`aggregate_id` (= document id), header `event_type`/`document_type`, `acks=all` + idempotence.
* **D3** status owned by document service; workflow owns only workflow state; `workflow.completed` flips document status.
* **D4** submit: local `DRAFT→SUBMITTED` + `outbox(workflow.start_requested, idempotency_key)` in one tx → background relay retries `StartInstance` (commit-then-dispatch, not reverse). Two constraints `workflow_instance.idempotency_key UNIQUE` + partial `(document_type_code,document_id) WHERE status='IN_PROGRESS'` both required `db-workflow.md:9`.
* **D5** approve race: `workflow_step_instance.version` optimistic lock `WHERE version=? AND status='ACTIVE'` → `ABORTED`.
* **D6** outbox on every emitting service (all but notification) `00-registry.md:140`; relay poll `ORDER BY created_at` single publisher per service `mechanics.md:22`.
* **D7** cross-service refs = opaque UUID + snapshots (PAY-03 price snapshot).
* **D11** auth at edge: RS256 15-min access + 14-day opaque rotating refresh (`db-identity.md:7`), `perm:role:{code}` Redis cache `mechanics.md:7`, services trust headers, fail-closed on Redis down.
* **D12** one Postgres instance, DB per service `infra/docker/postgres/init/01-create-databases.sql:1`, no cross-DB queries.
* **D15** centralized audit: `audit.recorded` via outbox, `audit-service` sole store.
* **D17** `status_history` append-only local per stateful entity `00-registry.md:134`.

Sync matrix `00-registry.md:86`, event catalog `00-registry.md:66`, status transitions `00-registry.md:186`.

---

## 4. Repository map

```
pas/
  build.gradle.kts:1          root (Java 25, Spring Boot 4.0.0)
  settings.gradle.kts:1       includes proto, libs:common, services:identity (+future services)
  gradle/libs.versions.toml:1 version catalog (Spring Boot, JJWT 0.12.6, Flyway 11.1.0, spring-grpc 1.0.3, protobuf 4.33.4)
  libs/common/src/main/java/com/abclogistics/pas/common/  BaseEntity, outbox/OutboxEvent, audit/AuditRecorder, security/HeaderAuthenticationFilter, PermissionCache, error/* 
  proto/src/main/proto/{service}/v1/*.proto  centralized proto (callee owns file, callers depend, package pas.<service>.v1) per 00-registry.md:115
  services/identity/          template for all services (Spring Web, Data JPA, Security, Validation, Redis, Flyway, grpc-spring-boot-starter, OpenAPI)
    src/main/resources/db/migration/V1__*.sql
    src/main/java/...  controller/service/domain/repository/grpc
    src/test/java/...  JwtRoundTripTest.java, AuthFlowIT.java (integration tag excluded by default build.gradle.kts:42)
  services/<new>/             scaffold by copying identity structure, rename DB/ports per 00-registry.md:7
  infra/docker/postgres/init/01-create-databases.sql:1
  infra/docker/traefik/       traefik.yml + dynamic/jwt.yml (patched by `make keys`)
  infra/keys/                 jwt-private.pem (gitignored) + jwt-public.pem
  Makefile:1                  keys, up, build, test, test-integration, logs, down-v
  docs/requirement.md:1
  docs/design/00-registry.md  naming authority
  docs/design/01-architecture.md  container + gRPC views
  docs/design/mechanics.md    M1 permission cache + M2 outbox relay (load-bearing)
  docs/design/db/db-*.md + db-*.drawio  8 schemas (generate via scripts/gen_er_drawio.py)
  docs/design/sequences/README.md + seq-*.drawio  8 flows (gen via scripts/gen_seq_drawio.py)
  docs/figma/*.png            22 screens (16 main + 6 variants)
```

**Commands**

```bash
make keys              # RSA 2048 + patch Traefik (once)
make up                # compose up --build -d  (gateway :18080, dashboard :18090)
./gradlew test         # unit only (excludes tag integration)
./gradlew test --tests "*IT"   # or make test-integration (needs Docker)
make test-integration
make down-v             # wipe DBs
```

Service template `services/identity/build.gradle.kts:1`: copy, change `DB_URL/DB_USER/DB_PASSWORD/ports` in `docker-compose.yml`, keep `libs:common` + `proto` dependencies, `useJUnitPlatform { excludeTags("integration") }` (protobuf generation centralized in `proto/`).

---

## 5. Resource access policy (read broadly)

**Mandatory for every session (Tier-1):**

* `requirement.md:1` — at least the mapped section + rule table (e.g. session 3 → §4.1–4.3 + CTR-0x). Never assume rule wording.
* `00-registry.md:1` — §1 ports/DBs, §2 doc types, §3 status enums, §4 event envelope + headers, §5 gRPC matrix + §5.1 conventions, §6 column/outbox/audit conventions, §7 roles/permissions, §9 transitions. Use names verbatim.
* `01-architecture.md:1` — which arrows are sync vs async, which service calls which.
* `mechanics.md:1` — M1 cache (writer/startup/hourly/TTL/fail-closed), M2 relay claim SQL + cancel-vs-dispatch handoff. Do not re-derive these.

**Primary per session (Tier-2, listed in each card) but NOT exclusive:**

* The session's `db-*.md` + `db-*.drawio` (choices, rule mapping, constraints, indexes)
* Its sequence diagram(s) `sequences/README.md:21` + `seq-*.drawio`
* Its Figma PNGs `figma/*.png`

**Expansion rule — do NOT artificially limit:**

> If you need context to do something well, read it. A billing agent must read `db-contract.md` + `ContractInternal.GetContract` proto to snapshot correctly (PAY-03). A contract agent must read `db-workflow.md:9` to understand the `idempotency_key` it writes. A pricing agent must read `db-billing.md:5` to know how `GetEffectivePriceList` is consumed historically. A frontend agent reads all. Checking another service's `src/main/java` or `src/main/proto` is expected. The Tier-2 list is a *starting* set, not a permission boundary. When you expand, note it in the PR description.

---

## 6. Session catalog

Each card is self-contained. Sessions are ordered by dependency; within a parallel group they can run concurrently.

### Session 0 — Foundation (cross-cutting, do first)

* **Objective:** harden shared kernel so 1–8 don't rebuild it differently.
* **Scope:**
  * `libs:common`: `BaseEntity`, `OutboxEvent/OutboxRepository`, `AuditRecorder/AuditPayload`, `HeaderAuthenticationFilter/AuthenticatedUser/SecurityUtils/PermissionCache`, `GlobalExceptionHandler/ApiError/DomainException`, `SecurityCommonConfig`.
  * Proto module: `proto/` shared Gradle module publishing `.proto` per `00-registry.md:115` (callee owns file, callers depend).
  * Infra: Kafka (KRaft single broker) + `pas.events` (3 partitions) + `pas.audit` (1) `design-plan.md:52` D2, Redis, `make keys` wiring, `infra/docker/postgres/init` already done.
  * Outbox relay abstract + `PermissionCache` sweeper `mechanics.md:22`.
* **Primary inputs:** `00-registry.md:6` + `mechanics.md:7` + `libs/common/src/main/java` existing + `services/identity/build.gradle.kts:1` + `01-architecture.md:14`.
* **Expand to:** any `db-*.md` that defines `outbox` shape.
* **Deliverables:** `libs:common` tests, `proto` build, `docker-compose.yml` Kafka/Redis, `infra/docker/traefik/dynamic/jwt.yml` headerMap `Makefile:48`.
* **Tests:** `OutboxRelayClaimTest` (poll `ORDER BY created_at`, claim `WHERE ... OR claimed_at < now()-N` `mechanics.md:24`), `PermissionCacheFailClosedTest`, `HeaderFilterStripTest`.
* **Done:** `make test` green, `docker compose up` → identity can issue JWT and permission cache warms `mechanics.md:15`.

### Session 1 — Identity & Auth

* **Objective:** complete the only service that has no upstream dependency and blocks all others (D11).
* **Scope API/DB:** `pas_identity` `db-identity.md:1` `app_user/department/role/permission/user_role/role_permission/refresh_token/outbox`. REST `POST /auth/login|refresh|logout`, `GET /users, /roles`, `PUT /roles/{code}/permissions` (locks `role FOR UPDATE` `db-identity.md:11`), `POST /users`, `PUT /users/{id}/roles`. gRPC `IdentityInternal.ListUsersByRole(ACTIVE only)` `00-registry.md:94`. Figma `01-login.png, 02-dashboard.png, 16-administration.png, 02.1-sidebar-by-role`, `15-audit-log.png` search not here.
* **Rules:** two-token RS256 15m/14d rotation+family reuse detection, no blacklist `db-identity.md:7`, Redis `perm:role:*` writer `mechanics.md:7`.
* **Primary inputs:** `requirement.md:42` §6 JWT + `db-identity.md` + `sequences/README.md:66` seq-01 + `00-registry.md:131` token claims/headers.
* **Expand to:** `mechanics.md:7` M1, `db-workflow.md:12` (how callers use `ListUsersByRole`).
* **Tests:** `JwtRoundTripTest.java` existing + `RefreshRotationReuseDetectionIT`, `RolePermissionReplaceLockIT` (concurrent `FOR UPDATE`), `PermissionCachePropagationIT`, `ListUsersByRoleActiveOnlyIT`.
* **Done:** login→access+refresh→refresh rotation→reuse revokes family→disabled user excluded; `perm:role:*` warm+hourly; edge validates and injects headers.

### Session 2 — Workflow Engine

* **Objective:** the configurable approval engine that every document service depends on.
* **Scope:** `pas_workflow` `db-workflow.md:1` `document_type_config/workflow_definition(version_no,is_active partial)/workflow_step_definition/workflow_instance(idempotency_key UNIQUE + partial IN_PROGRESS)/workflow_step_instance(version)/step_assignee/workflow_action/outbox`. REST `GET|POST /workflow-definitions`, `PUT .../steps` (only `is_active=false` under `FOR UPDATE`), `POST .../activate` (deactivate-then-activate tx `mechanics.md:22`), `POST /workflow-steps/{id}/actions` (approve/reject/revision, APR-03 comment check), `GET /inbox` tabs Assigned/Submitted/Completed. gRPC `ValidateStartable/StartInstance/CancelInstance/GetInstanceByDocument` `00-registry.md:98`.
* **Rules:** 4.7 no hard-code, `approval:act` + `step_assignee` contextual APR-01 `db-workflow.md:14`, D4 two constraints, D5 version guard, `sla_hours` + `workflow.step_overdue`.
* **Primary inputs:** `requirement.md:74` + `db-workflow.md` + `seq-03-contract-approval` + `seq-08-workflow-configuration` + `seq-02-outbox` + `00-registry.md:186` transitions.
* **Expand to:** `db-contract.md:12`/`db-pricing.md:17`/`db-billing.md:16` (callers' submit wiring), `mechanics.md:22` stale-claim handling.
* **Tests:** `IdempotencyKeyPermanentVsPartialTest`, `ConcurrentApproveABORTEDTest`, `StepAssigneeSnapshotWholeChainTest`, `EmptyAssigneeFailsSubmitTest`, `ActivationDeactivateThenActivateTxTest`, `GetInstanceByDocumentSelectionTest`.
* **Done:** submit via `ValidateStartable` then `StartInstance` (retry safe), race-safe approve, cancel vs dispatch handoff proven, progress `GetInstanceByDocument` returns `current_step?` + snapshot steps.

### Session 3 — Contract + Customer + Addendum

* **Objective:** Sales-owned aggregate (`design-plan.md:35` merge).
* **Scope:** `pas_contract` `db-contract.md:1` `customer/customer_contact/contract/addendum/attachment/status_history/outbox/processed_event`. REST `POST/GET /customers, /contracts, /addenda, /attachments`, `POST .../submit` (D4 outbox row + `ValidateStartable` pre-check), `POST .../cancel` (M2 handoff), `GET .../progress` (proxy `GetInstanceByDocument`). Events consume `workflow.completed` → status flip per `00-registry.md:186`, produce `document.expiring` direct `00-registry.md:68`. No `esign.session_completed` consumption `db-contract.md:14`. Figma `03-customers.png,04-customer-detail.png,05-contracts.png,05.1-row-actions,06-contract-detail.png,07-addenda.png`. Rule CTR-01..07 `requirement.md:126`, `TERM_EXTENSION` renewal D14b, addendum effects on parent `00-registry.md:204`.
* **Primary inputs:** `requirement.md:39` §4.1–4.3 + `db-contract.md` + `seq-03-contract-approval` + `00-registry.md:186` CONTRACT transitions + Figma contract/customer.
* **Expand to:** `db-workflow.md` (submit wiring), `db-esign.md` (frontend composes, not this service), `db-billing.md` (how `GetContract` snapshots are consumed).
* **Tests:** `CTR01EditGuardTest`, `CTR02SubmitRequiresAttachmentTest`, `SubmitCommitThenDispatchPENDINGTest`, `CancelStaleClaimForcesDispatchTest`, `AddendumActiveAppliesToParentTxTest`, `PeriodLookupByContractIdTest`.
* **Done:** submit→SUBMITTED(pending)→UNDER_REVIEW→APPROVED→ACTIVE(scheduler)→EXPIRED, revise flows, attachment required, contract snapshots include `service_group/vat_rate/payment_term`.

### Session 4 — Pricing (catalog + versions)

* **Objective:** the versioned, time-bounded price master with overlap guard.
* **Scope:** `pas_pricing` `db-pricing.md:1` `service_item/price_list/price_list_version/price_line` `EXCLUDE USING gist ... WHERE status IN (APPROVED,EFFECTIVE)` `btree_gist` `db-pricing.md:8`, `scope_key`, `UNIQUE(price_list_id,version_no)`. gRPC `GetEffectivePriceList` (historical, precedence `CONTRACT > CUSTOMER+GROUP > CUSTOMER`, `date=period_end`) + `GetServiceItem` `00-registry.md:92`. REST version CRUD, submit/approve/revise. Figma `08-price-lists.png`. Rules PRC-01..06 `requirement.md:140`, truncate-then-approve `00-registry.md:217`.
* **Primary inputs:** `requirement.md:56` §4.4 + `db-pricing.md` + `seq-04-pricelist-version` + `seq-03` (approval ref).
* **Expand to:** `db-operations.md:9` + `db-billing.md:7` (consumers of `GetServiceItem/GetEffectivePriceList`), proto of those callers.
* **Tests:** `PRC03ExclusionApprovedEffectiveTest`, `TruncateThenApproveTxTest`, `GetEffectiveHistoricalSupersededTest`, `PrecedenceContractShadowsCustomerTest`, `AddendumIdValidFromTest`.
* **Done:** `CREATE EXTENSION btree_gist`, overlap enforced, historical lookup correct, successor approval truncates predecessor in same tx.

### Session 5 — Operations (volumes + period lock)

* **Objective:** lockable global monthly periods feeding billing.
* **Scope:** `pas_operations` `db-operations.md:1` `operation_period(period_code YYYY-MM, OPEN→LOCKED no unlock)/volume_record(contract_id,customer_name/service_name/unit snapshots, record_no)`. REST `POST /periods, /volume-records`, `POST /periods/{code}/lock` (`volume:lock_period`), `PUT /volume-records/{id}` (guard `OPEN` or `volume:edit_locked` + audit `db-operations.md:8`). gRPC `ListVolumes(contract_id,period_code)` returns `period_state/start/end + volumes` `00-registry.md:93`. Event `operations.period_locked` direct `00-registry.md:79`. Figma `09-volume-records.png,09.1-adjustment-rules`.
* **Primary inputs:** `requirement.md:62` §4.5 + `db-operations.md` + `seq-05-volume-period-lock` + `seq-06` PAY-02 slice.
* **Expand to:** `db-contract.md` (validate `GetContract`), `db-pricing.md` (`GetServiceItem`), `db-billing.md:7` (billing's LOCKED gate).
* **Tests:** `LockIdempotentTest`, `PostLockEditRequiresPermissionAndAuditsTest`, `ListVolumesIncludesPeriodBoundsTest`, `ServiceCodeValidatedAtEntryTest`.
* **Done:** OPEN→LOCKED only, post-lock edit traced, billing sees only LOCKED.

### Session 6 — Billing (integration hub)

* **Objective:** statement calculation that snapshots three upstream sources.
* **Scope:** `pas_billing` `db-billing.md:1` `payment_statement/statement_line/statement_line_volume/status_history/outbox/processed_event` snapshots `contract_no/customer_name/period_start-end/price_list_no+version_no/payment_term/vat_rate/service_name/unit/unit_price` `db-billing.md:5`, `CALCULATED→RECONCILED→SUBMITTED→...` `00-registry.md:219`, `adjusts_statement_id` PAY-05, `due_date` from `payment_term`. REST `POST /statements/calculate` (contract→volumes→price ordered pulls `sequences/README.md:110`), `POST .../reconcile`, `PUT ...` controlled edit→DRAFT D14f, `POST .../submit`. Consumes `workflow.completed` + `esign.session_completed` `00-registry.md:81`. Figma `10-payment-statements.png,11-payment-statement-detail.png` (reconciliation panel, Source volumes tab).
* **Primary inputs:** `requirement.md:68` §4.6 + `db-billing.md` + `seq-06-payment-statement` + `00-registry.md:92` sync matrix.
* **Expand to:** `db-contract.md:11` (`GetContract` effective values), `db-pricing.md:12` (effective at `period_end`), `db-operations.md:9` (LOCKED), `db-workflow.md` (submit wiring), `db-esign.md:5` (SIGNING transition).
* **Tests:** `CalculateSnapshotsPAY03Test`, `LOCKEDGatePAY02Test`, `UnpricedService422Test`, `TotalNegativeRejectPAY04Test`, `ControlledEditResetsToDraftAndSourceMANUALTest`, `EsignFailedToRevisionPAY07Test`.
* **Done:** unpriced service fails 422, PAY-03 snapshots immutable, revision loops, audit outboxed.

### Session 7 — E-Signature + Mock Provider

* **Objective:** async external integration `requirement.md:80` (single REST exception D16).
* **Scope:** `pas_esign` `db-esign.md` `signing_session(document_type/id, PENDING_SEND→SIGNING→SIGNED/FAILED/CANCELLED, idempotency_key UNIQUE + partial IN_PROGRESS, attempts/last_error) + callback_log + status_history` `db-esign.md:5`. gRPC `EsignInternal.CreateSigningSession`, `GetSigningPayload` guards `00-registry.md:95` (`APPROVED` for contract/addendum, `{APPROVED,SIGNING}` for billing `sequences/README.md:139`). REST `POST /signing-sessions` (manual D10, owner writes `outbox(esign.session_requested)`), `GET /signing-sessions/by-document/{type}/{id}` (frontend composition), `POST /callbacks/esign` webhook `00-registry.md:110` (version-guarded apply). `esign-mock-provider:9001` `01-architecture.md:72` delayed POST callback. Figma `13-e-signatures.png`. `esign:send|cancel` permissions.
* **Primary inputs:** `requirement.md:80` §4.8 + `db-esign.md` + `seq-07-esign` + `00-registry.md:97` + `mechanics.md:22` (same dispatch pattern as D4).
* **Expand to:** `db-contract.md:14` + `db-billing.md:16` (both writers of `esign.session_requested`), `services/contract|billing/src/main/java` send actions.
* **Tests:** `ManualSendRequiresApprovedTest`, `OutboxDispatchCreateSessionIdempotentTest`, `CallbackDuplicateNoOpTest`, `CallbackBeforeCommitPENDING_SENDTest`, `RetriesExhaustedFAILEDTest`, `SigningStatusNeverMutatesContractStatusTest`.
* **Done:** manual send, 3-attempt retry, duplicate callback idempotent, `SIGNING` never leaks into contract status `00-registry.md:205`.

### Session 8 — Notification + Audit

* **Objective:** the two fan-out consumers (`01-architecture.md:66`).
* **Scope:** `pas_notification` `notification(recipient,type,document ref,read_at)/processed_event` + `pas_audit` `audit_record(source_service,entity_type/id/no,action,actor,before/after,changes,note)` `db-audit.md`. Kafka `pas.events` → notification (all events `00-registry.md:70`) + role resolution `ListUsersByRole` `00-registry.md:102`; `pas.audit` → audit (`INSERT ON CONFLICT DO NOTHING` `sequences/README.md:76`). REST `GET /notifications?unread`, `PATCH /notifications/{id}/read` (`notification:read`), `GET /audit-records?entity_type=&actor_id=&from=&to=` (`audit:view_all`) paged + `AuditInternal.ListRecords` gRPC for History tab `00-registry.md:103`. Figma `14-notifications.png,15-audit-log.png,15.1-detail-drawer`.
* **Primary inputs:** `requirement.md:86` §4.9 + `requirement.md:91` §4.10 + `db-notification.md` + `db-audit.md` + `seq-02-outbox-audit-notification`.
* **Expand to:** every service's `outbox(audit.recorded)` + `processed_event` consumers, `services/*/src` to enumerate auditable actions.
* **Tests:** `NotificationFanOutPerGroupTest`, `ProcessedEventDedupTest`, `AuditPKDedupNoProcessedEventTest`, `DocumentExpiringSelfHealsDirectPublishTest`, `AuditHistoryTwoSourcesTest` (local `status_history` sync vs `ListRecords` eventual).
* **Done:** lost event replays, DLQ on poison, History tab reads two sources `sequences/README.md:74`.

### Session 9 — Frontend + Gateway + Hardening

* **Objective:** wire everything through the edge and ship.
* **Scope:** `web-frontend:3000` single SPA role menus `02-dashboard.png` + `traefik` routing `/api/v1/{resource}` strip + `jwt-auth@file` `docker-compose.yml:58` + rate-limit, `infra/k8s/` manifests (DB per service, `NetworkPolicy` sealed internal `01-architecture.md:9`), `docs/design/99-traceability.md` matrix every requirement 4.1–4.10 + CTR/PRC/PAY/APR → artifact `design-plan.md:172`.
* **Primary inputs:** `01-architecture.md` + `figma/*.png` (all 22) + `sequences/README.md:33` actor coverage + `requirement.md:232` §6 tech checklist.
* **Expand to:** all services' `src/main/resources/application.yml` + `openapi.yaml`.
* **Tests:** `GatewayInjectsHeadersStripClientTest`, `E2EContractToStatementToSignTest` (Testcontainers compose), `K8sManifestRendersTest`.
* **Done:** `make up` boots full stack, frontend composes `GET /contracts/{id}` + `GET /signing-sessions/by-document/...` `00-registry.md:205`, `docker-compose.yml` publishes only edge, K8s applies on minikube.

---

## 7. TDD & quality practices (MANDATORY — reviewer checks these)

### 7.1 Spec-first TDD (every session)

```
Phase A — Spec PR (blocking review):
  1. Draft openapi.yaml (REST) + .proto diff (gRPC) from registry §5.
  2. Write failing tests FIRST: unit (*Test.java) + contract (*ContractTest.java) + IT skeleton (*IT.java tag integration).
     Tests encode: §9 transitions, business rules (CTR/PRC/PAY/APR tables), Seq invariants (D4/D5/D6/M2), Figma action visibility.
  3. Push with implementation stubbed → reviewer approves spec. Do NOT implement yet.

Phase B — Green:
  4. Implement Flyway V1__ + domain + service + controller + gRPC service until tests green.

Phase C — Verify:
  5. `make test` (unit) + `make test-integration` (containers) green, `make up` manual smoke, add ArchUnit if needed.
```

Why: tests are the review artifact. Reviewing tests catches missing rule coverage cheaper than reviewing impl. Agent loops on tests until green.

### 7.2 Test taxonomy

* **Unit** (`src/test/java/**/ *Test.java`, no tag): domain logic, state-machine guards, mappers, `PermissionCache` resolver. Mock gRPC with in-process channel.
* **Integration** (`*IT.java` tag `integration` `build.gradle.kts:42`): spins Postgres/Redis/Kafka via Testcontainers, flies migrations, hits real `DataSource`/`RedisTemplate`/Kafka. One IT per hard part (exclusion constraint, idempotency, race).
* **Contract** (`*ContractTest.java`): asserts proto/REST shape matches `00-registry.md:115`; breaking proto fails build.
* **Coverage rule:** every row in the session's rule table + every `00-registry.md:186` transition + every `alt` in its sequence must have a test. No happy-path-only PR.

### 7.3 Code quality checklist

* **Spring stack:** `spring-boot-starter-web|data-jpa|data-redis|validation|security|actuator`, `springdoc-openapi` (`libs.versions.toml:9`), `spring-grpc 1.0.3` `libs.versions.toml:11`, `flyway 11.1.0`, `jjwt 0.12.6`. Java 25 toolchain `build.gradle.kts:25`.
* **Ports/DBs/names:** from `00-registry.md:7` only — no invented ports. One DB per service `01-create-databases.sql:1`, no cross-DB query.
* **Proto:** callee owns `.proto`, shared `proto/` module, method names `GetContract/ListVolumes/...` `00-registry.md:86`, `idempotency_key` field on mutating gRPC `00-registry.md:121`, deadlines 2s read/5s write, status mapping `NOT_FOUND/INVALID_ARGUMENT/FAILED_PRECONDITION/ABORTED/PERMISSION_DENIED/UNAVAILABLE` `00-registry.md:116` + `CancelInstance NOT_FOUND retry` exception.
* **REST:** edge strips `/api/v1` `01-architecture.md:9`; services serve `/auth,/users,/contracts,...` under `800x`. Every service trusts `X-User-Id/X-Roles` headers `mechanics.md:7` — no JWT crypto in services. OpenAPI docs the public surface `requirement.md:232`.
* **Security:** layer-1 edge validates RS256 `Makefile:48`; layer-2 services resolve `perm:role:{code}` via `PermissionCache` `mechanics.md:7`, fail-closed `PERMISSION_DENIED` on Redis down, never check `hasRole()`. Passwords `bcrypt` `db-identity.md:12`.
* **Data:** `BaseEntity` `id UUID PK gen_random_uuid()`, `created_at/by`, `updated_at/by`, `version` only where race is real (step instance, contract edit) `00-registry.md:128`. Status `TEXT CHECK` vs `00-registry.md:42`. Cross-refs opaque UUID + snapshots `00-registry.md:130` (two business-key exceptions `service_item.code`, `period_code`). `status_history` INSERT+SELECT only `00-registry.md:134`. `outbox` shape `00-registry.md:140` (`event_id=PK`, `aggregate_id`=Kafka key=document id), relay claim SQL `mechanics.md:24`. `processed_event` on every consumer `00-registry.md:146`. No per-service `audit_log` — `audit.recorded` via outbox `00-registry.md:138`.
* **Async:** `acks=all`+idempotence producer, `published_at` only after ack, retry `claimed_at`+`retry_count`, stale claim only re-claimed, cancellation only `claimed_at IS NULL` `mechanics.md:29`. Fan-out = consumer group per service, filter via headers before deserialize `00-registry.md:68`. D9 events (`document.expiring`, `period_locked`) direct without outbox.
* **Idempotency/race:** `idempotency_key uuid NOT NULL UNIQUE` permanent + partial `WHERE status='IN_PROGRESS'` both `db-workflow.md:48`. Approve `WHERE version=? AND status='ACTIVE'` `db-workflow.md:10`. Cancel vs dispatch per `mechanics.md:30`.
* **Error handling:** `GlobalExceptionHandler → ApiError` `libs/common/error`, `DomainException/ConflictException/NotFoundException`, Bean Validation on DTOs, never leak stack.
* **Observability:** actuator `/actuator/health`, structured logging with `actor_id, entity_type/id, event_id`, Flyway migrations versioned.
* **Figments:** never invent tables/fields/events/screens not traceable to `requirement.md:1` or D# — `design-plan.md:15` anti-over-engineering.

### 7.4 Review gate (copy to PR description)

```
- [ ] Tier-1 + session Tier-2 docs read, names verbatim registry
- [ ] Phase A spec PR approved (failing tests cover rule table + §9 transitions + seq invariants)
- [ ] Flyway V1__ runs on fresh DB, btree_gist where needed
- [ ] Permission checks use permission codes, fail-closed on Redis down
- [ ] Header auth trusted, no JWT in service
- [ ] Outbox + status_history written in same tx as status column
- [ ] Idempotency/optimistic-lock tests green, race test with concurrent threads
- [ ] gRPC deadlines + status mapping correct, proto owned by callee
- [ ] `make test` + `make test-integration` green, `make up` smoke
```

---

## 8. New session execution protocol (copy-paste)

**Input:** "Implement/review ABC feature" + optional session number. If none, pick from §6 by keyword (e.g. "customer" → 3, "price" → 4, "statement" → 6, "sign" → 7, "inbox" → 2).

1.  **Context load (30 min):** read this plan §1–§5, then Tier-1 files fully, then session card's Tier-2. Skim the referenced `src/main/java` of collaborators.
2.  **Inventory (10 min):** list REST endpoints, gRPC methods, tables, events, Figma screens, business rules for this session. Note unknown — read the other `db-*.md`/code that owns it (policy §5).
3.  **Phase A — spec (reviewable):** write `openapi.yaml` diff + `.proto` + `*Test.java`/`*IT.java`/`*ContractTest.java` that **fail**. Commit `test: session-N spec (failing)` and request review. Wait.
4.  **Phase B — green:** scaffold module from `services/identity/build.gradle.kts:1` (copy, rename DB/ports, add to `settings.gradle.kts:7`), write Flyway `V1__init_<service>.sql` from `db-*.drawio`, implement domain→repository→service→controller→gRPC service, outbox relay, schedulers. Loop `make test` until green.
5.  **Phase C — verify:** `make test-integration` with Testcontainers (Postgres `16`, Redis `7`, Kafka KRaft). Add `processed_event`/`status_history` asserts. `make up` + `curl`/gRPCurl smoke via gateway.
6.  **Submit:** PR with checklist §7.4, traceability table (rule→test class), registry impact note (if any). Do not merge Tier-1 name changes without bumping `00-registry.md:268` change log + scanning other `db-*.md`.

**If reviewing:** read Phase A tests first — if coverage missing (e.g. `PRC-03` no exclusion test, D4 no stale-claim test, `volume:edit_locked` no audit assert), request changes before reading impl.

---

## 9. Parallelism & ordering

```
0 Foundation ─┬─→ 1 Identity ─→ 2 Workflow ─┬─→ 3 Contract ─┬─→ 6 Billing ─→ 7 E-Sign ─→ 8 Notify+Audit ─→ 9 Frontend
              │                              ├─→ 4 Pricing ──┘              │
              │                              └─→ 5 Operations ──────────────┘
              └─→ infra (Kafka/Redis/DBs) available for all after 0
```

Group P0=0, P1=1,2, P2a=3,4 parallel, P2b=5 after 3+4, P3=6 then 7, P4=8+9. Billing (6) is the bottleneck — do not start it before 3+4+5 green.

---

## 10. Common pitfalls (read before coding)

* **PRC-03 vs PRC-04:** approving successor before truncating predecessor always fails `EXCLUDE`. Order: `predecessor.valid_to = successor.valid_from -1` then `successor.status=APPROVED` same tx `00-registry.md:217` `seq-04`.
* **Stale claim != dead worker:** `mechanics.md:29` timestamp lease has no fencing. Never `UPDATE cancelled_at` on a claimed row — force dispatch to resolution then cancel.
* **Idempotency key reuse:** generate once at `outbox` creation (`workflow.start_requested`, `esign.session_requested`) `00-registry.md:142`, reuse on every retry — never regenerate `mechanics.md:37`.
* **PERMISSION_DENIED vs 404 reuse:** `CancelInstance NOT_FOUND` is retryable only in cancel handoff `00-registry.md:117`; elsewhere `NOT_FOUND` is terminal.
* **Snapshot before call:** billing snapshots `vat_rate/payment_term/period_start-end` at calculate tx `db-billing.md:5` (PAY-03) — do not re-derive.
* **Frontend composes, services don't:** contract-service never calls esign-service `db-contract.md:14`; frontend calls both REST via gateway.

---

## 11. Traceability closeout

After 0–8, session 9 fills `design/99-traceability.md` `design-plan.md:172`: matrix every `requirement.md:39` §4.1–4.10 + CTR/PRC/PAY/APR + §5.5 rows → covering sessions. Gap = bug before done.

---

## Appendix — quick lookup

* **Ports/DBs:** `00-registry.md:7`
* **Events:** `00-registry.md:66` (`workflow.instance_started/step_assigned/step_actioned/completed`, `esign.session_completed`, `document.expiring`, `operations.period_locked`, `audit.recorded`)
* **Transitions:** `00-registry.md:186` (contract/addendum, price_list version, statement, signing, period, workflow)
* **Permissions:** `00-registry.md:248` (`customer:read/write`, `contract:cancel_active`, `volume:edit_locked|lock_period`, `statement:cancel_approved`, `approval:act`, `esign:send/cancel`, `workflow:configure`, `user:manage`, `audit:view_all`) — check permissions, never roles `00-registry.md:265`
* **Diagrams:** `sequences/README.md:21` (8 flows) generated by `scripts/gen_seq_drawio.py`, ER by `scripts/gen_er_drawio.py`
* **Figma:** `docs/figma/*.png` 22 files — registry §3 badge label mapping applies (e.g. `SUBMITTED`→"Under Review" for price/statement `00-registry.md:53`)

> **One-line rule for a new session:** read Tier-1 + your card's Tier-2, write failing tests that encode your card's rule table + transitions + sequence invariants, get spec approved, then make them green with the shared `libs:common` + outbox/permission patterns. If you need more context, read more — don't guess.

