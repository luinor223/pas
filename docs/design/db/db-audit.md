# db-audit — notes (step 2.8)

Schema `audit`, owned by audit-service — the **single, centralized** audit trail (4.10). Diagram: [db-audit.drawio](db-audit.drawio).

## What this service is

A **read model, not a bounded context**: no invariants, no domain logic, no state machine. It is the system of record for audit — per-service `audit_log` tables were removed (D15) — but **not** for status history, which stays local per D17.

Split of responsibilities on a document detail screen:

| Reads | From | Consistency |
|---|---|---|
| status timeline | owning service's `status_history` | local, synchronous |
| everything else (field edits, config changes, actions with no status change) | **audit-service** | eventually consistent, sub-second |
| anything a **business rule** evaluates | `status_history` only — never here | — |

Beyond 4.10's per-entity axes, this is what enables cross-entity search: "what did user X do last week", "every reject in Q3", "all actions by ACCOUNTING" (§2 admin oversight).

## Key decisions

- **Durability lives in the producers' outbox.** The record is written to `outbox` in the same transaction as the business change (D6). That is what makes 4.10's *"phải giữ được vết thay đổi quan trọng"* unconditional; a naive async write would drop the trail whenever a service died between commit and publish. If audit-service is down, outbox rows accumulate and drain on recovery.
- **`id` = outbox row id = envelope `event_id`**, so the PK gives idempotent consumption: `INSERT … ON CONFLICT DO NOTHING`. **No `processed_event`** here — the natural key already does the job.
- **`changes jsonb` is never interpreted.** Producers format the human-readable description in their own ubiquitous language; this service stores, indexes and returns it. Holding that line is what stops a central store becoming a god-service.
- **`actor_id` is never resolved against identity at read time** — `actor_name`/`actor_department` are write-time snapshots (4.10: *"không phụ thuộc dữ liệu hiển thị hiện tại"*). A renamed or disabled user must not change what a past record shows; the ghost edge records provenance only (D7).
- **Producers:** identity, contract, pricing, operations, billing, workflow, esign. notification-service produces nothing (no auditable domain actions). audit-service audits nothing of its own; reads gated by `audit:view_all`.

## Rule / requirement mapping

| Rule | Design element |
|---|---|
| 4.10 ai / khi nào / hành động / trước-sau / ghi chú | `actor_*`, `occurred_at`, `action`, `before_status`/`after_status`, `note` |
| 4.10 per hợp đồng / bảng giá / bảng thanh toán / phiên ký | `(entity_type, entity_id)` index |
| 4.10 không phụ thuộc dữ liệu hiển thị hiện tại | actor/entity_no snapshots; in-transaction write at the producer |
| §2 admin oversight | cross-entity/actor/date queries (`audit:view_all`) |
| 5.5 Event bị mất | outbox at every producer + relay retry + PK dedup here |

## Constraints & indexes (not shown in the diagram)

- PK `id`, doubling as the dedup key.
- INDEX `(entity_type, entity_id, occurred_at DESC)` — hot path, every History tab.
- INDEX `(occurred_at DESC)`, `(actor_id, occurred_at DESC)`, `(source_service, occurred_at DESC)`, `(action)`.
- `source_service` CHECK against §1 service names; `entity_type` free text (each context's own vocabulary — not centrally enumerable).
- Rows immutable: no `updated_at`/`version`. **INSERT + SELECT grants only** — tamper-evidence, and the real gain of centralizing: a business service can no longer rewrite its own history.
