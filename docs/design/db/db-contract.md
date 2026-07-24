# db-contract — notes (step 2.2)

Schema `contract`, owned by contract-service (customers + contracts + addenda merged — same owner dept, strong data affinity; plan §3). Diagram: [db-contract.drawio](db-contract.drawio).

## Key decisions
- **Customer status is `ACTIVE|SUSPENDED` only** (4.1 "tạm ngưng"), displayed 1:1 via `Badge/Active`/`Badge/Suspended` (registry §3).
- **`customer_contact` child table**: Figma has a "Contacts" tab + primary-contact card; covers 4.1 "thông tin liên hệ" without stuffing columns into `customer`.
- **Contract commercial fields from Figma** (all displayed on Contract Detail): `service_group`, `currency`, `auto_renewal`, `billing_cycle`, `vat_rate` (feeds statement tax, PAY snapshot source), `penalty_terms`, `service_clause`. Each is one plain column — adopted per plan precedence rule.
- **Editing guard**: `version int` optimistic lock; CTR-01 (edit only in DRAFT / REVISION_REQUESTED) is an app-level state check against registry §9 — a DB CHECK can't see the transition.
- **Addendum**: own row + own workflow instance + same status enum. `change_type` (registry §10, from Figma). `TERM_EXTENSION` + `new_valid_to` **is** renewal (D14b) — no separate renewal mechanism; `auto_renewal` is display-only metadata (no scheduler behavior). Price changes carry **no price data here**: Sales creates a price list version from the approved addendum (D8).
- **Addendum effects land on the parent** (registry §9 footnote ²): when an addendum flips ACTIVE at `effective_from`, contract-service applies `new_valid_to` / `payment_term_override` to the contract row in the same transaction — a system action, audit-logged, not a CTR-07 violation (it applies an *approved* addendum). `GET /internal/contracts/{id}` therefore always returns effective values (what billing snapshots), and the D14d expiry scheduler runs on the extended `valid_to`. Addenda share the parent's expiry — no own end date.
- **Submit/cancel wiring** (registry §9 footnote ¹): submit sets SUBMITTED + starts the workflow instance (D4 idempotent); the flip to UNDER_REVIEW happens when consuming `workflow.instance_started`. Cancel while SUBMITTED calls the workflow cancel endpoint (succeeds only while no step is actioned), then sets CANCELLED.
- **Attachment**: polymorphic `owner_type + owner_id` (CONTRACT|ADDENDUM only — two values, one upload UI; a junction table per owner would be over-modeling). Files on a mounted volume; metadata here (plan 1.2 default). CTR-02's "≥1 attachment to submit" is an app check at submit time.
- **Status ownership (D3)**: `processed_event` + consumption of `workflow.completed` and `esign.session_completed` flip `status` per registry §9. Scheduler (D14d) flips APPROVED/SIGNED→ACTIVE→EXPIRED and emits `document.expiring` (direct publish, D9 — a lost warning re-fires next run).
- **No outbox**: the only emitted event type is `document.expiring` (D9 direct publish).

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| CTR-01 | app check vs status + `version` optimistic lock |
| CTR-02 | `CHECK valid_from <= valid_to`; customer validity + ≥1 attachment checked at submit |
| CTR-03 | no DRAFT→APPROVED transition in registry §9; flips only via `workflow.completed` |
| CTR-04 | REJECTED→DRAFT only via explicit `revise` action (audit-logged) |
| CTR-05 | scheduler flips APPROVED/SIGNED→ACTIVE at `valid_from` (D14d) |
| CTR-06 | no DELETE endpoint for ACTIVE; controlled cancel needs `contract.cancel_active` (D14a) |
| CTR-07 | terms changes on APPROVED/ACTIVE → `addendum` row, contract row untouched |
| 4.1 lookup by customer | `customer_id` FK here; price lists/statements queried cross-service by `customer_id` |
| 4.2 attachments, renewal, cancel | `attachment`, `addendum(TERM_EXTENSION)`, cancel transitions |
| 4.3 addendum takes effect from `effective_from` | new terms resolved via pricing versions (D8) / `payment_term_override` |

## Figma adoptions / discrepancies
- Adopted: `short_name`, `segment`, representative position, contacts tab, commercial term fields, owner display (= `created_by`), record-metadata card fields (created/updated/version).
- Discrepancy: "Outstanding balance / avg payment delay" cards are computed displays from billing data — **no receivables schema** (plan 4.5 exclusion).

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `customer.code`, `contract.contract_no`, `addendum.addendum_no`.
- CHECK: `contract.valid_from <= valid_to` (CTR-02); `contract.status` / `addendum.status` / `customer.status` vs registry §3 enums; `attachment.owner_type IN ('CONTRACT','ADDENDUM')`.
- `attachment` is polymorphic (`owner_type` + `owner_id`, no FK) — one upload UI, two owner kinds.
- Optimistic locking: `contract.version`, `addendum.version` (CTR-01 edit races).
- Nullable by design: `short_name`, `segment`, `representative_position`, `description`, `penalty_terms`, `service_clause`, `new_valid_to`, `payment_term_override`, `last_*` timestamps.
