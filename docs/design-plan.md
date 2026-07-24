# PAS — Design Plan & Working Guidelines

> **Status:** master plan for the design phase. Every design session starts by reading this file and `docs/design/00-registry.md`.
> **Sources of truth:** [requirement.md](requirement.md) (business requirements — MUST be followed) and [usecase.drawio](diagrams/usecase.drawio) (actor ↔ use case mapping).
> **This document defines:** the service decomposition, how services connect, the ordered list of artifacts to produce (DB schemas, sequence diagrams, UI wireframes), per-artifact research & design guidelines, and the critic/review protocol.

---

## 1. How to use this plan

1. Artifacts are produced **one at a time**, in the order of §6 (Roadmap). Never start an artifact whose inputs are not done.
2. Each artifact follows the same loop: **Research → Draft → Critic subagent → Revise → Update registry → Commit** (§7).
3. All names (services, tables, statuses, events, endpoints) come from the **registry** (`00-registry.md`). If an artifact needs a name that doesn't exist yet, add it to the registry *in the same step* and check whether already-finished artifacts are affected.
4. **Figma designs** (`docs/figma/`, 16 full-screen PNGs, one per major screen) are the UI design input for the whole system. Precedence: **requirement.md > Figma > invention**. Figma-only fields/features are adopted when they are cheap, visibly designed, and don't contradict a requirement — each adoption is documented in the consuming artifact's md. Where Figma contradicts the requirement (e.g. status vocabularies, e-sign shown as a workflow step), the requirement wins and the discrepancy is recorded. Every phase consults the relevant screens: schemas harvest field-level expectations, sequences match the designed actions, UI work annotates the screens instead of redrawing them.
5. **Anti-over-engineering rule (global):** every table, field, event, screen, or lifeline must be traceable to a requirement line, a coded business rule (CTR/PRC/PAY/APR), or a locked decision in §4. If it can't, cut it. Forbidden by default: CQRS, event sourcing, saga frameworks, BPMN engines, service mesh, per-actor frontends. (audit-service **is** a separate read model — the one deliberate exception, argued in D15; it does not license others.)

## 2. Business understanding (condensed)

**Domain:** ABC Logistics manages the lifecycle of business documents: customers → contracts (+ addenda) → price lists (versioned, time-bounded) → operational volumes (per period, lockable) → payment statements (calculated from the three above) → configurable approval → e-signature (async) → issue & archive. Notifications and audit trail throughout.

**Actors** (from usecase.drawio): Kinh doanh (Sales), Pháp chế (Legal), Ban Giám đốc (Board), Khai thác (Operations), Kế toán (Accounting), Quản trị hệ thống (Admin), Người dùng nội bộ (any internal user — notifications), and the external **E-Sign provider**.

**Document types & state machines** — the requirement already defines them in mermaid; **do not redraw**, copy the status enums verbatim into the registry:
- Contract/Addendum: `Draft → Submitted → UnderReview → Approved → Active → Expired`, plus `Rejected`, `RevisionRequested`, `Cancelled` (rules CTR-01…07).
- Price list: `Draft → Submitted → Approved → Effective → Superseded/Expired`, plus `Rejected` (rules PRC-01…06).
- Payment statement: `Draft → Calculated → Reconciled → Submitted → Approved → Signing → Signed → Issued`, plus `Rejected`, `Revision` (rules PAY-01…07), plus `Cancelled` added as a documented deviation (D14c).
- Signing session (separate state machine per 5.5): `PendingSend → Signing → Signed / Failed / Cancelled` (4.8).

**Non-negotiable technical constraints (req §6):** ≥4 business services + API Gateway; FastAPI or Java with OpenAPI; DB/schema per service (choice must be justified); PostgreSQL; Redis; Kafka or RabbitMQ for ≥1 async flow; Docker Compose; K8s manifests; JWT; logging/validation/error handling.

## 3. Service decomposition (locked baseline)

8 business services + gateway + 1 external mock + 1 frontend. Each maps to a requirement section; none is speculative.

