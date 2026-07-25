#!/usr/bin/env python3
"""Generate draw.io UML sequence diagrams for the PAS service flows (design phase 3).

Usage:  python3 scripts/gen_seq_drawio.py
Writes: docs/design/sequences/<name>.drawio (one file per flow)

Shapes are draw.io's own UML sequence set: shape=umlLifeline (with
participant=umlActor|umlBoundary|umlControl|umlEntity), activation bars,
shape=umlFrame for alt/opt/loop fragments, shape=note for invariants.

Step vocabulary (see DIAGRAMS below):
  ("call",  src, dst, label)   solid filled arrow  — sync request (REST or gRPC)
  ("ret",   src, dst, label)   dashed open arrow   — response / return value
  ("async", src, dst, label)   solid open arrow    — broker publish / delivery
  ("self",  who,      label)   self-call loop      — local work, one transaction
  ("note",  who|None, text)    note in the right gutter, anchored at this y
  ("frame", label) … ("div", label) … ("end",)     alt/opt/loop fragment

Activation bars are derived: a "call" opens one on the destination, the matching
"ret" from that destination closes it. Unmatched calls are ignored (defensive).

Conventions in the labels themselves — kept verbatim from the registry so the
diagrams and the registry cannot drift:
  gRPC methods  <Service>Internal.<Method>   (registry §5, D16)
  REST paths    user traffic only            (registry §5 "REST, not gRPC")
  event types   registry §4                  (event_type header on topic pas.events)
  statuses      registry §3 / transitions §9
"""
import os
import xml.dom.minidom
from xml.sax.saxutils import escape

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "docs", "design", "sequences")

X0, Y0 = 60, 116           # first lifeline centre, lifeline header top
NAME_Y = Y0 - 30           # icon participants carry their name in a text cell here
                           # (NOT verticalLabelPosition=bottom — on a 1400px-tall
                           #  lifeline that puts the name at the foot of the page)
GAP = 215                  # spacing between lifeline centres
HDR_H = 40                 # lifeline header height
STEP = 46                  # vertical distance between messages
GUTTER = 360               # width of the right-hand note column
NOTE_W = 320

KIND_W = {"actor": 20, "boundary": 60, "control": 40, "entity": 50, "plain": 150}
KIND_PARTICIPANT = {
    "actor": "umlActor", "boundary": "umlBoundary",
    "control": "umlControl", "entity": "umlEntity", "plain": None,
}

# UML robustness stereotypes (Jacobson), as draw.io exposes them. Plain rectangles are
# reserved for our own nine services, so an icon always means "not a PAS service".
LEGEND = ("Lifeline shapes — stick figure = actor (human role) · circle with left bar = boundary "
          "(system edge: gateway, external provider) · circle with rim arrow = control (relay, scheduler) · "
          "circle on a line = entity (datastore or broker: Postgres schema, Redis, Kafka) · "
          "plain rectangle = one of the nine PAS services.     "
          "Arrows — solid filled = sync request (gRPC service-to-service per D16; REST for user or "
          "external traffic) · dashed open = response · purple open = broker publish/delivery · "
          "self-call = local work ('tx:' ⇒ commits atomically).")

ARROW = {
    "call":  "endArrow=block;endFill=1;",
    "ret":   "endArrow=open;endFill=0;dashed=1;endSize=8;",
    "async": "endArrow=open;endFill=0;endSize=10;strokeColor=#7B4FA8;",
}

# Message labels sit above their arrow and are wider than the gap between lifelines, so
# they must be opaque: a transparent label lands on top of a lifeline, a frame border or
# a neighbouring label and both become unreadable.
LABEL_BG = "labelBackgroundColor=#FFFFFF;"
LINE_H = 15          # per extra label line, used to reserve vertical clearance


# ─────────────────────────────────────────────────────────────── diagram specs

DIAGRAMS = {}

# ── 1. auth ──────────────────────────────────────────────────────────────────
DIAGRAMS["seq-01-auth"] = {
    "title": "1 · Login, authenticated request, permission resolution",
    "caption": "req §6 (JWT) · D11 two-layer authorization · mechanics.md §M1 (permission cache). "
               "Every other diagram assumes this gateway hop and omits it.",
    "participants": [
        ("user", ":Internal user", "actor"),
        ("admin", ":System admin", "actor"),
        ("gw", "api-gateway", "boundary"),
        ("idsvc", "identity-service", "plain"),
        ("redis", "Redis", "entity"),
        ("svc", "any business service", "plain"),
    ],
    "steps": [
        ("frame", "loop [identity startup, then every 1h]"),
        ("call", "idsvc", "redis", "SET perm:role:{code} = [permissions]\nfor every role · TTL 6h"),
        ("note", "redis", "§M1: the sweep runs whether or not anything changed. Rewrite-on-change-only "
                          "would let an idle role's key expire with no trigger left to rewrite it."),
        ("end",),
        ("call", "user", "gw", "POST /auth/login\n{username, password}"),
        ("call", "gw", "idsvc", "POST /auth/login"),
        ("self", "idsvc", "assert app_user.status = 'ACTIVE' (§3)\nverify password_hash (bcrypt/argon2) · SET last_login_at"),
        ("note", "idsvc", "The status assert is the whole deactivation mechanism: users are never deleted, only flipped "
                          "to DISABLED (db-identity.md). Without it a disabled account still receives a valid token — "
                          "the ListUsersByRole ACTIVE filter only stops them being *assigned*, not authenticated. "
                          "Residual, accepted: a token already issued stays valid until exp; disable does not blacklist "
                          "the live jti."),
        ("ret", "idsvc", "gw", "200 {access_token}"),
        ("ret", "gw", "user", "200 {access_token}"),
        ("note", "idsvc", "JWT claims (registry §6): sub, username, full_name, department, roles[], jti, exp. "
                          "roles[] only — never permissions[], which would go stale until re-login (D11)."),
        ("call", "user", "gw", "GET /contracts\nAuthorization: Bearer <jwt>"),
        ("self", "gw", "verify signature + exp FIRST\n(never read a claim out of an unverified token)"),
        ("call", "gw", "redis", "EXISTS blacklist:{jti}"),
        ("ret", "redis", "gw", "0 — not revoked"),
        ("self", "gw", "coarse role check on the route (D11 layer 1)"),
        ("note", "gw", "Redis down here fails closed as well: the blacklist cannot be consulted, so the token cannot be "
                       "shown to be un-revoked. Same policy, same reason, as the permission cache below — Redis is "
                       "already a hard auth dependency (§M1)."),
        ("call", "gw", "svc", "GET /contracts\n+ X-PAS-User-Id / -Username / -Roles / -Department headers"),
        ("note", "svc", "Services trust those headers because the 80xx REST port is reachable only through the gateway "
                        "(D16) — the gRPC 505x port is not routed at all. Header names are a registry delta."),
        ("call", "svc", "redis", "MGET perm:role:{r} for every r ∈ jwt.roles[]"),
        ("ret", "redis", "svc", "[[\"contract:read\", …], …]"),
        ("self", "svc", "resolved set = UNION over the caller's roles\nassert contract:read ∈ set (D11 layer 2 — permissions, never roles)"),
        ("note", "svc", "Redis unavailable ⇒ PERMISSION_DENIED, fail closed (§M1). Deliberately unlike D9's "
                        "self-heal: a missed expiry warning costs nothing, a wrongly-granted permission is an incident."),
        ("ret", "svc", "gw", "200 [contracts]"),
        ("ret", "gw", "user", "200 [contracts]"),
        ("call", "user", "gw", "POST /auth/logout"),
        ("call", "gw", "idsvc", "POST /auth/logout"),
        ("call", "idsvc", "redis", "SETEX blacklist:{jti} … ttl = exp − now"),
        ("ret", "idsvc", "gw", "204"),
        ("ret", "gw", "user", "204"),
        ("frame", "opt [Phân quyền — the change-triggered half of §M1]"),
        ("call", "admin", "gw", "PUT /roles/{role_code}/permissions\n{permissions: [...]}"),
        ("call", "gw", "idsvc", "PUT /roles/{role_code}/permissions"),
        ("self", "idsvc", "assert user:manage ∈ permissions — identity resolves its own\npermission from the same cache, never special-cased (db-identity)"),
        ("self", "idsvc", "tx: DELETE + INSERT role_permission (role_id, permission_id)\n+ INSERT outbox('audit.recorded') — every grant and revoke is traced"),
        ("frame", "opt [best-effort cache overwrite — AFTER the commit]"),
        ("call", "idsvc", "redis", "SET perm:role:{code} = [recomputed permissions] · TTL 6h"),
        ("self", "idsvc", "SET fails or Redis is down ⇒ log + metric, swallow the error\nthe response is still 200 · the hourly sweep repairs the key"),
        ("end",),
        ("ret", "idsvc", "gw", "200 {role_code, permissions[]}"),
        ("ret", "gw", "admin", "200"),
        ("note", "idsvc", "The fragment is 'opt' and the failure is swallowed on purpose: Redis is NOT on this request's "
                          "success path. It cannot join the Postgres transaction, and letting a cache write fail an "
                          "authorization change that is already committed would report a lie to the admin and invite a "
                          "retry that re-writes role_permission for no reason. Contrast seq-01's read path, where Redis "
                          "being down is fail-closed PERMISSION_DENIED — enforcement must fail shut, propagation must "
                          "not fail the write."),
        ("note", "idsvc", "This is the path that actually delivers revocation; the hourly loop at the top is what BOUNDS "
                          "it. If this SET is missed, the sweep repairs the key within 1h (TTL 6h is the last-resort "
                          "backstop, not the mechanism). Net guarantee: bounded eventual revocation (§M1), never "
                          "immediacy — nothing in the requirement asks for immediacy, only for better than "
                          "'wait for re-login'."),
        ("note", "admin", "One role key, every holder at once — the map is keyed by role, not by user, which is why a "
                          "revoke needs no per-user fan-out. The asymmetric case: assigning or removing a user's ROLE "
                          "writes only user_role and touches Redis not at all, because roles[] is a JWT claim — that "
                          "change lands at the user's next login, not on this request (db-identity, §M1)."),
        ("end",),
    ],
}

