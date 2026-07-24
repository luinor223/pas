# db-esign — notes (step 2.7a)

Schema `esign`, owned by esign-service — thin adapter to the external mock provider (4.8: focus is integration design, states, asynchrony — not legal validity). Diagram: [db-esign.drawio](db-esign.drawio).

## Key decisions
- **Generic session** over `(document_type_code, document_id)` for `CONTRACT | ADDENDUM | PAYMENT_STATEMENT` (D10; Figma shows all three). Snapshots (`document_no`, `customer_name`) keep the E-Signatures screen join-free.
- **Provider contract** (mock, but shaped like a real one): **HTTP, not gRPC, on purpose** (D16) — the provider is outside the system boundary and a real one is handed a callback URL, not a `.proto`. `POST` to provider with payload + callback URL → provider returns `provider_ref`; provider later `POST /callbacks/esign` with `{provider_ref, result}`. Inside the boundary the result becomes `esign.session_completed` on the broker (§4), so the webhook is only the ingress edge. `provider` column defaults `'MockSign'` — a second provider is config, not schema.
- **Callback safety (APR-06/07)**: `version` optimistic lock guards duplicate/late callbacks (only PENDING_SEND/SIGNING accept transitions); `signing_callback_log` stores every raw webhook (trace for 4.10 "phiên ký" + debugging). Unknown `provider_ref` → logged (`session_id` is nullable and the log carries its own `provider_ref` column), ignored.
- **One active session per document**: partial unique index `(document_type_code, document_id) WHERE status IN ('PENDING_SEND','SIGNING')` — a double-clicked "Send for Signature" can't create two provider sends with conflicting callbacks (mirrors D4).
- **Retry (APR-07)**: `attempts` counts sends; provider-down at send time leaves the session `PENDING_SEND` and a background retry re-sends — the owning document's data is never touched by a failing esign call. Retries exhaust at 3 attempts → session `FAILED` (registry §9), emitting `esign.session_completed{FAILED}` so the document status reflects it (PAY-07). `last_error` surfaces the reason (Figma: "signer email bounced").
- **Signer fields** (`signer_name`, `signer_email`) from Figma — the mock addresses a named customer signer.
- **Outbox (D6)** for `esign.session_completed` (payload carries `document_no`, `requested_by`, `signer_name` so notification can address and phrase without queries); consumed by billing-service (status flip per registry §9) and notification-service. **Not** consumed by contract-service (D14e removed — contract/addendum status never reflects signing progress; instead the frontend composes both for display, calling contract-service and esign-service independently through the gateway). A separate `session_started` event was dropped — no 4.9 trigger needs it and the requester triggered it themselves.
- **`status_history` (D17)**: append-only, per session. Distinct from `signing_callback_log`: the callback log is raw provider traffic (including callbacks for unknown `provider_ref`), the status history is *this system's* state machine (§3 SIGNING_SESSION). Both are needed — a provider can send three callbacks that produce one transition, or one callback the version guard rejects and that produces none.
- **Audit is centralized (D15)**: user-facing actions (create/cancel/resend) go out as `audit.recorded` on the same `outbox`, consumed by audit-service. The callback log stays here — it records raw *provider traffic*, not user actions, and is an operational/debugging artifact of this service, not part of the 4.10 trail.
- **New user-facing endpoint**: `GET /signing-sessions/by-document/{document_type}/{document_id}` (via gateway, not in the internal gRPC matrix §5 — that matrix is service-to-service only, per D16). Needed so the frontend can compose a document-detail view (e.g. Contract Detail) from contract-service's status plus this service's signing-session status, without contract-service ever depending on esign-service (D14e removal — see db-contract.md).

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| 4.8 states chờ gửi ký / đang ký / thành công / thất bại / hủy | `PENDING_SEND / SIGNING / SIGNED / FAILED / CANCELLED` (§3) |
| APR-06 async callback | webhook endpoint + callback log + version guard |
| APR-07 esign down ≠ broken business data | PENDING_SEND + retry; owner services unaffected |
| 5.5 separate signing state machine | session status ≠ document status; for contracts/addenda there is no coupling at all now (registry §9 footnote ³, D14e removed) — contract-service never consumes `esign.session_completed` and has no dependency on esign-service. The frontend composes both for display by calling contract-service and this service (`GET /signing-sessions/by-document/{document_type}/{document_id}`, user-facing, not in the internal gRPC matrix §5) independently through the gateway. Billing still consumes the event (statements keep their native Signing/Signed states). |
| 4.10 trace per "phiên ký" | session + callback_log + `audit.recorded` → audit-service |

## Figma adoptions
`session_no` (SIG-seq), provider column, attempts, signer columns, tab counts (Active/Completed/Failed/Cancelled = status queries).

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `signing_session.session_no`.
- Partial UNIQUE: `signing_session (document_type_code, document_id) WHERE status IN ('PENDING_SEND','SIGNING')` — one active session per document.
- `signing_callback_log.session_id` is nullable (unknown `provider_ref` callbacks are still logged); log carries its own `provider_ref`.
- Optimistic locking: `signing_session.version` (callback races); status CHECK vs registry §3.
- Snapshots (D7): `document_no`, `customer_name`, `requested_by_name`.