| # | Service | Owns (data) | Requirement | Key notes |
|---|---------|-------------|-------------|-----------|
| 0 | **api-gateway** | nothing (stateless) | req §6 | Routing, JWT validation, rate limiting (Redis), request logging. |
| 1 | **identity-service** | users, departments, roles, permissions, token blacklist (Redis) | 4.х admin, §6 JWT | Issues JWT. Resolves role → users for workflow step assignment. |
| 2 | **contract-service** | customers, contracts, addenda, attachments | 4.1, 4.2, 4.3 | Customer merged with contract: same owner (Sales), strong data affinity, avoids a nano-service. Justify in architecture doc. |
| 3 | **pricing-service** | service catalog (loại dịch vụ), price lists, price list versions, price lines | 4.4 | Owns the service-item catalog; operations & billing reference `service_code`. Overlap rule PRC-03 enforced here (Postgres exclusion constraint). |
| 4 | **operations-service** | operation periods, volume records | 4.5 | Period lock is the core mechanic. |
| 5 | **billing-service** | payment statements, statement lines (with **price snapshots**, PAY-03), adjustment statements | 4.6 | The integration hub: sync-reads contract, pricing, operations at calculation time. |
| 6 | **workflow-service** | document types config, workflow definitions (versioned), workflow instances, step instances, actions | 4.7, APR rules, 5.5 | Generic engine keyed by `(document_type, document_id)`. NOT a BPMN engine: ordered steps, each step = role/assignee resolution. Definition versions pinned per instance. |
| 7 | **esign-service** | signing sessions, callback log | 4.8 | Thin adapter: sends docs to the mock provider, receives webhooks, emits events. Kept separate because async integration is an explicit learning goal of the course. |
| 8 | **notification-service** | notifications (per user), processed-event dedup | 4.9 | Pure event consumer + REST for list/mark-read. |
| 9 | **audit-service** | the audit trail of every service | 4.10 | Read model, **not a bounded context**: no invariants, no domain logic, no state machine. Sole store of the trail (D15). |
| — | **esign-mock-provider** | (throwaway state) | 4.8 | Standalone tiny app *outside* the system boundary: accepts a signing request, waits, POSTs a callback. Makes the async boundary real. |
| — | **web-frontend** | — | all UI | ONE app, role-based menus. Not per-actor apps. |

## 4. How services connect (locked decisions)

Decisions are numbered `D#` so artifacts and critics can reference them.