# ── 2. outbox / audit / notification mechanics ────────────────────────────────
DIAGRAMS["seq-02-outbox-audit-notification"] = {
    "title": "2 · Outbox relay, centralized audit, notification fan-out",
    "caption": "D6 outbox · D15 centralized audit · D17 status history · D9/4.9 notifications · mechanics.md §M2. "
               "Drawn ONCE here; later diagrams reference it instead of redrawing the broker hop.",
    "participants": [
        ("owner", "owning service", "plain"),
        ("db", "its schema", "entity"),
        ("relay", "outbox relay", "control"),
        ("mq", "Kafka", "entity"),
        ("audit", "audit-service", "plain"),
        ("admin", ":System admin", "actor"),
        ("notif", "notification-service", "plain"),
        ("idsvc", "identity-service", "plain"),
        ("user", ":Internal user", "actor"),
    ],
    "steps": [
        ("frame", "one local transaction — atomic or nothing"),
        ("call", "owner", "db", "BEGIN"),
        ("call", "owner", "db", "UPDATE <entity> SET status = …, version = version + 1"),
        ("call", "owner", "db", "INSERT status_history (from_status, to_status, trigger_kind, trigger_ref)\nin the four schemas that own a state machine (D17)"),
        ("call", "owner", "db", "INSERT outbox (event_type='audit.recorded', payload)"),
        ("call", "owner", "db", "INSERT outbox (event_type=<domain event>, payload)"),
        ("call", "owner", "db", "COMMIT"),
        ("ret", "db", "owner", "ok"),
        ("end",),
        ("note", "db", "This single transaction is what makes 4.10's \"phải giữ được vết thay đổi quan trọng\" "
                       "unconditional (D15) and keeps the status column and status_history cross-checkable (D17). "
                       "A plain async audit write would drop the trail whenever a service died after COMMIT."),
        ("frame", "loop [relay poll, every N seconds]"),
        ("call", "relay", "db", "SELECT … WHERE published_at IS NULL AND cancelled_at IS NULL\n"
                                "AND (claimed_at IS NULL OR claimed_at < now() − lease) ORDER BY created_at"),
        ("ret", "db", "relay", "pending rows"),
        ("call", "relay", "db", "UPDATE outbox SET claimed_at = now()\nWHERE id = ? AND <same predicate>"),
        ("ret", "db", "relay", "1 row — claim won"),
        ("note", "relay", "§M2: the claim predicate must repeat the poll's staleness clause exactly. 0 rows ⇒ another "
                          "worker (re)claimed, published or cancelled it first — skip. Under READ COMMITTED the loser "
                          "re-evaluates against the winner's committed row, so this is real mutual exclusion."),
        ("async", "relay", "mq", "send(topic = pas.events | pas.audit, key = aggregate_id = document id,\n"
                                 "headers{event_type, document_type},\n"
                                 "value = envelope{event_id = outbox.id, occurred_at, actor, payload})"),
        ("call", "relay", "db", "UPDATE outbox SET published_at = now()"),
        ("note", "relay", "published_at is stamped only after the broker acks (acks=all, enable.idempotence=true — D2). "
                          "On send failure: clear claimed_at, retry_count + 1 — the same staleness clause reclaims it "
                          "either way. At-least-once is still the contract (the relay can die after the ack, before the "
                          "stamp), so consumers must dedup. key = aggregate_id ⇒ one partition per document — but Kafka "
                          "orders what was *sent*, so commit order survives only because this poll is ORDER BY created_at "
                          "and one publisher runs per service (§M2). Consumers must not depend on it: §9¹ is order-tolerant."),
        ("end",),
        ("frame", "opt [the cancelled_at side exit — who writes it (§M2)]"),
        ("call", "owner", "db", "UPDATE outbox SET cancelled_at = now()\nWHERE id = ? AND claimed_at IS NULL\nAND cancelled_at IS NULL AND published_at IS NULL"),
        ("ret", "db", "owner", "1 row ⇒ never dispatched, safe to abandon · 0 rows ⇒ claimed, do NOT cancel"),
        ("note", "owner", "Only a row nobody ever claimed is cancelled directly — no staleness clause here, deliberately. "
                          "A timestamp lease has no fencing, so \"stale\" never means the worker is dead; it may be paused "
                          "and resume. The full handoff for a claimed row is drawn in seq-03's cancel fragment."),
        ("end",),
        ("async", "mq", "audit", "audit.recorded — topic pas.audit, group 'audit-service'"),
        ("self", "audit", "INSERT audit_record (id = envelope event_id)\nON CONFLICT DO NOTHING"),
        ("note", "audit", "PK = source event id, so dedup is the natural key — audit-service needs no processed_event "
                          "table (db-audit.md). Rows are immutable: INSERT + SELECT grants only, so a business service "
                          "can never rewrite its own history."),
        ("async", "mq", "notif", "<domain event> — topic pas.events, group 'notification-service'"),
        ("note", "mq", "Fan-out is one consumer group per consuming service (D2), not a broker binding: every group "
                       "reads the whole topic and skips what it doesn't handle, on the event_type / document_type headers, before "
                       "deserializing the payload. Offsets are committed after processing, so a redelivery is normal — "
                       "not an error path. A record that can never succeed must go to pas.events.DLT and let the offset "
                       "advance: unlike a requeue, a stuck record blocks its whole partition behind it."),
        ("self", "notif", "SELECT processed_event WHERE event_id = ?"),
        ("frame", "opt [not yet processed]"),
        ("frame", "opt [event carries recipient_role, not user ids]"),
        ("call", "notif", "idsvc", "IdentityInternal.ListUsersByRole(role_code)"),
        ("ret", "idsvc", "notif", "ACTIVE users only — a disabled account is never notified"),
        ("end",),
        ("self", "notif", "tx: INSERT notification × N recipients\n+ INSERT processed_event"),
        ("note", "notif", "Recipient resolution (registry §4): assignee_ids / requested_by / owner_user_id come straight "
                          "off the payload; only recipient_role-addressed events need the lookup above. "
                          "notification-service never queries another service's business data (D9)."),
        ("end",),
        ("call", "user", "notif", "GET /notifications"),
        ("ret", "notif", "user", "[{category, title, body, document_no, read_at}]"),
        ("call", "user", "notif", "PATCH /notifications/{id}/read"),
        ("frame", "History tab — two sources, two consistency models (D15 + D17)"),
        ("call", "user", "owner", "GET /<documents>/{id}/history"),
        ("call", "owner", "db", "SELECT status_history WHERE entity_id = ?"),
        ("ret", "db", "owner", "status timeline — local, synchronous, immediately consistent"),
        ("call", "owner", "audit", "AuditInternal.ListRecords(entity_type, entity_id, page)"),
        ("ret", "audit", "owner", "field edits + actions without a status change — eventually consistent"),
        ("ret", "owner", "user", "200 {timeline, audit_records}"),
        ("note", "audit", "The accepted cost of D15: if audit-service is down the non-status half of this tab is "
                          "unavailable, business operations are not. Outbox rows accumulate and drain on recovery. "
                          "A business rule may only ever read status_history — never audit (D17)."),
        ("end",),
        ("frame", "Tra cứu audit log — the admin's cross-entity search"),
        ("call", "admin", "audit", "GET /audit-records\n?entity_type&entity_no&actor_id&source_service&action&from&to&page&size"),
        ("self", "audit", "assert audit:view_all ∈ permissions (§7 — SYSTEM_ADMIN only)\nSELECT … ORDER BY occurred_at DESC, paged\nserved by (actor_id | source_service | entity_type+entity_id, occurred_at DESC)\nand (action) — db-audit.md"),
        ("ret", "audit", "admin", "200 {records[], page, total}"),
        ("note", "admin", "REGISTRY DELTA — the cross-entity axes had indexes but no endpoint. AuditInternal.ListRecords "
                          "above is NOT a substitute: it is gRPC (service-to-service, D16), keyed on one "
                          "(entity_type, entity_id), and exists to fill an owning service's History tab. \"Tra cứu audit "
                          "log\" is a human asking \"what did user X do last week\" — user traffic, therefore REST "
                          "through the gateway, with filters and pagination. Read-only by construction: the schema "
                          "grants INSERT + SELECT only, so not even audit-service can rewrite the trail."),
        ("note", "audit", "actor_name and actor_department render from each row's own write-time snapshot and are never "
                          "resolved against identity at read time — 4.10's \"không phụ thuộc vào dữ liệu hiển thị hiện "
                          "tại\". A user renamed or DISABLED since must not change what a past record shows."),
        ("end",),
    ],
}

