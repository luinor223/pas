# db-notification — notes (step 2.7b)

Schema `notification`, owned by notification-service — pure event consumer (4.9). Diagram: [db-notification.drawio](db-notification.drawio).

## Key decisions
- **One table + dedup**: `notification` (per recipient) + `processed_event` (idempotent consumption, D6). Nothing else — notifications are not business documents, so no audit_log, no outbox, no versioning.
- **Recipient resolution** per event type: assignee/requester user ids come straight from event payloads (`assignee_ids`, `requested_by`, `owner_user_id`); role-addressed events (`operations.period_locked` → ACCOUNTANTs) resolve via identity `IdentityInternal.ListUsersByRole` (gRPC, §5 matrix; `status='ACTIVE'` users only, so disabled accounts are never sent a notification). One event → N notification rows.
- **A recipient field the producer got wrong is not "nobody"**: an absent or non-uuid `assignee_ids` / `requested_by` / `owner_user_id` / `recipient_role` raises `MalformedEventException` and the record goes to `pas.events.DLT`. Resolving it to an empty list instead would mark the event processed and destroy the only copy — no retry, no dead letter, no trace. Zero recipients is reserved for the two cases that genuinely are an answer: an empty `assignee_ids` array, and a `recipient_role` that identity reports nobody holds.
- **`category`** (`APPROVAL | ESIGN | EXPIRY | SYSTEM`) drives the Figma tab filters; `event_id` traces each row to its source event.
- **Read state**: `read_at` timestamp (null = unread); "Mark all as read" = bulk UPDATE. Unread badge = `COUNT WHERE read_at IS NULL`. Both mark-read paths are conditional updates guarded on `read_at IS NULL` rather than read-modify-write, so two concurrent requests cannot both win and overwrite the moment the row was *first* read.
- **Figma "Preferences" button is out of scope**: per-user notification preferences have no requirement backing (4.9 lists fixed triggers) — recorded as a deliberate drop; revisit only if the team wants it.
- Body text is composed at write time from the event (title/body snapshots) — a notification must stay readable even if the source document is later renamed/cancelled (4.10 spirit).

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| 4.9 triggers (cần xử lý / từ chối / được duyệt / ký xong / sắp hết hạn) | consumed event set (registry §4) |
| 4.9 async from other services | Kafka consumer (`pas.events`, group `notification-service`); no sync coupling to producers |
| 4.9 view + mark read | list endpoint + `read_at` |
| APR-07 notification failure ≠ business failure | outbox at producers + redelivery + `processed_event` dedup |

## Constraints & indexes (not shown in the diagram)
- INDEX `(recipient_user_id, read_at)` — inbox list + unread count.
- `category` CHECK vs registry §8 values; `title`/`body` are write-time snapshots.