- **D1 — Sync vs async split.** Synchronous calls for queries and commands that need an immediate answer; async events over the broker for anything that is a *consequence* (notifications, status reactions, audit-relevant milestones). At least the workflow→notification and esign flows are async (satisfies req §6). Transport of the sync half: see D16.
- **D2 — Broker = RabbitMQ** (topic exchange `pas.events`, routing keys = event types). Simpler to operate and to demo than Kafka; fan-out routing fits notifications. Kafka acceptable substitute — if changed, only the registry's transport section changes.
- **D3 — Status ownership.** Each document's status column lives in its owning service. workflow-service owns only workflow instance/step state. On terminal workflow outcomes it publishes `workflow.completed {outcome}`; the owning service consumes it and flips document status (state machine transition enforced there). Progress display ("ai đang giữ hồ sơ") is a sync query to workflow-service.
- **D4 — Workflow start is idempotent.** Owner services call `WorkflowInternal.StartInstance` with `(document_type, document_id, idempotency_key)`; a partial unique index (one *active* instance per document) makes double-submit harmless (5.5 double-submit row).
- **D5 — Race-safe approval.** Approving a step = single UPDATE guarded by optimistic locking / row lock on the step instance; second concurrent approve fails cleanly (5.5 race row, APR-02).
- **D6 — Outbox pattern** on event-emitting services (workflow, esign at minimum; same table shape via shared lib): event row written in the business transaction, background publisher pushes to RabbitMQ with retry (5.5 lost-event row, APR-07). Consumers keep a small `processed_event` table for idempotent consumption.
- **D7 — Cross-service references are opaque UUIDs + snapshots.** No cross-service FKs. Where history must survive later changes, denormalize a snapshot (unit price + service name on statement lines per PAY-03; customer name on statements).
- **D8 — Addendum price changes go through pricing, manually.** If an addendum changes unit prices, **Sales manually creates a new price list version from the addendum screen** after the addendum is approved; it carries `addendum_id`, `valid_from` = addendum effective date, and goes through the normal price-list approval flow. No automation, no `addendum.*` event. Billing therefore resolves prices from pricing-service only — one source of truth (4.3 "nghiệp vụ sau thời điểm hiệu lực dùng thông tin mới" + PRC-04).
- **D9 — Expiry warnings** (contract/price list sắp hết hạn, 4.9): the *owning* service runs a scheduled check and emits `document.expiring` events — published **directly, without outbox**: a lost warning self-heals on the next scheduled run, so outbox here would be over-engineering. Notification-service never queries other services' data.
- **D10 — E-sign scope & initiation.** E-sign applies to **contracts, addenda and payment statements** (price lists are internal, never signed). Backing: req 4.8 speaks of "hồ sơ" generically, req §2 gives Sales "theo dõi tình trạng ký kết" (tracking contract signing), and the Figma E-Signatures screen shows CTR/ADD/PMT sessions. esign-service is generic over `(document_type, document_id)`. **Send-for-signing is a manual action by the document owner** (Accounting for statements per use case edge e18; Sales for contracts/addenda) on an **Approved** document; esign-service fetches the signing payload via a sync GET from the owning service. No event-triggered auto-send on approval (APR-05's "có thể" is exercised as a manual trigger). Signing status is a **separate** state machine from approval status (5.5 last row) — in particular, e-sign is **not** a workflow step even though the Figma admin builder renders it as one: the builder card maps to `document_type_config` (esign enabled + provider), and the document-detail timeline is a composed view (workflow steps + signing session status). Signing is **optional** for contracts/addenda: an Approved contract with no signing session still becomes Active at its effective date.
- **D11 — Two-layer authorization.** Gateway: JWT validity + coarse role check. Services: contextual checks — especially workflow-service verifying *the caller is the assignee of the current step* (APR-01: "kiểm tra role là chưa đủ"). Named permissions (`volume.edit_locked`, `contract.cancel_active`, `statement.cancel_approved`, `workflow.configure`, `user.manage`, `audit.view_all` — registry §10) are checked by the owning service directly against the JWT's `permissions[]` claim (registry §6) — no separate sync call to identity; same mechanism and staleness as `roles[]`.
- **D12 — DB layout.** One PostgreSQL instance in dev, **schema per service**, no cross-schema queries (justification req §6 demands: service isolation with student-project ops simplicity; swap to DB-per-service in K8s if desired without design change).
- **D13 — Tech stack for design artifacts.** Backend is **Java 26 / Spring Boot / Maven** (the project is already generated on it). Concretely for the diagrams: Spring Web for the REST surface, `grpc-spring-boot-starter` + `protobuf-maven-plugin` for the gRPC surface (D16), Spring Data JPA + PostgreSQL, Spring AMQP for RabbitMQ. Nothing in the diagrams may depend on the stack beyond naming.
- **D14 — Transition table + documented deviations.** The registry holds an **allowed-transitions table** per document type: `(from_status, trigger, to_status)` with `trigger ∈ {user action, workflow event, scheduler}` — the single source for schema CHECKs, sequence arrows, and UI action visibility. Four holes in the requirement's state diagrams are resolved as documented deviations (permitted by req §5 "nhóm được phép điều chỉnh, nhưng phải giải thích"):
  - (a) Contract `Active → Cancelled` is allowed as a controlled user action (needed by CTR-06 and use case "Hủy hợp đồng").
  - (b) **Renewal (gia hạn) = an addendum extending validity** (consistent with 4.3 + CTR-07); no separate renewal mechanism or status.
  - (c) Payment statement gets a `Cancelled` terminal state reachable from `Approved`/`Signed` via a controlled cancel flow (PAY-05 "hủy theo quy trình"); correction of amounts still goes through adjustment statements.
  - (d) **Time-driven flips** (`Approved → Active` at effective date per CTR-05; `Active/Effective → Expired` at end date) are executed by a scheduled job in the owning service — the same scheduler that emits D9 expiry warnings.
  - (e) **Contract/addendum signing states** (per D10 + Figma): `Approved → (send e-sign) → Signing → (callback ok) → Signed`; `Signing → (fail/cancel) → Approved` (retry point); both `Approved` and `Signed` flip to `Active` at effective date. Statements keep the requirement's own signing transitions (fail → Revision).
  - (f) **Statement rework paths**: `REJECTED → (explicit revise) → DRAFT` (the requirement's statement diagram leaves Rejected terminal; we mirror CTR-04's delegated choice — an explicit, audit-logged user action, never auto-resubmit) and `CALCULATED → (controlled edit) → DRAFT` (4.6 "đối chiếu/chỉnh sửa có kiểm soát" — editing invalidates the calculation, so the status honestly returns to DRAFT).

- **D15 — Centralized audit (supersedes the earlier per-service `audit_log` decision).** 4.10 is served by a dedicated **audit-service** holding the only copy of the trail; `audit_log` is removed from every service schema. Producers write an `audit.recorded` row to their **`outbox` in the same transaction as the business change** — this, not the storage location, is what makes the trail unlosable (5.5 "Event bị mất"); a naive async audit write would drop records whenever a service died between commit and publish. Consequences, accepted:
  - **Cost:** the non-status part of the History tab is a cross-service, eventually-consistent read; unavailable if audit-service is down (business operations unaffected — outbox drains on recovery).
  - **Bought:** cross-entity queries per actor/department/date (§2 admin oversight), one retention policy, and tamper-evidence — a service can no longer rewrite its own history (audit holds INSERT+SELECT only).
  - **Never interprets `changes jsonb`** — producers format the description in their own language; audit stores and indexes it. This line is what stops a central store becoming a god-service.
  - `workflow_action` stays in workflow-service: domain data (4.7 "người đang xử lý"), read synchronously.

- **D17 — Status history is append-only domain data, in the owning schema.** Every state-machine entity (`contract`/`addendum`, `price_list_version`, `payment_statement`, `signing_session`) gets a local `status_history` table: INSERT+SELECT only, one row per transition, written in the **same transaction** as the status column update, carrying the §9 `trigger_kind` (U/W/E/S) and its provenance.
  - **Not audit-service**, because a business rule may never depend on it: eventually consistent (non-deterministic evaluation), remote (unlockable in the transaction it guards), deliberately uninterpreted. Audit is for humans; the engine needs local facts. No coded rule needs history today, but the obvious next ones do ("rejected twice ⇒ escalate", revision counts, SLA math).
  - **Keep the status column too:** deriving it from the latest row breaks PRC-03 (an `EXCLUDE` predicate can only reference its own row) and every list screen's filter/sort. Column = cache, log = record, written together and cross-checkable.
  - **Side benefit:** the status timeline returns to a local synchronous read, softening D15. Duplication with audit is intentional — different readers, different availability; local is the source.
- **D16 — Internal transport is gRPC; the public surface stays REST.** Every service runs a **dual stack**: REST/JSON on `80xx` for user traffic through the gateway (this is the surface OpenAPI/Swagger documents, so req §6 is satisfied by the API that is actually public), and gRPC on `505x` for service-to-service calls only, unreachable from outside the network. The `/internal/**` REST path convention is retired — there is no internal HTTP route left to expose by accident. Contract-first: `.proto` owned by the callee, published to a shared `proto/` module. Conventions and the HTTP→gRPC status-code mapping live in registry §5.1.
  - **One exception:** the esign provider webhook. It is machine-to-machine, so the rule would say gRPC, but the caller is **outside** the boundary — a real provider is handed a callback URL, not a `.proto`, and making the mock speak gRPC would simulate an integration that doesn't exist. (The approver's step-action endpoint is *not* an exception: it is user traffic, hence REST by the rule.)
  - **Cost:** protobuf codegen, no `curl` on internal endpoints (`grpcurl` + reflection in dev), two ports per service. **Bought:** a typed generated contract instead of hand-written JSON clients — a silently-changed payload shape is the multi-service failure that hurts most.