# ── 3. contract approval (flagship) ──────────────────────────────────────────
DIAGRAMS["seq-03-contract-approval"] = {
    "title": "3 · Contract create → submit → configurable approval → Active",
    "caption": "4.2, 4.7 · CTR-01/02/03/04/05 · APR-01/02/03/04/05 · 5.5 double-submit + approve race · "
               "D4 dispatch, D5 lock, D14d scheduler. Addendum follows the identical path (own workflow, 2 steps).",
    "participants": [
        ("sales", ":Sales officer", "actor"),
        ("appr", ":Approver", "actor"),
        ("ct", "contract-service", "plain"),
        ("relay", "contract relay", "control"),
        ("wf", "workflow-service", "plain"),
        ("idsvc", "identity-service", "plain"),
        ("mq", "Kafka", "entity"),
        ("notif", "notification-service", "plain"),
        ("sched", "scheduler", "control"),
    ],
    "steps": [
        ("call", "sales", "ct", "POST /contracts\n{customer_id, valid_from, valid_to, terms, vat_rate}"),
        ("self", "ct", "basic field validation · status = DRAFT"),
        ("ret", "ct", "sales", "201 {contract_no: CTR-2026-0142, status: DRAFT}"),
        ("call", "sales", "ct", "POST /contracts/{id}/attachments"),
        ("note", "ct", "CTR-01: edits and uploads are accepted only in DRAFT or REVISION_REQUESTED — an app-level check "
                       "against registry §9 plus the version column, since a DB CHECK cannot see a transition."),
        ("call", "sales", "ct", "POST /contracts/{id}/submit"),
        ("self", "ct", "assert status = 'DRAFT' (§9 — the only edge into SUBMITTED)\nassert customer.status = 'ACTIVE'\nassert valid_from ≤ valid_to\nassert ≥ 1 attachment          (the last three = CTR-02)"),
        ("note", "ct", "CTR-02 is a SUBMIT precondition, not a create-time one — the contract stays editable in DRAFT "
                       "and 4.1 lets its customer be suspended meanwhile, so both facts are re-checked here or a "
                       "contract for a SUSPENDED customer enters the chain. REVISION_REQUESTED is deliberately NOT "
                       "admitted: §9 routes it back through DRAFT via 'update' first."),
        ("frame", "alt [pre-check — read-only, nothing has committed yet]"),
        ("call", "ct", "wf", "WorkflowInternal.ValidateStartable(CONTRACT)"),
        ("call", "wf", "idsvc", "IdentityInternal.ListUsersByRole(role)\nonce per distinct role in the active definition"),
        ("ret", "idsvc", "wf", "ACTIVE holders per role"),
        ("ret", "wf", "ct", "OK — every step resolves ≥ 1 assignee"),
        ("div", "[FAILED_PRECONDITION — a step's role has no ACTIVE holder]"),
        ("ret", "wf", "ct", "FAILED_PRECONDITION {role: LEGAL_REVIEWER}"),
        ("ret", "ct", "sales", "422 — no active LEGAL_REVIEWER; admin must fix role assignments"),
        ("div", "[UNAVAILABLE — workflow-service down]"),
        ("self", "ct", "skip the pre-check, proceed to commit + dispatch\n(it is an optimisation, never a gate — degrades to plain D4)"),
        ("end",),
        ("note", "wf", "REGISTRY DELTA. It must be a READ: a mutating call before the local commit is exactly the orphan "
                       "D4 forbids, whereas a read leaves no remote state to orphan if the commit then fails. It exists "
                       "because db-workflow.md's \"zero assignees ⇒ submit fails, nothing strands\" is unimplementable "
                       "once D4 commits SUBMITTED first. The race it cannot close is handled below, not hand-waved."),
        ("frame", "one local transaction (D4 step 1)"),
        ("self", "ct", "UPDATE contract SET status='SUBMITTED', version = version + 1\nWHERE id = ? AND status = 'DRAFT' AND version = ?\n0 rows ⇒ 409, whole tx rolls back (5.5 double submit)"),
        ("self", "ct", "+ INSERT status_history (DRAFT→SUBMITTED, trigger_kind = U)\n+ outbox('workflow.start_requested', payload.idempotency_key)\n+ outbox('audit.recorded')"),
        ("end",),
        ("note", "ct", "The guard is what makes a double-clicked Submit a local 409 rather than two status_history rows, "
                       "two audit rows and two differently-keyed dispatch rows — one of which could never succeed. "
                       "workflow-service's partial unique index is the second line of defence, not the first."),
        ("ret", "ct", "sales", "200 {status: SUBMITTED, workflow: initialization pending}"),
        ("note", "ct", "D4: the document is genuinely SUBMITTED with no instance yet, and the UI says exactly that "
                       "rather than erroring. Nothing is inconsistent — just briefly incomplete."),
        ("frame", "alt [relay dispatch, retried with backoff — D4 step 2]"),
        ("call", "relay", "wf", "WorkflowInternal.StartInstance(CONTRACT, document_id, document_no,\ncustomer_name, requested_by, requested_by_name, priority, idempotency_key)"),
        ("call", "wf", "idsvc", "IdentityInternal.ListUsersByRole(role)\nevery distinct role — BEFORE any write"),
        ("ret", "idsvc", "wf", "ACTIVE users per role"),
        ("self", "wf", "tx: INSERT workflow_instance (idempotency_key UNIQUE permanent;\npartial unique WHERE status='IN_PROGRESS')\n+ ALL workflow_step_instance rows + ALL step_assignee snapshots\n+ activate step 1 + outbox(instance_started, step_assigned, audit.recorded)"),
        ("ret", "wf", "relay", "OK {instance_id} → relay stamps published_at"),
        ("note", "wf", "Resolution happens before the transaction opens and every row is written inside it, so the "
                       "existence of an instance implies its completeness. That is what makes §5.1's \"a retry with the "
                       "same key returns the existing instance\" safe: otherwise a crash mid-creation leaves a live "
                       "IN_PROGRESS instance with no steps, and the permanent key makes that state permanent."),
        ("note", "wf", "Two constraints, two jobs (D4). The permanent key makes retrying THIS dispatch safe at any "
                       "status, including after cancellation. The partial index rejects a concurrent, differently-keyed "
                       "double-submit (5.5) while an instance is active. Neither subsumes the other."),
        ("div", "[UNAVAILABLE — the only status §5.1 retries in general]"),
        ("self", "relay", "clear claimed_at, retry_count + 1 → reclaimed after the lease"),
        ("div", "[FAILED_PRECONDITION / ALREADY_EXISTS — permanent]"),
        ("self", "relay", "stop retrying, park the outbox row\n+ outbox('audit.recorded') · document stays SUBMITTED,\nflagged \"workflow initialization failed\""),
        ("note", "relay", "The residual race the pre-check cannot close (a sole assignee disabled between check and "
                          "dispatch) lands here — visibly parked for an admin, never retried forever. Same discipline "
                          "as seq-07, which terminates a send at FAILED after 3 attempts."),
        ("end",),
        ("async", "mq", "ct", "workflow.instance_started"),
        ("self", "ct", "tx: UPDATE … SET status='UNDER_REVIEW' WHERE status='SUBMITTED'\n+ status_history (trigger_kind = W) + INSERT processed_event (§9¹)"),
        ("note", "ct", "Guarded on the expected from_status and deduped in the same transaction: delivery is "
                       "at-least-once (D6), so a redelivered instance_started must be a no-op, not a second flip. "
                       "Order-tolerant in BOTH directions (§9¹): Kafka orders what the relay sent, not what committed, "
                       "so a workflow.completed can arrive while the document is still SUBMITTED — that handler admits "
                       "SUBMITTED and applies the skipped SUBMITTED → UNDER_REVIEW edge itself before the outcome, in "
                       "one transaction, with a status_history row for each edge (both trigger_kind = W). Two §9 edges "
                       "in sequence, never an edge §9 doesn't have. Conversely an instance_started landing after the "
                       "outcome matches no row and does nothing. Without both halves a reorder wedges the document "
                       "permanently. PRICE_LIST and PAYMENT_STATEMENT have no UNDER_REVIEW state and ignore this "
                       "event entirely."),
        ("async", "mq", "notif", "workflow.instance_started + workflow.step_assigned"),
        ("self", "notif", "INSERT notification × assignee_ids\n\"New document assigned to you\""),
        ("note", "mq", "Relay claim and broker mechanics are exactly seq-02 and are not redrawn."),
        ("frame", "alt [Sales cancels while still SUBMITTED — the §M2 handoff]"),
        ("call", "sales", "ct", "POST /contracts/{id}/cancel"),
        ("self", "ct", "UPDATE outbox SET cancelled_at = now()\nWHERE id = ? AND claimed_at IS NULL\nAND cancelled_at IS NULL AND published_at IS NULL"),
        ("note", "ct", "1 row ⇒ dispatch was never attempted, so no instance can ever exist: set CANCELLED, stop. "
                       "0 rows ⇒ the row is claimed — fresh OR stale, treated identically, because a timestamp lease has "
                       "no fencing and a \"stale\" worker may merely be paused. The document is NOT flipped yet."),
        ("call", "ct", "wf", "WorkflowInternal.CancelInstance(CONTRACT, document_id, idempotency_key)"),
        ("ret", "wf", "ct", "OK — instance CANCELLED, remaining steps swept"),
        ("self", "ct", "SUBMITTED → CANCELLED + status_history + outbox('audit.recorded')"),
        ("div", "[NOT_FOUND — not created YET; §5.1's one retryable NOT_FOUND]"),
        ("self", "ct", "fresh claim ⇒ back off, retry from the top\nstale claim ⇒ re-claim the row, run StartInstance with the STORED key,\nthen cancel the instance it creates — never assume it won't complete"),
        ("div", "[FAILED_PRECONDITION — a step was already actioned]"),
        ("ret", "wf", "ct", "cancel refused for the whole document (existing behaviour)"),
        ("end",),
        ("call", "appr", "wf", "POST /workflow-steps/{step_instance_id}/actions\n{action, comment}"),
        ("self", "wf", "assert approval:act ∈ permissions (perm cache)\nassert caller ∈ step_assignee OF THIS step\nassert step.status = 'ACTIVE'"),
        ("note", "wf", "APR-01 in force: role alone is insufficient — only the snapshotted assignees of the ACTIVE step "
                       "pass. Option A (registry change log): every ACTIVE holder of the step's role IS an assignee — "
                       "there is no per-manager document ownership, which requirement.md never defines. APR-02 falls out "
                       "of the same predicate: PENDING steps have no assignees, completed steps are not ACTIVE."),
        ("self", "wf", "tx: UPDATE workflow_step_instance SET status, version = version + 1\nWHERE id = ? AND version = ? AND status = 'ACTIVE'\n+ INSERT workflow_action(comment) (APR-03)\n+ outbox(step_actioned, audit.recorded)"),
        ("frame", "alt [0 rows — concurrent approve lost the race (5.5, D5)]"),
        ("ret", "wf", "appr", "409 ABORTED — this step was already actioned"),
        ("end",),
        ("frame", "alt [APPROVE — steps remain]"),
        ("self", "wf", "activate next step (PENDING → ACTIVE)\nassignees were already snapshotted at instance creation\n+ outbox(step_assigned, audit.recorded)"),
        ("async", "mq", "notif", "workflow.step_assigned → next step's assignees"),
        ("ret", "wf", "appr", "200 {step: APPROVED, next_step: Legal review}"),
        ("div", "[APPROVE — final step (APR-05)]"),
        ("self", "wf", "instance → APPROVED\n+ outbox(workflow.completed{APPROVED}, audit.recorded)"),
        ("async", "mq", "ct", "workflow.completed {outcome: APPROVED}"),
        ("self", "ct", "tx: completion guard status IN ('SUBMITTED','UNDER_REVIEW')\n"
                       "SUBMITTED: apply SUBMITTED → UNDER_REVIEW first (§9¹)\n"
                       "then → APPROVED; one status_history row per edge, both trigger_kind = W\n"
                       "+ processed_event (D3) — all in the one transaction"),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"contract approved\")"),
        ("ret", "wf", "appr", "200 {step: APPROVED, document: APPROVED}"),
        ("div", "[REJECT or REQUEST_REVISION (APR-03/04)]"),
        ("self", "wf", "tx: instance → REJECTED | REVISION_REQUESTED\nsweep remaining PENDING steps → CANCELLED\n+ outbox(workflow.completed, audit.recorded)"),
        ("async", "mq", "ct", "workflow.completed {outcome: REJECTED | REVISION_REQUESTED}"),
        ("self", "ct", "tx: completion guard status IN ('SUBMITTED','UNDER_REVIEW')\n"
                       "SUBMITTED: apply SUBMITTED → UNDER_REVIEW first (§9¹)\n"
                       "then → REJECTED | REVISION_REQUESTED; one status_history row per edge,\n"
                       "both trigger_kind = W + processed_event — all in the one transaction"),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"rejected — action required\")"),
        ("ret", "wf", "appr", "200 {step: REJECTED, document: REJECTED}"),
        ("note", "ct", "CTR-04: a REJECTED contract is never auto-resubmitted — REJECTED → DRAFT needs an explicit, "
                       "audited 'revise' action. REVISION_REQUESTED → DRAFT on update. Either way the next submit "
                       "generates a NEW idempotency_key and therefore a new instance."),
        ("end",),
        ("frame", "loop [scheduled job inside contract-service, daily — D14d]"),
        ("call", "sched", "ct", "tick"),
        ("self", "ct", "APPROVED and valid_from ≤ today ⇒ ACTIVE (CTR-05)\ntx: status + status_history (trigger_kind = S) + outbox('audit.recorded')"),
        ("self", "ct", "ACTIVE and valid_to < today ⇒ EXPIRED\ntx: status + status_history (trigger_kind = S) + outbox('audit.recorded')"),
        ("async", "ct", "mq", "document.expiring {days_left, owner_user_id} — direct publish, no outbox (D9)"),
        ("async", "mq", "notif", "document.expiring → owner_user_id (\"contract expiring in 30 days\")"),
        ("note", "sched", "D14e: contract status never reacts to e-sign. APPROVED → ACTIVE fires on schedule whether a "
                          "signing session exists, is in flight, or completed — contract-service has no dependency on "
                          "esign-service at all. 5.5 forbids mixing approval and signing state."),
        ("end",),
        ("frame", "loop [SLA sweep — workflow-service, hourly]"),
        ("call", "sched", "wf", "tick"),
        ("self", "wf", "ACTIVE steps where now() − activated_at > sla_hours\nAND overdue_notified_at IS NULL"),
        ("async", "wf", "mq", "workflow.step_overdue {waiting_hours, sla_hours, assignee_ids}\nstamp overdue_notified_at (emit once, not every run)"),
        ("async", "mq", "notif", "workflow.step_overdue → assignees (\"approval overdue\")"),
        ("end",),
    ],
}

