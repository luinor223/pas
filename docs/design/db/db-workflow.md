# db-workflow — notes (step 2.5)

Schema `workflow`, owned by workflow-service — the configurable approval engine (4.7). Diagram: [db-workflow.drawio](db-workflow.drawio).

## Key decisions
- **Definition / instance split with pinned versions**: `workflow_definition.version_no` + partial unique `(document_type_id) WHERE is_active` (one active per doc type). Instances pin `definition_id`, so an admin edit mid-flight never mutates a running approval. Admin "edit" = insert new definition version + flip `is_active`, gated by permission `workflow.configure` (checked against the caller's JWT `permissions[]` claim, registry §6 — not a schema concern here).
- **Not a BPMN engine**: ordered sequential steps only. **Parallel approval is out of scope** — no requirement backs it and Figma shows only "Parallel: No"; noted so nobody adds a column speculatively.
- **Conditional steps** (Figma "Condition: value > 1B"): `condition_expr` = a single comparison against instance snapshot fields (`document_value > 1000000000`), evaluated **once at instance creation**; unmet ⇒ step created as `SKIPPED`. Unevaluable (NULL operand, e.g. a value condition on a document without a value) ⇒ **not** skipped — conservative default. This keeps APR-02 intact (humans can never skip; config can). Assignee resolution (below) happens in the same pass, for every step that isn't `SKIPPED`.
- **D4 double-submit**: partial unique index `(document_type_code, document_id) WHERE status='IN_PROGRESS'` — the second concurrent `WorkflowInternal.StartInstance` fails on the constraint and returns the existing instance (idempotent, keyed by `idempotency_key`).
- **D5 approve race**: `workflow_step_instance.version` optimistic lock — the action UPDATE carries `WHERE id=? AND version=? AND status='ACTIVE'`; the concurrent loser gets 0 rows and an `ABORTED` (REST callers see 409), and the `status='ACTIVE'` predicate (not the version alone) is what rejects actions on completed or pending steps (APR-02).
- **Cancellation**: `WorkflowInternal.CancelInstance` (registry §5), called by the owner service inside its document-cancel flow (retried on `UNAVAILABLE`, APR-07 spirit). Succeeds only while no step has been actioned (`FAILED_PRECONDITION` otherwise); sets instance → CANCELLED, the ACTIVE step → CANCELLED via the same version-guarded UPDATE (closing the approve-vs-cancel race), and all PENDING steps → CANCELLED. The same PENDING→CANCELLED sweep runs when an instance terminates early (REJECTED / REVISION_REQUESTED). No `instance_cancelled` event — inbox queries reflect the state immediately.
- **Resolution timing, made explicit**: `step_assignee` for **every** non-SKIPPED step — not just the first — is populated in the same instance-creation transaction as the condition-expr pass, by calling identity once per distinct role. This is deliberate, not lazy per-step resolution at activation: resolving the whole chain upfront is what lets a missing assignee on step 3 fail the submission immediately, instead of only surfacing after steps 1–2 are already approved.
- **Empty assignee resolution**: if any step's role resolves to zero users at that instance-creation pass, the submit fails with a clear error (admin must fix role assignments) — no instance is created, nothing strands. Identity's `IdentityInternal.ListUsersByRole` returns `status='ACTIVE'` users only (registry §5), so a role whose every holder is `DISABLED` resolves to zero and hits this same safeguard, rather than silently assigning a step to someone who can never act on it.
- **APR-01 contextual auth**: `step_assignee` rows are the **snapshot of who may act**, resolved from identity (`IdentityInternal.ListUsersByRole`) at instance creation (see above — not at step activation, which only flips `workflow_step_instance.status` to `ACTIVE` for whichever step is next). At instance creation, all `ACTIVE` users holding the configured step role are snapshotted into `step_assignee`. Only users in this snapshot may act; possessing the role later is insufficient. Disabled users and users granted the role after instance creation cannot act. This gives APR-01 meaningful contextual enforcement:
  - Role holder but not in snapshot → denied
  - Snapshotted assignee on an inactive/completed step → denied
  - Snapshotted assignee on the current ACTIVE step → allowed

  No per-step user override (`approver_user_id` was considered and cut — registry §7 chains are role-only, nothing backs it). Inbox tabs: **Assigned to me** = ACTIVE steps where caller ∈ `step_assignee`; **Submitted by me** = instances where `requested_by` = caller; **Completed** = caller's past `workflow_action` rows. No **Team queue** tab (dropped — see Figma discrepancies below); one consequence stated plainly: a user granted the role *after* an instance's step already activated has no way to see or act on that step. Accepted — nothing requires live reassignment, and building visibility for it would need the same claim/reassign machinery the drop is avoiding.
- **APR-01 scope limitation, documented honestly**: per-manager document ownership is **not modeled**, because the requirements do not define document-to-manager assignment — no account/document-manager ownership concept, no assignee-selection algorithm, no UI for choosing a manager, and no per-step assignment strategy exist anywhere in requirement.md, UC v2, Figma, or the registry. Therefore, when a step's role resolves to more than one active user (e.g. two Legal Reviewers), **all of them** are valid assignees — the first valid action completes the step; concurrent actions are protected by optimistic locking (D5), not by narrowing to one specific person. This is a **documented interpretation** of the ambiguous contextual-authorization line in req §5.5 ("Manager A không được duyệt hồ sơ của Manager B nếu không phải assignee của bước hiện tại") — not a claim that req §5's general "nhóm được phép điều chỉnh" permission covers it (that sentence is specifically about adjusting the provided state-machine diagrams, D14, not about interpreting ambiguous authorization rules). Adding real per-manager ownership (e.g. a `customer.account_manager_id` restricting only the `SALES_MANAGER` steps to one specific user) would introduce a new business concept across schema, API, Figma, workflow resolution, and tests — a scoped later extension if the instructor confirms "Manager B's hồ sơ" means explicit account ownership, not something to introduce speculatively now.
- **APR-03**: `workflow_action.comment` + `CHECK (action = 'APPROVE' OR (comment IS NOT NULL AND comment <> ''))`.
- **Instance snapshots** (`document_no`, `customer_name`, `document_value`, `priority`): make the Approvals Inbox renderable without querying owner services and feed `condition_expr` + event payloads (D7). `priority` from Figma (LOW/NORMAL/HIGH/URGENT).
- **E-sign is not a step** (D10 / 5.5): the Figma builder's "E-signature" card maps to `document_type_config.esign_enabled` + provider config; the engine ends at final approval (APR-05).
- **SLA**: `sla_hours` on step definition (snapshot onto step instance); scheduler emits `workflow.step_overdue` once per step (`overdue_notified_at` stamp prevents re-fire spam). Feeds Figma "Approval overdue" notification + dashboard "4 overdue".
- **Outbox (D6)**: all `workflow.*` lifecycle events are written to `outbox` in the same transaction as the state change.
- `workflow_action` doubles as the approval-history display (Figma "Approval History" tab) — it is **domain data**, not audit: it is the record of who holds/held the document (4.7 "hiển thị người đang xử lý"), served synchronously and never eventually consistent. Audit of config changes (definitions, steps) and of approval actions goes out as `audit.recorded` on the same `outbox` (D15).

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
- Adopted: step names, SLA, condition, priority, inbox tabs (Assigned to me / Submitted by me / Completed — queries over these tables).
- Discrepancy: drag-reorder = `step_order` updates on a **new** definition version.
- Dropped: Figma's **Team queue** tab. Not in requirement.md or UC v2, not needed to satisfy APR-01 (which requires excluding non-assignees, not adding a broader role-pool view), and it would have surfaced tasks the viewer isn't actually the assignee of — no claim/reassign or view-only logic added to compensate.

## Constraints & indexes (not shown in the diagram)
- Partial UNIQUE: `workflow_definition (document_type_id) WHERE is_active`; `workflow_instance (document_type_code, document_id) WHERE status = 'IN_PROGRESS'` (D4).
- UNIQUE: `workflow_definition (document_type_id, version_no)`, `workflow_step_definition (definition_id, step_order)`, `workflow_step_instance (instance_id, step_order)`, `document_type_config.code`.
- CHECK: `workflow_action.action = 'APPROVE' OR (comment IS NOT NULL AND comment <> '')` (APR-03); statuses/priority vs registry §3.
- Optimistic locking: `workflow_step_instance.version` (D5).
- Snapshots (D7): instance `document_no`, `customer_name`, `document_value`, `requested_by_name`; step `name`, `approver_role`, `sla_hours`; `step_assignee.user_name`.