**Sync dependency matrix (coarse — refined in registry):**

| Caller → Callee | Purpose |
|---|---|
| billing → contract | contract validity & terms at period (PAY-01) |
| billing → pricing | effective price version + lines at period (PAY-01, PAY-03 snapshot source) |
| billing → operations | confirmed volumes of a locked period (PAY-02) |
| workflow → identity | resolve step role → concrete users at step activation (assignees snapshot) |
| esign → billing | fetch signing payload of an Approved statement (sync GET, per D10) |
| * → workflow | start instance, query progress, act on step |
| frontend → gateway → * | everything user-facing |

**Event catalog (seed — full schema in registry):** `workflow.instance_started`, `workflow.step_assigned`, `workflow.step_actioned` (approve/reject/revision + comment), `workflow.completed {outcome}`, `esign.session_completed {result}`, `document.expiring`, `audit.recorded` (D15), all with envelope `{event_id, event_type, occurred_at, actor, document_type, document_id, payload}`.

## 5. Cross-artifact consistency: the registry

`docs/design/00-registry.md` is the living contract between artifacts. It contains (and only it may define):

1. Service names, ports, schema names.
2. `document_type` enum: `CONTRACT`, `ADDENDUM`, `PRICE_LIST`, `PAYMENT_STATEMENT`.
3. Status enums per document type — copied verbatim from requirement state diagrams (English names), **plus the D14 deviations** (e.g. payment statement `Cancelled`).
4. Event catalog: type, producer, consumers, payload fields.
5. Sync API dependency matrix: gRPC method-level (service, method, purpose) + the REST exceptions (D16).
6. Common column conventions (id/created/updated/version), centralized-audit payload shape (D15), outbox table shape.
7. Roles & departments list (Sales, Legal, Board, Operations, Accounting, Admin) **and default workflow definitions per document type** (seeded from req §3: e.g. contract = Legal → Board; statement = Accounting lead → Board) so seq diagrams and the ui-admin workflow builder agree on the same example chains.
8. Shared UI components list (status badge, workflow stepper, history tab, notification bell, task inbox).
9. Allowed state transitions per document type: `(from_status, trigger, to_status)` table per D14 — including the documented deviations.
10. Cross-cutting reference values (service groups, units, change types, currency, seed permissions).