# ── 4. price list version ────────────────────────────────────────────────────
DIAGRAMS["seq-04-pricelist-version"] = {
    "title": "4 · Price list version — approval, overlap guard, supersede, historical lookup",
    "caption": "4.4 · PRC-01/02/03/04/05/06 · D8 addendum-driven version · registry §9³ truncate-then-approve. "
               "Approval itself is seq-03 and is collapsed here.",
    "participants": [
        ("sales", ":Sales officer", "actor"),
        ("pr", "pricing-service", "plain"),
        ("db", "pricing schema", "entity"),
        ("relay", "pricing relay", "control"),
        ("wf", "workflow-service", "plain"),
        ("mq", "Kafka", "entity"),
        ("notif", "notification-service", "plain"),
        ("sched", "scheduler", "control"),
        ("bill", "billing-service", "plain"),
    ],
    "steps": [
        ("call", "sales", "pr", "POST /price-lists/{id}/versions\n{valid_from, valid_to, lines[], addendum_id?}"),
        ("self", "pr", "assert ≥ 1 scope field set (PRC-01)\nassert valid_from ≤ valid_to (PRC-02)\nassert valid_from > the latest LOCKED period's end_date\nderive scope_key · status = DRAFT"),
        ("note", "pr", "No backdating: PRC-02 only checks from ≤ to, so without the third assert a successor could be "
                       "given a valid_from inside an already-billed period, retroactively changing which version was "
                       "\"in force\" there — a rebuild or adjustment would then resolve a different version than the "
                       "original statement snapshotted, defeating the stability PAY-03 exists for."),
        ("note", "pr", "D8: addendum_id is set only when Sales creates this version from an APPROVED addendum, with "
                       "valid_from = addendum.effective_from. Manual, no automation, no addendum.* event — pricing stays "
                       "the single source of unit prices (4.3)."),
        ("call", "sales", "pr", "POST /price-list-versions/{id}/submit"),
        ("call", "pr", "wf", "WorkflowInternal.ValidateStartable(PRICE_LIST)"),
        ("ret", "wf", "pr", "OK"),
        ("self", "pr", "warn on a cross-scope overlap the EXCLUDE cannot see\n(different scope kinds, same customer — db-pricing.md's residual gap)"),
        ("self", "pr", "tx: DRAFT → SUBMITTED + status_history\n+ outbox('workflow.start_requested', idempotency_key) + outbox(audit)"),
        ("frame", "ref — approval per seq-03 (Commercial → Director)"),
        ("call", "relay", "wf", "WorkflowInternal.StartInstance(PRICE_LIST, …, idempotency_key)"),
        ("end",),
        ("frame", "alt [outcome APPROVED]"),
        ("async", "mq", "pr", "workflow.completed {outcome: APPROVED}"),
        ("frame", "one transaction — ORDER IS LOAD-BEARING (§9³)"),
        ("call", "pr", "db", "BEGIN"),
        ("call", "pr", "db", "assert no overlapping APPROVED/EFFECTIVE version has\nvalid_from ≥ successor.valid_from → else FAILED_PRECONDITION\n(a successor may not rewrite a later approved version's validity)"),
        ("call", "pr", "db", "UPDATE predecessor SET valid_to = successor.valid_from − 1 day\nWHERE scope_key = ? AND status IN ('APPROVED','EFFECTIVE')\nAND id <> :successor_id AND valid_from < :successor_valid_from\nAND valid_to ≥ :successor_valid_from"),
        ("call", "pr", "db", "UPDATE successor SET status = 'APPROVED'"),
        ("call", "pr", "db", "INSERT status_history (successor SUBMITTED→APPROVED, trigger_kind = W)\n+ INSERT processed_event + INSERT outbox(audit.recorded)\nthe predecessor's valid_to change is an audit 'changes' entry, not a transition"),
        ("call", "pr", "db", "COMMIT"),
        ("ret", "db", "pr", "ok"),
        ("end",),
        ("note", "db", "Truncate BEFORE approve, or the EXCLUDE USING gist constraint (scope_key =, daterange &&) "
                       "WHERE status IN ('APPROVED','EFFECTIVE') rejects the successor and PRC-04's supersede flow "
                       "becomes unreachable. Truncation never touches unit_price, so PAY-03 snapshots on issued "
                       "statements are unaffected — and non-overlapping ranges are what make the historical lookup "
                       "below unambiguous."),
        ("note", "db", "The three added predicates matter: `id <> successor` stops the successor truncating ITSELF on a "
                       "redelivered event (it is APPROVED by then and would otherwise match its own WHERE clause, "
                       "setting valid_to < valid_from and aborting on PRC-02 forever — a poison message); "
                       "`valid_from <` scopes truncation to genuine predecessors; and `processed_event` makes this the "
                       "idempotent consumer every other one already is."),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"price list approved\")"),
        ("div", "[outcome REJECTED (PRC-06)]"),
        ("async", "mq", "pr", "workflow.completed {outcome: REJECTED}"),
        ("self", "pr", "tx: SUBMITTED → REJECTED + status_history + processed_event"),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"rejected\")"),
        ("call", "sales", "pr", "POST …/revise → DRAFT, edit, submit again"),
        ("end",),
        ("frame", "loop [scheduled job inside pricing-service, daily — D14d]"),
        ("call", "sched", "pr", "tick"),
        ("self", "pr", "APPROVED and valid_from ≤ today ⇒ EFFECTIVE\nsame tx: predecessor EFFECTIVE → SUPERSEDED (PRC-04)\nsupersede is evaluated BEFORE the valid_to → EXPIRED rule"),
        ("note", "sched", "Order pinned deliberately: after truncation the predecessor's valid_to is successor.valid_from "
                          "− 1, so on activation day both the EXPIRED rule and the SUPERSEDED rule match. §9/PRC-04 "
                          "permit either, but a version replaced by a successor should read SUPERSEDED, not EXPIRED."),
        ("self", "pr", "EFFECTIVE and valid_to < today ⇒ EXPIRED (no successor)\ntx: status + status_history (trigger_kind = S) + outbox(audit)"),
        ("async", "pr", "mq", "document.expiring — price list about to lapse (4.9, D9, direct publish)"),
        ("async", "mq", "notif", "document.expiring → owner_user_id (\"expiring in 14 days\")"),
        ("end",),
        ("call", "bill", "pr", "PricingInternal.GetEffectivePriceList(\ncontract_id, customer_id, service_group, date = period_end)"),
        ("self", "pr", "resolve scope by precedence:\nCONTRACT > CUSTOMER+GROUP > CUSTOMER\nrange contains date — SUPERSEDED/EXPIRED included"),
        ("ret", "pr", "bill", "{price_list_no, version_no, status,\nlines[{service_code, service_name, unit, unit_price, currency}]}"),
        ("note", "bill", "The response carries service_name and unit because pricing owns the service catalog — billing's "
                         "PAY-03 line snapshot must come from the authority, not from a snapshot-of-operations'-snapshot. "
                         "The lookup is historical, not current: a June statement rebuilt in July must resolve the version "
                         "in force in June. PRC-05 then holds structurally — anything past DRAFT is read-only, so a "
                         "correction is a new version, never an edit."),
    ],
}

