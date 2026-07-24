# db-workflow — notes (step 2.5)

Schema `workflow`, owned by workflow-service — the configurable approval engine (4.7). Diagram: [db-workflow.drawio](db-workflow.drawio).

## Key decisions
- **Definition / instance split with pinned versions**: `workflow_definition.version_no` + partial unique `(document_type_id) WHERE is_active` (one active per doc type). Instances pin `definition_id`, so an admin edit mid-flight never mutates a running approval. Admin "edit" = insert new definition version + flip `is_active`.
- **Not a BPMN engine**: ordered sequential steps only. **Parallel approval is out of scope** — no requirement backs it and Figma shows only "Parallel: No"; noted so nobody adds a column speculatively.
- **Conditional steps** (Figma "Condition: value > 1B"): `condition_expr` = a single comparison against instance snapshot fields (`document_value > 1000000000`), evaluated **once at instance creation**; unmet ⇒ step created as `SKIPPED`. Unevaluable (NULL operand, e.g. a value condition on a document without a value) ⇒ **not** skipped — conservative default. This keeps APR-02 intact (humans can never skip; config can).
- **D4 double-submit**: partial unique index `(document_type_code, document_id) WHERE status='IN_PROGRESS'` — the second concurrent `POST /internal/workflow-instances` fails on the constraint and returns the existing instance (idempotent).
- **D5 approve race**: `workflow_step_instance.version` optimistic lock — the action UPDATE carries `WHERE id=? AND version=? AND status='ACTIVE'`; the concurrent loser gets 0 rows and a 409, and the `status='ACTIVE'` predicate (not the version alone) is what rejects actions on completed or pending steps (APR-02).
- **Cancellation**: `POST /internal/workflow-instances/{id}/cancel` (registry §5), called by the owner service inside its document-cancel flow (retried on failure, APR-07 spirit). Succeeds only while no step has been actioned (409 otherwise); sets instance → CANCELLED, the ACTIVE step → CANCELLED via the same version-guarded UPDATE (closing the approve-vs-cancel race), and all PENDING steps → CANCELLED. The same PENDING→CANCELLED sweep runs when an instance terminates early (REJECTED / REVISION_REQUESTED). No `instance_cancelled` event — inbox queries reflect the state immediately.
- **Empty assignee resolution**: if a step's role resolves to zero users at instance creation, the submit fails with a clear error (admin must fix role assignments) — no instance is created, nothing strands.
- **APR-01 contextual auth**: `step_assignee` rows are the **snapshot of who may act**, resolved from identity (`GET /internal/users?role=`) at step activation. Authorization = caller ∈ assignees of the ACTIVE step — role alone is insufficient by design ("Manager A can't approve Manager B's step"). No per-step user override (`approver_user_id` was considered and cut — registry §7 chains are role-only, nothing backs it). Inbox tabs: **Assigned to me** = ACTIVE steps where caller ∈ `step_assignee`; **Team queue** = ACTIVE steps whose `approver_role` ∈ caller's roles (catches users granted the role after activation); **Submitted by me** = instances where `requested_by` = caller; **Completed** = caller's past `workflow_action` rows.
- **APR-03**: `workflow_action.comment` + `CHECK (action = 'APPROVE' OR (comment IS NOT NULL AND comment <> ''))`.
- **Instance snapshots** (`document_no`, `customer_name`, `document_value`, `priority`): make the Approvals Inbox renderable without querying owner services and feed `condition_expr` + event payloads (D7). `priority` from Figma (LOW/NORMAL/HIGH/URGENT).
- **E-sign is not a step** (D10 / 5.5): the Figma builder's "E-signature" card maps to `document_type_config.esign_enabled` + provider config; the engine ends at final approval (APR-05).
- **SLA**: `sla_hours` on step definition (snapshot onto step instance); scheduler emits `workflow.step_overdue` once per step (`overdue_notified_at` stamp prevents re-fire spam). Feeds Figma "Approval overdue" notification + dashboard "4 overdue".
- **Outbox (D6)**: all `workflow.*` lifecycle events are written to `outbox` in the same transaction as the state change.
- `workflow_action` doubles as the approval-history display (Figma "Approval History" tab); `audit_log` here covers **config** changes (definitions, steps).

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| 4.7 configurable, no hard-code | definitions + steps as data; seed chains registry §7 |
| 4.7 progress display | instance `current_step_order` + step statuses (sync GET, §5) |
| APR-01 | `step_assignee` snapshot + membership check |
| APR-02 | sequential activation + optimistic lock; SKIPPED only via config condition |
| APR-03 | comment CHECK |
| APR-04 | terminal outcome per registry §9 (REJECTED or REVISION_REQUESTED) |
| APR-05 | final approve → instance APPROVED → `workflow.completed` outbox event |
| 5.5 double submit / race | D4 partial unique / D5 version column |

## Figma adoptions / discrepancies
- Adopted: step names, SLA, condition, priority, inbox tabs (Assigned to me / Submitted by me / Team queue / Completed — all queries over these tables).
- Discrepancies: "E-signature" as builder step (mapped to config, above); drag-reorder = `step_order` updates on a **new** definition version.

## Constraints & indexes (not shown in the diagram)
- Partial UNIQUE: `workflow_definition (document_type_id) WHERE is_active`; `workflow_instance (document_type_code, document_id) WHERE status = 'IN_PROGRESS'` (D4).
- UNIQUE: `workflow_definition (document_type_id, version_no)`, `workflow_step_definition (definition_id, step_order)`, `workflow_step_instance (instance_id, step_order)`, `document_type_config.code`.
- CHECK: `workflow_action.action = 'APPROVE' OR (comment IS NOT NULL AND comment <> '')` (APR-03); statuses/priority vs registry §3.
- Optimistic locking: `workflow_step_instance.version` (D5).
- Snapshots (D7): instance `document_no`, `customer_name`, `document_value`, `requested_by_name`; step `name`, `approver_role`, `sla_hours`; `step_assignee.user_name`.
