# db-billing — notes (step 2.6)

Schema `billing`, owned by billing-service — the integration hub. Diagram: [db-billing.drawio](db-billing.drawio).

## Key decisions
- **Everything price/party/period is a snapshot** (PAY-03, D7): `unit_price`, `service_name`, `unit`, `vat_rate`, `payment_term`, `contract_no`, `customer_name`, `period_start/end`, `price_list_no + version_no`. A statement must render identically forever, regardless of later catalog/price/customer changes. The uuid refs (`contract_id`, `price_list_version_id`, …) remain for navigation/traceability only.
- **Calculation flow** (statement build): sync pulls per §5 matrix — contract (validity, vat_rate, payment_term — always the *effective* values, addendum effects already applied per registry §9² → PAY-01), pricing effective version resolved with `contract_id + customer_id + service_group` at `date = period_end` (precedence CONTRACT > CUSTOMER+GROUP > CUSTOMER; PAY-01, snapshot source), operations volumes of a **LOCKED** period (PAY-02). The Figma "Reconciliation check" panel is exactly these checks re-evaluated for display.
- **PAY-04 "required lines", concretely**: at submit — ≥1 line; every locked volume record of (contract, period) is mapped via `statement_line_volume`; every volume `service_code` is priced in the resolved version (an unpriced service is the natural PAY-01 "no suitable price list" failure and blocks the build with a clear error); `total_amount >= 0`.
- **VAT at statement level** (Figma: single "VAT (8%)" row): `vat_rate` + `tax_amount` on the statement; lines carry pre-tax `amount` only. Per-line tax has no requirement or design backing.
- **Line traceability**: `statement_line_volume` links lines to source volume records with quantity snapshots — backs the Figma "Source volumes" tab and PAY-02 reconciliation. One link table, no more.
- **`source: CALCULATED|MANUAL`** per line (4.6 "chỉnh sửa có kiểm soát"; Figma "Manual adjustments: None"): manual additions/edits are visible and audit-logged; `version` on the statement guards concurrent controlled edits.
- **Adjustments (PAY-05)**: a *new* statement with `adjusts_statement_id` → the original; original stays immutable after APPROVED/SIGNED. `CHECK total_amount >= 0` applies to adjustments too (PAY-04 wording is absolute); a correction that would net negative is modeled as cancel (D14c) + re-issue.
- **`due_date`** (Figma metadata card): computed at issue from `payment_term` (e.g. Net 30) and stored — display data, not a receivables module (plan 4.5 exclusion).
- **Status flips**: consumes `workflow.completed` + `esign.session_completed` (processed_event). Billing emits no *business* events — notifications about statements ride workflow/esign events (§4).
- **`status_history` (D17)**: append-only, per statement, written in the same transaction as each flip. The statement has the longest chain in the system (DRAFT → CALCULATED → RECONCILED → SUBMITTED → APPROVED → SIGNING → SIGNED → ISSUED, plus the REJECTED/REVISION rework loops of D14f), so "how many times did this go back for revision" is a real question — and it is answerable here rather than from audit-service, which a rule may not read.
- **`outbox` for audit (D15)**: calculate, controlled edit, reconcile, submit, publish, cancel and adjustment creation are written as `audit.recorded` to `outbox` in the same transaction. PAY-05 (no direct edit after Approved/Signed) is enforced by status checks, but *defended* by this trace.

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| PAY-01 | build-time checks vs contract validity + effective price version (sync pulls) |
| PAY-02 | volumes only from LOCKED period; `statement_line_volume` trace |
| PAY-03 | snapshot columns (above) |
| PAY-04 | `CHECK total_amount >= 0` + required-lines app check at submit |
| PAY-05 | immutability after APPROVED/SIGNED (app + §9); `adjusts_statement_id`; controlled cancel D14c, gated by `statement:cancel_approved` (registry §6/§10) |
| PAY-06 | send-e-sign transition only from APPROVED (§9) |
| PAY-07 | SIGNING → REVISION on failed/cancelled callback (§9) |
| 4.6 list of services, unit price, qty, amounts, tax, total | `statement_line` + statement totals |

## Figma adoptions / discrepancies
- Adopted: PMT numbering, period display, reconciliation panel semantics, metadata card (current step/assignee come from workflow sync query, not stored here), "Add line" ⇒ MANUAL source, Adjustments tab.
- Discrepancy: badge "Under Review" = enum `SUBMITTED` (registry §3).

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `payment_statement.statement_no`, `statement_line (statement_id, line_no)`.
- CHECK: `total_amount >= 0` (PAY-04); `statement_line.source IN ('CALCULATED','MANUAL')`; statuses vs registry §3.
- Self-FK: `adjusts_statement_id → payment_statement` (PAY-05 adjustments).
- Optimistic locking: `payment_statement.version` (controlled edits, 4.6).
- Snapshots (D7/PAY-03): `contract_no`, `customer_name`, `period_start/end`, `price_list_no` + `price_list_version_no`, `payment_term`, `vat_rate`; line `service_name`, `unit`, `unit_price`; `statement_line_volume.record_no`/`quantity`.