# ── 5. volume + period lock ──────────────────────────────────────────────────
DIAGRAMS["seq-05-volume-period-lock"] = {
    "title": "5 · Volume recording, period lock, and the traced post-lock escape hatch",
    "caption": "4.5 · PAY-02 (billing side) · permissions volume:write / volume:lock_period / volume:edit_locked · "
               "D15 audit. Lock is the only confirmation mechanism — volume rows have no status of their own.",
    "participants": [
        ("ops", ":Ops officer", "actor"),
        ("op", "operations-service", "plain"),
        ("ct", "contract-service", "plain"),
        ("pr", "pricing-service", "plain"),
        ("mq", "Kafka", "entity"),
        ("notif", "notification-service", "plain"),
        ("idsvc", "identity-service", "plain"),
        ("bill", "billing-service", "plain"),
    ],
    "steps": [
        ("call", "ops", "op", "POST /volume-records\n{period_code, contract_id, service_code, quantity}"),
        ("self", "op", "assert volume:write ∈ permissions\nassert period.status = 'OPEN'"),
        ("call", "op", "ct", "ContractInternal.GetContract(contract_id)"),
        ("ret", "ct", "op", "{customer_id, customer_name, status} — validates the contract exists"),
        ("call", "op", "pr", "PricingInternal.GetServiceItem(service_code)"),
        ("ret", "pr", "op", "{name, unit, is_active} — NOT_FOUND ⇒ 422 unknown service code"),
        ("note", "pr", "Validating and snapshotting in the same call, at entry. Without it an Ops typo in service_code "
                       "cannot fail until statement time weeks later, where it surfaces as the misleading \"no suitable "
                       "price list\" (seq-06). D7 also wants snapshots captured FROM the owner, not client-supplied."),
        ("self", "op", "tx: INSERT volume_record (VOL-2026-0712)\nsnapshots: customer_id, customer_name, service_name, unit (D7)\n+ outbox('audit.recorded')"),
        ("ret", "op", "ops", "201 {record_no, period_state: OPEN}"),
        ("call", "ops", "op", "PATCH /volume-records/{id} {quantity}"),
        ("self", "op", "allowed freely while period is OPEN (4.5)\ntx: UPDATE + outbox(audit.recorded)"),
        ("call", "ops", "op", "POST /operation-periods/{period_code}/lock"),
        ("self", "op", "assert volume:lock_period ∈ permissions\nassert period.status = 'OPEN' (a second lock is a no-op 409)\ntx: OPEN → LOCKED, locked_by, locked_by_name, locked_at\n+ status_history (trigger_kind = U) + outbox('audit.recorded')"),
        ("async", "op", "mq", "operations.period_locked {period_code, recipient_role: 'ACCOUNTANT'}"),
        ("note", "op", "Published directly, no outbox row — registry §4 marks it informational (\"statements can now be "
                       "generated\"). A lost copy costs nothing, unlike the audit.recorded row beside it, which is "
                       "always outboxed. Two different durability needs in one handler."),
        ("async", "mq", "notif", "operations.period_locked"),
        ("call", "notif", "idsvc", "IdentityInternal.ListUsersByRole('ACCOUNTANT')"),
        ("ret", "idsvc", "notif", "ACTIVE accountants"),
        ("self", "notif", "INSERT notification × N + processed_event"),
        ("note", "op", "There is no unlock transition (registry §9). The period is the single confirmation signal "
                       "PAY-02 checks — modelling a per-record confirmed flag as well would be two mechanisms for one fact."),
        ("frame", "alt [post-lock edit — caller lacks volume:edit_locked]"),
        ("call", "ops", "op", "PATCH /volume-records/{id}"),
        ("ret", "op", "ops", "403 PERMISSION_DENIED — period LOCKED (4.5)"),
        ("div", "[caller holds volume:edit_locked]"),
        ("call", "ops", "op", "PATCH /volume-records/{id}"),
        ("self", "op", "tx: UPDATE volume_record\n+ outbox('audit.recorded') — MANDATORY on this path"),
        ("ret", "op", "ops", "200 {record_no}"),
        ("note", "op", "4.5's \"quyền đặc biệt\" escape hatch is only defensible because every use of it is traced. "
                       "The audit write is not optional here — and because a statement may already be issued off these "
                       "volumes, the correction downstream is an adjustment statement (PAY-05), never an in-place edit."),
        ("end",),
        ("call", "bill", "op", "OperationsInternal.ListVolumes(contract_id, period_code)"),
        ("ret", "op", "bill", "{period_state: LOCKED, period_start, period_end,\nvolumes[{record_no, service_code, unit, quantity}]}"),
        ("note", "bill", "`period_start`/`period_end` come from the owner's `operation_period` row rather than being "
                         "re-derived from the period_code string — billing snapshots them onto the statement (PAY-03), so "
                         "they must be the authoritative values. billing refuses to build unless period_state = LOCKED "
                         "(PAY-02) — see seq-06."),
        ("note", "op", "A post-lock edit under the escape hatch changes a quantity a statement may already have "
                       "snapshotted. For an ISSUED statement the correction is an adjustment (PAY-05); for one still in "
                       "CALCULATED/RECONCILED, seq-06's reconcile compares snapshot vs current quantity and blocks — "
                       "otherwise the drift would reach APPROVED undetected."),
    ],
}

# ── 6. payment statement build ───────────────────────────────────────────────
DIAGRAMS["seq-06-payment-statement"] = {
    "title": "6 · Payment statement — three sync pulls, price snapshot, reconcile, approve",
    "caption": "4.6 · PAY-01/02/03/04 · D7 snapshots · D14f rework paths. The integration flagship: billing is the "
               "only service that reads three others in one operation.",
    "participants": [
        ("acct", ":Accountant", "actor"),
        ("bill", "billing-service", "plain"),
        ("ct", "contract-service", "plain"),
        ("op", "operations-service", "plain"),
        ("pr", "pricing-service", "plain"),
        ("relay", "billing relay", "control"),
        ("wf", "workflow-service", "plain"),
        ("mq", "Kafka", "entity"),
        ("notif", "notification-service", "plain"),
    ],
    "steps": [
        ("call", "acct", "bill", "POST /payment-statements {contract_id, period_code}"),
        ("self", "bill", "assert no live statement for (contract_id, period_code)\npartial unique WHERE adjusts_statement_id IS NULL\nAND status NOT IN ('CANCELLED','REJECTED')"),
        ("call", "bill", "ct", "ContractInternal.GetContract(contract_id)"),
        ("ret", "ct", "bill", "{status, valid_from, valid_to, service_group, vat_rate,\npayment_term, customer_id, customer_name, contract_no, currency}"),
        ("note", "ct", "Registry §9²: these are the EFFECTIVE values — an approved TERM_EXTENSION or PAYMENT_TERMS "
                       "addendum has already been applied to the contract row, so billing never has to reason about "
                       "addenda. That is why 4.3's \"nghiệp vụ sau thời điểm hiệu lực dùng thông tin mới\" holds here. "
                       "`service_group` is here because it is the pricing lookup's scope key."),
        ("self", "bill", "assert status ∈ {ACTIVE, EXPIRED} — reject DRAFT/SUBMITTED/REJECTED/CANCELLED\nassert period within [valid_from, valid_to] (PAY-01)"),
        ("note", "bill", "EXPIRED must be admitted, not just ACTIVE. A contract ending 30/06 flips EXPIRED on 01/07 "
                         "(D14d), and the June statement is always built in July — testing for ACTIVE would make every "
                         "contract's final period permanently unbillable. PAY-01's real requirement is that the contract "
                         "was in force *during the period*, which the second assert is what actually checks."),
        ("call", "bill", "op", "OperationsInternal.ListVolumes(contract_id, period_code)"),
        ("ret", "op", "bill", "{period_state, period_start, period_end, volumes[]}"),
        ("self", "bill", "assert period_state = 'LOCKED' (PAY-02)"),
        ("call", "bill", "pr", "PricingInternal.GetEffectivePriceList(contract_id,\ncustomer_id, service_group, date = period_end)"),
        ("ret", "pr", "bill", "{price_list_no, version_no,\nlines[{service_code, service_name, unit, unit_price, currency}]}"),
        ("self", "bill", "assert every volume.service_code is priced in this version\nelse 422 — no suitable price list for the period (PAY-01)"),
        ("note", "bill", "All three reads happen before anything is written, so an UNAVAILABLE callee (§5.1 deadlines) "
                         "just fails the request with nothing committed — no failure fragment needed. The order is not "
                         "arbitrary: the contract supplies customer_id + service_group, which are inputs to the pricing "
                         "lookup, and period_end is the pricing lookup's date."),
        ("frame", "one transaction — all durable values are snapshots (PAY-03)"),
        ("self", "bill", "INSERT payment_statement\nsnapshots: contract_no, customer_name, period_start/end,\nprice_list_no + version_no, payment_term, vat_rate, currency"),
        ("self", "bill", "INSERT statement_line per priced service_code\nquantity = Σ of that code's volume records\nsnapshots: service_name, unit, unit_price · source = 'CALCULATED'"),
        ("self", "bill", "INSERT statement_line_volume per contributing record\n(line → volume_record, quantity snapshot)"),
        ("self", "bill", "subtotal = Σ amount · tax_amount = subtotal × vat_rate · total_amount\nDRAFT → CALCULATED + status_history + outbox('audit.recorded')"),
        ("end",),
        ("ret", "bill", "acct", "201 {statement_no: PMT-2026-0331, subtotal, tax_amount, total_amount}"),
        ("note", "bill", "One line per service, not per volume record — that is what makes `statement_line_volume` a real "
                         "1:N trace (the Figma \"Source volumes\" tab) instead of a 1:1 decoration, and what 4.6's "
                         "\"danh sách dịch vụ, đơn giá, sản lượng tính phí\" describes. Nothing here is a live reference: "
                         "a version published next month or a renamed service must not change what this renders (PAY-03)."),
        ("frame", "alt [controlled edit before submit (4.6, D14f)]"),
        ("call", "acct", "bill", "PATCH /payment-statements/{id}/lines\n{line_no, quantity | unit_price, note}"),
        ("self", "bill", "assert statement:write · assert status ∈ {DRAFT, CALCULATED}\n(PAY-05: never after APPROVED/SIGNED/ISSUED) · version guard\nline.source = 'MANUAL' · CALCULATED → DRAFT\n+ status_history + outbox('audit.recorded')"),
        ("call", "acct", "bill", "POST /payment-statements/{id}/calculate"),
        ("self", "bill", "recompute totals · refresh source='CALCULATED' lines,\nPRESERVE source='MANUAL' lines\nDRAFT → CALCULATED + status_history"),
        ("note", "bill", "The edit drops the status to DRAFT (an edit invalidates the calculation, D14f), so the "
                         "re-calculate is what makes the loop walkable — reconcile only accepts CALCULATED. Preserving "
                         "MANUAL lines is the rule that stops the recalculation silently discarding the correction."),
        ("end",),
        ("call", "acct", "bill", "POST /payment-statements/{id}/reconcile"),
        ("call", "bill", "op", "OperationsInternal.ListVolumes(contract_id, period_code)"),
        ("ret", "op", "bill", "current volumes"),
        ("self", "bill", "re-check: period still LOCKED · contract in force during period ·\nresolved version still = the snapshotted price_list_no + version_no ·\nstatement_line_volume.quantity == current record quantity ·\nmanual adjustments listed\nCALCULATED → RECONCILED + status_history"),
        ("note", "bill", "Two corrections here. (1) The version check compares against the SNAPSHOT, not \"is it still "
                         "EFFECTIVE\" — reconciling a June statement in July finds it SUPERSEDED, which is correct and "
                         "expected under the historical lookup. (2) The quantity comparison is what catches a post-lock "
                         "edit made under `volume:edit_locked` (seq-05) after the snapshot was taken; without it stale "
                         "quantities reach APPROVED, SIGNED and ISSUED undetected — exactly what 4.6's \"đối soát\" is for."),
        ("call", "acct", "bill", "POST /payment-statements/{id}/submit"),
        ("self", "bill", "assert ≥ 1 line · total_amount ≥ 0 (PAY-04)\nassert every LOCKED volume of (contract, period) is mapped"),
        ("call", "bill", "wf", "WorkflowInternal.ValidateStartable(PAYMENT_STATEMENT)"),
        ("ret", "wf", "bill", "OK"),
        ("self", "bill", "tx: RECONCILED → SUBMITTED + status_history\n+ outbox('workflow.start_requested', idempotency_key) + outbox(audit)"),
        ("frame", "ref — approval per seq-03 (Accounting → Director)"),
        ("call", "relay", "wf", "WorkflowInternal.StartInstance(PAYMENT_STATEMENT, …, idempotency_key)"),
        ("end",),
        ("frame", "alt [outcome APPROVED]"),
        ("async", "mq", "bill", "workflow.completed {outcome: APPROVED}"),
        ("self", "bill", "tx: SUBMITTED → APPROVED + status_history + processed_event\n→ ready for e-signature (PAY-06, seq-07)"),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"statement approved\")"),
        ("div", "[outcome REJECTED / REVISION_REQUESTED]"),
        ("async", "mq", "bill", "workflow.completed {outcome: REJECTED | REVISION_REQUESTED}"),
        ("self", "bill", "tx: SUBMITTED → REJECTED | REVISION + status_history + processed_event"),
        ("async", "mq", "notif", "workflow.completed → requested_by (\"rejected — action required\")"),
        ("call", "acct", "bill", "POST …/revise → DRAFT (explicit, audited — D14f)"),
        ("end",),
        ("frame", "opt [after ISSUED — correction and cancellation (PAY-05)]"),
        ("call", "acct", "bill", "POST /payment-statements/{id}/adjustments"),
        ("self", "bill", "assert source status = 'ISSUED'\nINSERT a NEW statement, adjusts_statement_id = source.id,\nstatus = DRAFT — re-enters the build path above\nthe original stays immutable"),
        ("ret", "bill", "acct", "201 {statement_no: PMT-2026-0402, adjusts: PMT-2026-0331}"),
        ("call", "acct", "bill", "POST /payment-statements/{id}/cancel {reason}"),
        ("self", "bill", "assert statement:cancel_approved ∈ permissions (in no role bundle, §7)\nassert status ∈ {APPROVED, SIGNED} · reason mandatory\nAPPROVED|SIGNED → CANCELLED + status_history + outbox(audit)"),
        ("note", "bill", "PAY-05 has two arms and they are different: a wrong *amount* becomes an adjustment statement "
                         "(the original is never edited), while abandoning the document is the controlled cancel — gated "
                         "by a permission nobody holds by default and always traced. A negative net correction is "
                         "cancel-then-reissue, since total_amount ≥ 0 (PAY-04) binds adjustments too."),
        ("end",),
    ],
}