**Connection guarantee:** every artifact uses registry names verbatim; every critic pass includes a "registry conformance" check; every registry change triggers a scan of finished artifacts (list them in the change note).

## 6. Roadmap — ordered artifact list

Order rationale: architecture locks the skeleton → DB schemas define what exists → sequences stress-test that the schemas and events actually support the flows → UI displays what already exists. If a later phase reveals a gap, fix registry + earlier artifact with a change note (don't silently drift).

### Phase 1 — Architecture baseline
| Step | Artifact | Content & done-criteria |
|---|---|---|
| 1.1 | `design/00-registry.md` | Seed all 9 registry sections from §4–§5 of this plan. Done when every enum/event/dependency in this plan appears exactly once. |
| 1.2 | `design/01-architecture.md` + `diagrams/architecture.drawio` | Container diagram: gateway, 8 services + their DBs/schemas, Redis, RabbitMQ, esign-mock, frontend; sync arrows (gRPC, labeled with purpose) vs async arrows (labeled with event types); mark the two REST exceptions (D16). Written justification for: DB-per-schema (D12), customer-contract merge, centralized audit (D15), gRPC-internal/REST-public (D16), broker choice (D2). **Also decide attachment/file storage here** (4.2 tệp đính kèm, CTR-02, e-sign payload rendering) — default recommendation: attachment metadata table in contract-service + files on a mounted volume; no object-store service at student scope. Done when every D# decision is visible or cited. |

### Phase 2 — Database schemas (one drawio ER per service + notes md)
Files: `design/db/db-<service>.drawio` + `design/db/db-<service>.md` (choices, rule mapping). Common research for all: re-read the requirement section + rule table for that domain; **study the relevant Figma screens** (they carry field-level expectations — list columns, detail panels, metadata cards); check registry conventions (§5.6); confirm every cross-service reference is an opaque UUID (+snapshot per D7).

| Step | Service | Specific design attention (research targets) |
|---|---|---|
| 2.1 | identity | Users, departments, roles, user_roles, permissions kept minimal (role-based, no ACL matrix — anti-over-eng). Refresh/blacklist strategy note (Redis, not a table). |
| 2.2 | contract | Contract, addendum (own workflow + effective_date, CTR-07), attachment, customer + status field per state machine; edit-allowed states (CTR-01) as a check constraint or app rule — decide & note. Customer suspension (4.1). Outbox (audit emission, D15). |
| 2.3 | pricing | service_item catalog; price_list (scope: customer/contract/service-group — PRC-01) + price_list_version (valid_from/to, status, `addendum_id` nullable — set only when the version was manually created from an approved addendum per D8) + price_line. **Overlap exclusion constraint** (PRC-03) — research Postgres `EXCLUDE USING gist` with daterange. Supersede mechanics (PRC-04). |
| 2.4 | operations | operation_period (period_code, status OPEN/LOCKED, locked_by/at) + volume_record (period, contract_id, customer_id, service_code, quantity, unit). Post-lock edits need special permission (4.5) — model as permission, not schema. |
| 2.5 | workflow | document_type config, workflow_definition + definition_version + step_definition (order, approver role), workflow_instance (unique active per document — D4), step_instance (assignee snapshot per APR-01, version column per D5), step_action (comment mandatory on reject/revision per APR-03). Outbox table. This is the most design-heavy schema — budget a second critic round. |
| 2.6 | billing | payment_statement (period, contract_id, **customer_id + customer name snapshot** per D7 — needed for the 4.1 per-customer lookup, status, totals, `adjusts_statement_id` self-ref for PAY-05) + statement_line (service_code + **snapshotted** name/unit/unit_price + quantity + amount + tax — PAY-03). Validation notes: PAY-01/02/04 as service-level checks. |
| 2.7 | esign + notification (small, one step) | signing_session (document ref, status per 4.8 states, provider_ref, retry count per APR-07), callback_log; notification (recipient, type, document ref, read_at), processed_event. Outbox for esign. |
| 2.8 | audit | Single `audit_record` table (D15): the registry §6 audit payload + `source_service`, `occurred_at`, `received_at`. PK = source event id (dedup without `processed_event`). Index the document-history path `(entity_type, entity_id, occurred_at DESC)` and the global-search paths (actor, date, service). No local audit table anywhere else. |

### Phase 3 — Sequence diagrams (mermaid in md)
Files: `design/sequences/seq-<name>.md`. Format decision: mermaid (the requirement doc itself uses mermaid; text = fast critic iteration). Rules in §8.2.

| Step | Diagram | Covers |
|---|---|---|
| 3.1 | `seq-outbox-notification` | The mechanics drawn ONCE: business tx + outbox row → publisher → RabbitMQ → consumer dedup → notification stored → user reads/marks read. Later diagrams *reference* this instead of redrawing (anti-repetition). |
| 3.2 | `seq-auth` | Login → JWT issue; an authenticated request through gateway (blacklist + rate limit touchpoints). Short. |
| 3.3 | `seq-contract-approval` | The flagship approval flow: create → submit (idempotent start, D4) → step assignment (identity lookup, snapshot) → Legal approves (row-lock note, D5) → Board approves → `workflow.completed` → contract-service flips status → Active flipped later by the D14(d) scheduler at effective date (CTR-05). `alt` fragments: reject, request-revision → back to Draft (CTR-04, APR-03/04). |
| 3.4 | `seq-pricelist-version` | New version — both entry points: standalone and **created from an approved addendum (D8)** → overlap check (PRC-03) → approval (compressed: "per seq-contract-approval") → Effective + old version Superseded (PRC-04). Include `document.expiring` timer note (D9). |
| 3.5 | `seq-volume-period` | Record volumes → adjust → lock period → `alt`: post-lock edit rejected for normal users / allowed with special permission + audit entry (4.5). Short. |
| 3.6 | `seq-payment-statement-esign` | The integration flagship: build statement (3 sync pulls + price snapshot) → calculate → reconcile → submit/approve (compressed) → esign-service session → mock provider → **async callback** → Signed → Issued. `alt`: sign failure/cancel → Revision (PAY-07), provider down → retry via outbox (APR-07). |

### Phase 4 — UI annotation & gap-check (against Figma)
The Figma screens in `docs/figma/` **are** the wireframes — do not redraw them. Files become `design/ui/ui-<actor>.md`: for each Figma screen the actor uses, an annotation block (entry point, backing endpoint(s) from the registry, states rendered, actions → target service, business rules enforced visually — e.g. edit button only in Draft/RevisionRequested per CTR-01, mandatory comment modal per APR-03) plus a **gap report**: requirement-demanded elements missing from the screen, and Figma elements that are out of scope (each with a keep/defer/drop verdict). Only if a required screen has no Figma design at all is a low-fi drawio added.

| Step | File | Screens |
|---|---|---|
| 4.1 | `ui-shared` | Login; app shell (role menu, notification bell); **task inbox** ("hồ sơ cần xử lý" — the central approver screen); notification list; document history tab (audit per entity, 4.10 — backed by audit-service, so specify the empty/stale/unavailable states per D15); workflow progress stepper (done/current/remaining steps + current holder, 4.7). |
| 4.2 | `ui-sales` | Customer list/detail (tabs: contracts, price lists, statements — 4.1 lookup); contract editor/detail (status timeline, addendum tab, renew/cancel per 4.2); addendum editor; price list version editor (Sales creates per use case e3); submit-for-approval dialog. |
| 4.3 | `ui-approver` (Legal + Board — same UX) | Approval inbox (filtered task inbox); document review screen (doc rendering + terms/files) with Approve / Reject / Request-revision + comment modal; workflow progress. |
| 4.4 | `ui-operations` | Volume entry grid (period, service, quantity); period list + lock action (confirmation shows consequences). |
| 4.5 | `ui-accounting` | Statement list; statement builder (pick contract+period → auto lines from pulls, show price-version used); reconcile view (controlled edit per 4.6); statement detail with **"Gửi ký điện tử" send action on Approved statements (D10)** + signing status tracker (4.8 states); adjustment creation from an Issued statement and controlled cancel (PAY-05, D14c). *Explicit exclusion:* no receivables/công nợ module — no functional requirement in §4 backs it; tracking is served by statement status. Note this in the md. |
| 4.6 | `ui-admin` | User management; role assignment; **workflow config builder** (per document type: ordered steps, role per step — the "no hard-code" proof, 4.7); document type config; global audit search (use case e25). |

### Phase 5 — Final connect-check
| 5.1 | `design/99-traceability.md` | Matrix: every requirement section (4.1–4.10) and every coded rule (CTR/PRC/PAY/APR + 5.5 rows) → artifact(s) covering it. Any uncovered line = a gap to fix before design is "done". One dedicated critic pass across ALL artifacts for cross-references (IDs, enums, events, endpoints). |

## 7. Working process & critic protocol

**Per-artifact loop:**
1. **Research:** re-read the mapped requirement lines + rule tables; read registry; read adjacent finished artifacts (the ones this must connect to — listed per step in §6). Web search only for concrete technical patterns when unsure (e.g. Postgres exclusion constraints, webhook signature conventions) — not for inventing features.
2. **Draft** the artifact **together with its registry delta** (new names/enums/events it introduces, as a proposed edit to `00-registry.md`) — the critic reviews both, so legitimately-new names aren't flagged as registry mismatches.
3. **Critic subagent** (general-purpose agent, fresh context). Prompt template:
   > Read `docs/requirement.md` (sections X, rules Y), `docs/design-plan.md` §4–5, `docs/design/00-registry.md`, and the artifact `<path>`. Also read `<adjacent artifacts>`. Critique it on exactly: (1) requirement coverage gaps; (2) business-rule violations; (3) inconsistency with the registry or adjacent artifacts (names, enums, events, IDs); (4) over-engineering — anything not traceable to a requirement or a D# decision; (5) under-specification that would block the next artifact. Verdict: GOOD or NEEDS-CHANGES with a prioritized list. Do not nitpick style; if it is genuinely good, say so plainly. Do not invent new requirements.
4. **Revise.** Max 2 critic rounds; remaining minor nits → note in the artifact's md and move on. Blocking disagreement → surface to the human.
5. **Apply the registry delta** + list affected finished artifacts in the change note.
6. **Commit** (one artifact per commit, message `design: <artifact> (<step #>)`).

**Critic calibration (per user directive):** criticize honestly but don't be stubborn; a good artifact gets a plain "GOOD"; nitpicking is a failure mode of the critic, not diligence.

## 8. Component design guidelines

### 8.1 Database schemas
- Registry conventions: `id UUID PK`, `created_at/created_by`, `updated_at/updated_by`; `version INT` only where a race is real (step_instance, contract edit).
- Status columns: TEXT with CHECK against the registry enum — exact state-machine names.
- No cross-service FK. Opaque UUID + snapshot fields per D7.
- **No `audit_log` table anywhere** (D15). Auditable actions emit `audit.recorded` via the service's `outbox`, written in-transaction; audit-service holds the trail. Payload shape in registry §6.
- Outbox table (registry shape) in every service that emits any event — i.e. all but notification, since audit alone requires one.
- Each `db-*.md` ends with a rule-mapping table ("PRC-03 → exclusion constraint on price_list_version…") and a "Constraints & indexes" section.
- ER drawio format (standard draw.io ER table shape, `shape=table`/`tableRow`): one column per row — key markers (`PK`, `FK1`, `PK,FK1`, …) in the narrow left cell, `name type` in the right cell, PK rows bold+underlined. **No notes, constraints, or annotations inside tables** — uniqueness, CHECKs, defaults, nullability and snapshot semantics live only in the md. In-schema FKs = crow's-foot edges from the FK row to the parent table; cross-service references = plain `uuid`/`text` columns with a dashed edge to a grey ghost table and **no FK marker** (no real constraint exists across schemas, D7/D12). Diagrams are generated by `scripts/gen_er_drawio.py` — edit specs there and regenerate.

### 8.2 Sequence diagrams
- Mermaid `sequenceDiagram` inside md, one flow per file, with a header: actors involved, requirement lines covered, registry endpoints/events used.
- Lifelines: actor, frontend (collapsed), gateway (shown once then implicit), involved services, broker, DB only where transactionality matters (outbox step).
- `alt`/`opt` fragments for failure/rejection paths — no separate happy/sad twin diagrams.
- Annotate at the exact step: idempotency (D4), locking (D5), outbox (D6), snapshot (D7). Reference `seq-outbox-notification` instead of redrawing broker mechanics.
- Every sync arrow = registry endpoint; every async arrow = registry event type. Nothing ad hoc.

### 8.3 UI annotation (Figma-based)
- The Figma screens are authoritative for layout/visuals; annotation docs map them to the design (endpoints, states, rules) — never redraw what Figma already shows.
- Status badge labels on screen are **display labels**; the registry keeps the label ↔ enum mapping (e.g. "Under Review" badge = `SUBMITTED` for price lists/statements).
- Each screen's annotation block: actor, route, backing endpoints, states rendered, actions → service, business rules enforced visually, discrepancies vs requirement (requirement wins).
- Shared components (registry §5.8) identified once and referenced per screen.

## 9. Out of scope for the design phase
Implementation planning (repo layout, Docker Compose, K8s manifests, CI) comes after design is accepted. The architecture doc lists the infra components so nothing in the design contradicts req §6, but writing those files is a separate phase.
