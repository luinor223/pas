# db-operations — notes (step 2.4)

Schema `operations`, owned by operations-service. Diagram: [db-operations.drawio](db-operations.drawio).

## Key decisions
- **Global monthly period** (`period_code 'YYYY-MM'`), one lock for all customers/contracts (plan 2.4 default). Simplest model satisfying 4.5; per-customer locking has no requirement backing.
- **Lock = confirmation (PAY-02)**: a volume record has **no per-record status** — the period's `LOCKED` state is the "đã xác nhận/đối soát" signal billing checks. One mechanism instead of two.
- **No unlock transition** (registry §9). Post-lock edits: permission `volume:edit_locked` + mandatory audit entry (4.5 "quyền đặc biệt"). Deliberately not schema-enforced — the service checks period status + the caller's resolved permissions (registry §6).
- **`contract_id` required** on `volume_record` even though the Figma list omits the column: statements are per contract (PAY-01), so volume→contract mapping must be unambiguous at entry time, not inferred later. `customer_name`/`service_name`/`unit` snapshots make the Figma list renderable without cross-service joins (D7).
- **Events**: emits `operations.period_locked` (direct publish — informational "statements can now be generated" notification, Figma; a lost one costs nothing). Consumes nothing → no processed_event.
- **`outbox` for audit (D15)**: volume create/adjust, period lock, and especially post-lock edits under `volume:edit_locked` are written as `audit.recorded` to `outbox` in the same transaction. 4.5's "quyền đặc biệt" escape hatch is only acceptable *because* every use of it is traced — so this write is not optional for that path.

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| 4.5 record volumes (containers/tons/trips/storage days) | `volume_record.quantity` + `service_code/unit` (catalog units §10) |
| 4.5 adjust before lock | UPDATE allowed while period OPEN |
| 4.5 locked ⇒ no edit without special permission | period status + `volume:edit_locked` + audit |
| PAY-02 (billing side) | billing sync-reads only LOCKED periods (§5 matrix) |

## Figma adoptions / discrepancies
- Adopted: `record_no` (VOL-YYYY-seq), recorded-by display (= `created_by`), period filter, Open/Locked badges, unit/service examples.
- Discrepancy: Figma list has no contract column — see decision above (detail view will show it).

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `operation_period.period_code`, `volume_record.record_no`.
- CHECK: `volume_record.quantity >= 0`; `operation_period.status IN ('OPEN','LOCKED')`.
- Snapshots (D7): `customer_name`, `service_name`, `unit` — render lists without cross-service joins.