# ── 7. e-signature ───────────────────────────────────────────────────────────
DIAGRAMS["seq-07-esign"] = {
    "title": "7 · E-signature — manual send, external provider, async callback, publish",
    "caption": "4.8 · PAY-06/07 · APR-06/07 · D10 manual send · D16's one REST exception · 5.5 separate signing "
               "state machine. Statement path drawn in full; the contract/addendum leg differs and is drawn too.",
    "participants": [
        ("acct", ":Accountant", "actor"),
        ("sales", ":Sales officer", "actor"),
        ("bill", "billing-service", "plain"),
        ("relay", "owner relay", "control"),
        ("ct", "contract-service", "plain"),
        ("es", "esign-service", "plain"),
        ("prov", "MockSign provider", "boundary"),
        ("mq", "Kafka", "entity"),
        ("notif", "notification-service", "plain"),
    ],
    "steps": [
        ("call", "acct", "bill", "POST /payment-statements/{id}/send-for-signature\n{signer_name, signer_email}"),
        ("self", "bill", "assert esign:send ∈ permissions\nassert status = 'APPROVED' (PAY-06)"),
        ("self", "bill", "tx: APPROVED → SIGNING + status_history\n+ outbox('esign.session_requested', payload.idempotency_key)\n+ outbox('audit.recorded')"),
        ("ret", "bill", "acct", "202 {status: SIGNING, session: dispatch pending}"),
        ("note", "bill", "REGISTRY DELTA — reuses D4's dispatch pattern rather than inventing a second one: commit "
                         "locally, then let the relay retry a gRPC call. Calling esign first and committing after would "
                         "leave, on a crash, a session that sends to the provider for a statement still APPROVED — a "
                         "callback for a status §9 has no transition from. One outbox, one pattern, no orphan."),
        ("frame", "opt [same action on a CONTRACT or ADDENDUM (D10)]"),
        ("call", "sales", "ct", "POST /contracts/{id}/send-for-signature\n{signer_name, signer_email}"),
        ("self", "ct", "assert esign:send · assert status = 'APPROVED'\ntx: NO status change (D14e) + outbox('esign.session_requested')\n+ outbox('audit.recorded')"),
        ("ret", "ct", "sales", "202 {session: dispatch pending} — contract status unchanged"),
        ("note", "ct", "The outbox is still right here even though nothing flips: APR-07 requires the user's send action "
                       "to survive esign-service being down. The premise differs from billing's (no status to protect) "
                       "but the conclusion is the same, so contract-service writes the same event_type."),
        ("end",),
        ("frame", "loop [relay retries until OK — §M2]"),
        ("call", "relay", "es", "EsignInternal.CreateSigningSession(document_type, document_id,\ndocument_no, signer_name, signer_email, idempotency_key)"),
        ("self", "es", "INSERT signing_session status = 'PENDING_SEND'\nidempotency_key UNIQUE (permanent, unscoped)\n+ partial unique (document_type, document_id)\n  WHERE status IN ('PENDING_SEND','SIGNING')\n+ status_history"),
        ("ret", "es", "relay", "{session_id, session_no: SIG-8839} → relay stamps published_at"),
        ("note", "es", "Two constraints, two jobs — the same lesson as workflow_instance (D4). The permanent "
                       "idempotency_key is what makes a retry safe AFTER the session reached a terminal status: without "
                       "it, a relay that crashed before stamping published_at would re-dispatch, the status-scoped "
                       "partial index would no longer apply, and a SECOND session would send to the provider and deliver "
                       "a second callback for an already-SIGNED document — back to the orphan this delta prevents. "
                       "The partial index still rejects a genuine concurrent double-send (different key) with "
                       "FAILED_PRECONDITION, which is permanent, not retryable."),
        ("end",),
        ("call", "es", "bill", "BillingInternal.GetSigningPayload(document_id)"),
        ("ret", "bill", "es", "{rendered document, statement_no, customer_name, total_amount}"),
        ("note", "bill", "The guard here is status ∈ {APPROVED, SIGNING} for statements (and APPROVED for "
                         "contract/addendum): billing already moved to SIGNING before dispatching, so a guard of "
                         "APPROVED-only — as D10 originally worded it — would deadlock every send on an unbounded retry."),
        ("call", "es", "prov", "POST /sign {document, callback_url = …/callbacks/esign/{session_no}}"),
        ("frame", "alt [provider accepts]"),
        ("ret", "prov", "es", "202 {provider_ref}"),
        ("self", "es", "tx: PENDING_SEND → SIGNING, attempts + 1, provider_ref\n+ status_history + outbox('audit.recorded')"),
        ("div", "[provider UNAVAILABLE / send fails]"),
        ("self", "es", "tx: stay PENDING_SEND, attempts + 1, last_error\n+ outbox('audit.recorded') · background retry"),
        ("self", "es", "after 3 attempts:\ntx: PENDING_SEND → FAILED + status_history\n+ outbox('esign.session_completed'{FAILED}) + outbox(audit)"),
        ("note", "es", "APR-07: a provider outage never touches the document's own data. Exhaustion is a terminal FAILED "
                       "(§9), not an infinite retry — and it carries a status_history row like every other transition, "
                       "since §9 treats a status change without one as a bug."),
        ("end",),
        ("note", "prov", "Asynchronous gap — minutes to days. The signer acts out of band; nothing in PAS blocks on it."),
        ("call", "prov", "es", "POST /callbacks/esign/{session_no} {provider_ref, result}"),
        ("note", "prov", "D16's single exception: machine-to-machine, but the caller is OUTSIDE the boundary — a real "
                         "provider is handed a callback URL, not a .proto. The URL carries session_no so the session is "
                         "identifiable BEFORE provider_ref is persisted: a provider that calls back faster than the 202 "
                         "handler commits would otherwise be unmatchable, get logged as unknown, and leave the session "
                         "waiting forever for a callback that already happened (SIGNING has no timeout exit in §9)."),
        ("self", "es", "tx: INSERT signing_callback_log (session_no, provider_ref, raw_payload)\nsession_id NULL when neither matches — logged, then ignored"),
        ("frame", "alt [result SIGNED]"),
        ("self", "es", "tx: UPDATE signing_session SET status='SIGNED', version + 1\nWHERE id = ? AND status IN ('PENDING_SEND','SIGNING') AND version = ?\n0 rows ⇒ duplicate or post-terminal callback, no-op\n+ status_history + outbox('esign.session_completed'{SIGNED}) + outbox(audit)"),
        ("note", "es", "PENDING_SEND is accepted, not just SIGNING (db-esign.md) — that is the reordered-webhook case "
                       "above. The version guard is what makes duplicates and late callbacks no-ops."),
        ("ret", "es", "prov", "200"),
        ("async", "mq", "bill", "esign.session_completed {result: SIGNED, document_no, requested_by, signer_name}"),
        ("self", "bill", "tx: SIGNING → SIGNED + status_history + processed_event (§9)"),
        ("async", "mq", "notif", "esign.session_completed → requested_by (\"signature completed\")"),
        ("call", "acct", "bill", "POST /payment-statements/{id}/publish"),
        ("self", "bill", "SIGNED → ISSUED, issued_at, due_date = issued_at + payment_term\n+ status_history + outbox('audit.recorded')"),
        ("ret", "bill", "acct", "200 {status: ISSUED, due_date}"),
        ("div", "[result FAILED (PAY-07)]"),
        ("ret", "es", "prov", "200"),
        ("async", "mq", "bill", "esign.session_completed {result: FAILED, error}"),
        ("self", "bill", "tx: SIGNING → REVISION + status_history + processed_event\nuser is told to fix and resubmit"),
        ("async", "mq", "notif", "esign.session_completed → requested_by (\"e-signature failed\")"),
        ("end",),
        ("frame", "opt [user cancels the session — §9's only route to CANCELLED]"),
        ("call", "acct", "es", "POST /signing-sessions/{id}/cancel {reason}"),
        ("self", "es", "assert esign:cancel ∈ permissions\nassert status ∈ {PENDING_SEND, SIGNING}\ntx: → CANCELLED + status_history\n+ outbox('esign.session_completed'{CANCELLED}) + outbox(audit)"),
        ("async", "mq", "bill", "esign.session_completed {result: CANCELLED}"),
        ("self", "bill", "tx: SIGNING → REVISION + status_history + processed_event (PAY-07)"),
        ("note", "es", "CANCELLED is a USER action in §9, never a provider-reported result — drawing it as a callback "
                       "outcome would attribute the transition to a trigger the table doesn't allow. 4.8 requires "
                       "\"hủy ký\" to be a managed state, so it needs its own path with its own permission."),
        ("end",),
        ("note", "bill", "The two state machines never merge (5.5). A statement no longer in SIGNING (ISSUED, REVISION, "
                         "CANCELLED) that receives a late session_completed is a documented no-op — the guarded UPDATE "
                         "matches nothing. For a CONTRACT, contract-service does not consume this event at all (D14e): "
                         "the frontend composes contract status with GET /signing-sessions/by-document/… for display."),
    ],
}

# ── 8. workflow configuration (admin) ────────────────────────────────────────
DIAGRAMS["seq-08-workflow-configuration"] = {
    "title": "8 · Workflow configuration — new version, activation swap, running instances unaffected",
    "caption": "4.7 \"quy trình phê duyệt không được hard-code\" · UC Quản trị hệ thống → Cấu hình workflow · "
               "permission workflow:configure · db-workflow's definition/instance split. The admin-facing half of seq-03.",
    "participants": [
        ("admin", ":System admin", "actor"),
        ("wf", "workflow-service", "plain"),
        ("db", "workflow schema", "entity"),
        ("appr", ":Approver", "actor"),
        ("ct", "contract-service", "plain"),
    ],
    "steps": [
        ("call", "admin", "wf", "GET /workflow-definitions?document_type=CONTRACT"),
        ("ret", "wf", "admin", "[{version_no: 3, is_active: true, steps: 3}, {version_no: 2, …}]"),
        ("note", "wf", "4.7 in one sentence: the chain is DATA. No service branches on document type to decide who "
                       "approves — the engine reads workflow_step_definition rows, and registry §7's four seed chains "
                       "are rows an admin may replace at runtime. That is the requirement's \"không được hard-code "
                       "theo if/else đơn giản\", and this diagram is where it is actually exercised."),
        ("call", "admin", "wf", "POST /workflow-definitions\n{document_type: CONTRACT, based_on_version_no: 3}"),
        ("self", "wf", "assert workflow:configure ∈ permissions (D11 layer 2, §M1)\ncopy v3's steps into version_no = 4, is_active = false\nUNIQUE (document_type_id, version_no)"),
        ("ret", "wf", "admin", "201 {definition_id, version_no: 4, is_active: false}"),
        ("call", "admin", "wf", "PUT /workflow-definitions/{id}/steps\n[{step_order, name, approver_role, sla_hours}]"),
        ("self", "wf", "assert is_active = false — an ACTIVE version is read-only\nassert ≥ 1 step · step_order contiguous from 1\ntx: replace workflow_step_definition rows + outbox('audit.recorded')"),
        ("note", "wf", "There is no edit-in-place path at all, which is the point: \"change the Legal review step\" is "
                       "always insert-a-version then activate, and Figma's drag-reorder is a step_order rewrite on the "
                       "DRAFT version. That is also what keeps every past approval re-readable — a finished instance's "
                       "definition_id still resolves to the chain that actually ran (db-workflow)."),
        ("call", "admin", "wf", "POST /workflow-definitions/{id}/activate"),
        ("frame", "one transaction — deactivate BEFORE activate"),
        ("call", "wf", "db", "BEGIN"),
        ("call", "wf", "db", "SELECT id, document_type_id, is_active, version_no\nFROM workflow_definition WHERE id = :target FOR UPDATE"),
        ("ret", "db", "wf", "the target row, now locked"),
        ("self", "wf", "assert found — else NOT_FOUND, ROLLBACK\nthe locked target's document_type_id defines the swap scope"),
        ("frame", "alt [target already active — idempotent no-op]"),
        ("call", "wf", "db", "COMMIT — no writes, no duplicate audit record"),
        ("ret", "db", "wf", "ok — target was already active"),
        ("div", "[target inactive — perform the guarded swap]"),
        ("call", "wf", "db", "UPDATE workflow_definition SET is_active = false\nWHERE document_type_id = ? AND is_active     → 0 or 1 rows"),
        ("call", "wf", "db", "UPDATE workflow_definition SET is_active = true\nWHERE id = :target AND NOT is_active     → MUST be 1 row"),
        ("self", "wf", "activated ≠ 1 ⇒ ROLLBACK — never commit a deactivate\nthat is not paired with its activate"),
        ("call", "wf", "db", "INSERT outbox (audit.recorded — before/after version_no, actor)"),
        ("call", "wf", "db", "COMMIT"),
        ("ret", "db", "wf", "ok — exactly one active version, by construction"),
        ("end",),
        ("end",),
        ("note", "db", "Order is load-bearing, the same shape as seq-04's truncate-then-approve: the partial unique "
                       "(document_type_id) WHERE is_active admits exactly one active version per document type, so "
                       "activating first aborts on the constraint and no chain could ever be swapped. But order alone "
                       "is not enough — the guards above are what stop the swap committing HALF done."),
        ("note", "db", "Without them a missing target can go unnoticed, the deactivate can land, and an activate that "
                       "matches nothing can still be followed by COMMIT: a document type with NO active definition, "
                       "every subsequent submit failing ValidateStartable, and no error raised at the moment of "
                       "damage. Hence: lock and validate the target FIRST, use its document_type_id as the swap scope, "
                       "treat an already-active target as an idempotent read-only success, and require an inactive "
                       "target's activate to report exactly 1 row. "
                       "The deactivate is allowed to report 0 — the partial unique already bounds it to ≤ 1, and 0 is "
                       "the legitimate first-activation case for a freshly seeded document type; it is the ACTIVATE "
                       "whose count carries the invariant."),
        ("note", "db", "Concurrency: two admins activating different versions of one document type serialize on the "
                       "same currently-active row in the deactivate, and the loser then hits the partial unique on its "
                       "own activate — ABORTED, nothing half-applied. If no active version exists yet, neither blocks, "
                       "and the partial unique is what rejects the second directly. Either way the failure is a "
                       "rejected request, never a document type left with zero or two active chains."),
        ("ret", "wf", "admin", "200 {version_no: 4, is_active: true}"),
        ("note", "wf", "Activation deliberately does NOT verify that each step's role has an ACTIVE holder. A chain is "
                       "legitimately configured before the people exist, and the check already lives where it can act "
                       "on the answer: ValidateStartable rejects the SUBMIT and names the unfillable role (seq-03). "
                       "Two gates for one fact would just let the config screen and the submit screen disagree."),
        ("frame", "opt [an approval already running under v3 — unaffected]"),
        ("call", "appr", "wf", "POST /workflow-steps/{step_instance_id}/actions\n{action: APPROVE, comment}"),
        ("self", "wf", "reads workflow_step_instance + step_assignee — both written at\ninstance creation, and workflow_instance.definition_id still pins v3\nnothing on the action path reads workflow_step_definition at all"),
        ("ret", "wf", "appr", "200 {step: APPROVED, next_step: Legal review} — v3's chain, to the end"),
        ("note", "appr", "This is the whole reason definitions are versioned instead of edited. A chain mutating under a "
                         "running approval would change \"các bước còn lại\" (4.7) mid-flight, could strand an ACTIVE "
                         "step whose role left the chain, and would undermine APR-02's \"no re-approving a completed "
                         "step\" — the step numbers themselves would have moved underneath it."),
        ("end",),
        ("call", "ct", "wf", "WorkflowInternal.StartInstance(CONTRACT, …, idempotency_key)\nthe first submit after activation (seq-03)"),
        ("self", "wf", "resolve is_active ⇒ v4 · pin definition_id = v4\nsnapshot v4's steps + assignees into the new instance"),
        ("ret", "wf", "ct", "OK {instance_id} — new instances get v4, in-flight ones keep v3"),
        ("note", "ct", "The cutover is per instance and needs no migration: which chain a document follows is fixed at "
                       "the moment its instance is created and never re-read. \"Cấu hình loại hồ sơ\", the other "
                       "workflow-service admin UC, is ordinary CRUD on document_type_config (esign_enabled and friends, "
                       "D10) with this same assert-permission / write / outbox('audit.recorded') shape, so it is not "
                       "drawn separately."),
    ],
}


# ───────────────────────────────────────────────────────────────── rendering

def validate(name, spec):
    """Reject the defect classes that are invisible in XML but wrong in the picture."""
    ids = [p[0] for p in spec["participants"]]
    assert len(ids) == len(set(ids)), f"{name}: duplicate participant id"
    depth, seen = 0, set()
    for s in spec["steps"]:
        k = s[0]
        if k == "frame":
            depth += 1
            assert len(s[1]) <= 62, (f"{name}: frame label is {len(s[1])} chars, max 62 — it would wrap out of "
                                     f"the tab. Shorten it and put the detail in a note: {s[1]!r}")
        elif k == "end":
            depth -= 1
            assert depth >= 0, f"{name}: 'end' without 'frame'"
        elif k == "div":
            assert depth > 0, f"{name}: 'div' outside a frame"
            assert len(s[1]) <= 62, (f"{name}: div label is {len(s[1])} chars, max 62: {s[1]!r}")
        elif k == "note":
            assert s[1] is None or s[1] in ids, f"{name}: note anchored to unknown '{s[1]}'"
        elif k == "self":
            assert s[1] in ids, f"{name}: self-call on unknown participant '{s[1]}'"
        elif k in ARROW:
            src, dst = s[1], s[2]
            assert src in ids, f"{name}: unknown source '{src}'"
            assert dst in ids, f"{name}: unknown target '{dst}'"
            # a same-lifeline arrow renders as a zero-length line — must be a "self" step
            assert src != dst, f"{name}: '{k}' from '{src}' to itself — use ('self', …)"
            seen.update((src, dst))
        else:
            raise AssertionError(f"{name}: unknown step kind '{k}'")
    assert depth == 0, f"{name}: {depth} unclosed frame(s)"
    unused = [i for i in ids if i not in seen]
    assert not unused, f"{name}: participant(s) never exchange a message: {unused}"


def build(name, spec):
    parts = spec["participants"]
    centre = {pid: X0 + i * GAP for i, (pid, _, _) in enumerate(parts)}

    cells, bars, notes, frames = [], [], [], []
    open_calls = {}                 # pid -> [y, ...]
    frame_stack = []
    y = Y0 + HDR_H + 34
    note_cursor = Y0 + HDR_H + 10   # notes stack in the gutter on their own cursor
    n = 0

    def note_height(text):
        return max(46, 18 + 14 * (len(text) // 44 + 1))

    for step in spec["steps"]:
        kind = step[0]

        if kind == "frame":
            y += 12                                  # breathing room above the tab
            frame_stack.append({"label": step[1], "y0": y, "divs": []})
            y += 36                                  # tab band + clearance for the first
                                                     # message's label, which sits above it
            continue
        if kind == "div":
            if frame_stack:
                frame_stack[-1]["divs"].append((y - 14, step[1]))
            y += 28
            continue
        if kind == "end":
            if frame_stack:
                f = frame_stack.pop()
                f["depth"] = len(frame_stack)
                # y1 IS the drawn bottom edge (see the rect below), so the next element
                # can simply start below it — the old code advanced y by less than the
                # rect's own height, which made consecutive frames overlap by 14px.
                f["y1"] = y + 2 + f["depth"] * 10
                frames.append(f)
                y = f["y1"] + 40          # clear visual separation, not a hairline
            continue
        if kind == "note":
            text = step[2]
            h = note_height(text)
            ny = max(y - 8, note_cursor)             # never on top of the previous note
            notes.append((ny, text, h))
            note_cursor = ny + h + 10
            # Reserve only a fraction of the note's height in the message flow: a note
            # lives in the right gutter, so charging its full height to the sequence
            # opened huge blank bands inside frames. The cursor above is what actually
            # prevents notes colliding.
            y += max(12, h // 3)
            continue

        n += 1
        # Reserve room for every extra label line, so a 3-line label cannot ride up into
        # the frame tab or the message above it.
        extra = (str(step[-1]).count("\n")) * LINE_H
        y += extra

        if kind == "self":
            who, label = step[1], step[2]
            cx = centre[who]
            eid = f"m{n}"
            cells.append(
                f'<mxCell id="{eid}" value="{esc(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;'
                f'align=left;spacingLeft=8;verticalAlign=middle;endArrow=block;endFill=1;fontSize=11;{LABEL_BG}" '
                f'edge="1" parent="1">'
                f'<mxGeometry relative="1" as="geometry">'
                f'<Array as="points"><mxPoint x="{cx + 46}" y="{y}" /><mxPoint x="{cx + 46}" y="{y + 24}" /></Array>'
                f'<mxPoint x="{cx + 5}" y="{y}" as="sourcePoint" />'
                f'<mxPoint x="{cx + 5}" y="{y + 24}" as="targetPoint" />'
                f'</mxGeometry></mxCell>')
            bars.append((cx, y, y + 24))
            y += STEP + 14
            continue

        src, dst, label = step[1], step[2], step[3]
        x1, x2 = centre[src], centre[dst]
        d = 5 if x2 > x1 else -5
        eid = f"m{n}"
        cells.append(
            f'<mxCell id="{eid}" value="{esc(label)}" style="html=1;verticalAlign=bottom;align=center;'
            f'rounded=0;curved=0;fontSize=11;{LABEL_BG}{ARROW[kind]}" edge="1" parent="1">'
            f'<mxGeometry relative="1" as="geometry">'
            f'<mxPoint x="{x1 + d}" y="{y}" as="sourcePoint" />'
            f'<mxPoint x="{x2 - d}" y="{y}" as="targetPoint" />'
            f'</mxGeometry></mxCell>')

        if kind == "call":
            open_calls.setdefault(dst, []).append(y)
        elif kind == "ret":
            stack = open_calls.get(src)
            if stack:
                bars.append((centre[src], stack.pop(), y))
        y += STEP

    bottom = max(y, note_cursor) + 30
    width = X0 + (len(parts) - 1) * GAP + 120 + GUTTER
    note_x = X0 + (len(parts) - 1) * GAP + 110

    head = [
        f'<mxCell id="title" value="{esc("PAS · sequence · " + spec["title"])}" '
        'style="text;html=1;fontSize=17;fontStyle=1;align=left;" vertex="1" parent="1">'
        '<mxGeometry x="40" y="18" width="1100" height="26" as="geometry" /></mxCell>',
        f'<mxCell id="caption" value="{esc(spec["caption"])}" '
        'style="text;html=1;fontSize=11;align=left;fontColor=#777777;whiteSpace=wrap;" vertex="1" parent="1">'
        f'<mxGeometry x="40" y="44" width="{width - 120}" height="32" as="geometry" /></mxCell>',
        f'<mxCell id="legend" value="{esc(LEGEND)}" '
        'style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fafafa;strokeColor=#cccccc;align=left;'
        'verticalAlign=top;fontSize=10;fontColor=#666666;spacing=8;arcSize=4;" vertex="1" parent="1">'
        f'<mxGeometry x="40" y="{bottom + 20}" width="{min(width - 80, 1180)}" height="72" as="geometry" /></mxCell>',
    ]

    # lifelines — icon participants get their name in a separate text cell above the head,
    # because verticalLabelPosition=bottom would drop it to the foot of the lifeline.
    life = []
    for pid, label, knd in parts:
        w = KIND_W[knd]
        p = KIND_PARTICIPANT[knd]
        style = ("shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;"
                 "dropTarget=0;collapsible=0;recursiveResize=0;outlineConnect=0;portConstraint=eastwest;"
                 "fontSize=12;fontStyle=1;")
        head_label = label
        if p:
            style += f"participant={p};verticalAlign=top;"
            head_label = ""
            life.append(
                f'<mxCell id="nm_{pid}" value="{esc(label)}" '
                'style="text;html=1;align=center;verticalAlign=middle;fontSize=12;fontStyle=1;'
                'whiteSpace=wrap;" vertex="1" parent="1">'
                f'<mxGeometry x="{centre[pid] - 90}" y="{NAME_Y}" width="180" height="26" as="geometry" /></mxCell>')
        else:
            style += "fillColor=#f5f9ff;strokeColor=#6c8ebf;"
        life.append(
            f'<mxCell id="lf_{pid}" value="{esc(head_label)}" style="{style}" vertex="1" parent="1">'
            f'<mxGeometry x="{centre[pid] - w // 2}" y="{Y0}" width="{w}" '
            f'height="{bottom - Y0}" as="geometry" /></mxCell>')

    # activation bars
    barcells = []
    for i, (cx, ya, yb) in enumerate(bars):
        barcells.append(
            f'<mxCell id="ab{i}" value="" style="html=1;points=[[0,0,0,0,5],[0,1,0,0,-5],[1,0,0,0,5],'
            f'[1,1,0,0,-5]];perimeter=orthogonalPerimeter;outlineConnect=0;targetShapes=umlLifeline;'
            f'portConstraint=eastwest;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1">'
            f'<mxGeometry x="{cx - 5}" y="{ya}" width="10" height="{max(20, yb - ya)}" as="geometry" /></mxCell>')

    # fragments
    fcells = []
    for i, f in enumerate(frames):
        fx = X0 - 60 - f["depth"] * 10
        fw = (len(parts) - 1) * GAP + 130 + f["depth"] * 20
        # Size the label tab to the text. A fixed tab makes any label that wraps overflow,
        # and the frame's own top border then strikes through it.
        tab_w = min(max(int(len(f["label"]) * 6.2) + 26, 150), 440)
        fcells.append(
            f'<mxCell id="fr{i}" value="{esc(f["label"])}" style="shape=umlFrame;whiteSpace=wrap;html=1;'
            f'pointerEvents=0;fontSize=11;fontStyle=2;width={tab_w};height=26;strokeColor=#9673a6;'
            f'fillColor=none;verticalAlign=top;align=left;spacingLeft=4;" vertex="1" parent="1">'
            f'<mxGeometry x="{fx}" y="{f["y0"] - 24}" width="{fw}" '
            f'height="{f["y1"] - f["y0"] + 24}" as="geometry" /></mxCell>')
        for j, (dy, dlabel) in enumerate(f["divs"]):
            fcells.append(
                f'<mxCell id="fr{i}d{j}" value="{esc(dlabel)}" style="html=1;dashed=1;strokeColor=#9673a6;'
                f'endArrow=none;align=left;verticalAlign=bottom;spacingLeft=6;fontSize=10;fontStyle=2;'
                f'fontColor=#6a3d8f;{LABEL_BG}" edge="1" parent="1"><mxGeometry relative="1" as="geometry">'
                f'<mxPoint x="{fx}" y="{dy}" as="sourcePoint" />'
                f'<mxPoint x="{fx + fw}" y="{dy}" as="targetPoint" /></mxGeometry></mxCell>')

    # notes
    ncells = []
    for i, (ny, text, h) in enumerate(notes):
        ncells.append(
            f'<mxCell id="nt{i}" value="{esc(text)}" style="shape=note;whiteSpace=wrap;html=1;size=14;'
            f'fillColor=#fff9c4;strokeColor=#e0b400;align=left;verticalAlign=top;fontSize=10;spacing=6;" '
            f'vertex="1" parent="1">'
            f'<mxGeometry x="{note_x}" y="{ny}" width="{NOTE_W}" height="{h}" as="geometry" /></mxCell>')

    # Geometry self-check: sibling frames must not overlap, and notes must not overlap.
    # Both are invisible in the XML and only show up when the picture is opened.
    sib = sorted(((f["y0"] - 24, f["y1"], f["depth"], f["label"]) for f in frames))
    for (t1, b1, d1, l1), (t2, b2, d2, l2) in zip(sib, sib[1:]):
        if d1 == d2 and t2 < b1:
            raise AssertionError(f"{name}: frames overlap by {b1 - t2}px — {l1!r} then {l2!r}")
    for (ny1, _, h1), (ny2, _, _) in zip(notes, notes[1:]):
        if ny2 < ny1 + h1:
            raise AssertionError(f"{name}: notes overlap by {ny1 + h1 - ny2}px at y={ny2}")

    inner = "\n        ".join(head + life + fcells + barcells + cells + ncells)
    return f'''<mxfile host="app.diagrams.net">
  <diagram id="{name}" name="{name}">
    <mxGraphModel dx="1400" dy="900" grid="0" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="{width}" pageHeight="{bottom + 120}" math="0" shadow="0">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />
        {inner}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
'''


def esc(text):
    """Encode for two layers: draw.io parses the XML attribute, then renders the result
    as HTML (html=1). A literal "<same predicate>" survives the XML parse and is then
    swallowed by the HTML renderer as an unknown tag, so angle brackets must be
    entity-escaped for the HTML layer *before* the XML layer escapes them again.
    Specs therefore carry plain text — never hand-written &lt;/&gt; entities."""
    s = str(text).replace("&", "&" + "amp;").replace("<", "&" + "lt;").replace(">", "&" + "gt;")
    s = s.replace("\n", "<br>")               # a real tag, for the HTML layer
    return escape(s, {'"': "&quot;"})         # XML layer


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, spec in DIAGRAMS.items():
        validate(name, spec)
        xml_text = build(name, spec)
        xml.dom.minidom.parseString(xml_text)          # well-formedness gate
        path = os.path.join(OUT_DIR, f"{name}.drawio")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(xml_text)
        msgs = sum(1 for s in spec["steps"] if s[0] in ("call", "ret", "async", "self"))
        print(f"wrote {os.path.relpath(path)}  ({len(spec['participants'])} participants, {msgs} messages)")


if __name__ == "__main__":
    main()
