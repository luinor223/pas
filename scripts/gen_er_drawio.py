#!/usr/bin/env python3
"""Generate draw.io ER diagrams for the PAS service DB schemas (design phase 2).

Usage:  python3 scripts/gen_er_drawio.py
Writes: docs/design/db/db-<service>.drawio (one file per service schema)

Table/column conventions come from docs/design/00-registry.md (§3 enums, §6 columns).
Edit the SPECS below and regenerate instead of editing the XML by hand.

Row markers: pk = primary key (bold) · fk = in-schema FK · ref = cross-service
opaque UUID reference (D7, grey arrow prefix) · note = constraint/index annotation.
"""
import os
import xml.dom.minidom
from xml.sax.saxutils import escape

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "design", "db")
ROW_H, HDR_H, W = 20, 28, 280

NEUTRAL = ("#f5f5f5", "#666666")
GHOST = ("#fafafa", "#999999")

# ---------------------------------------------------------------- shared rows
STD = ("+ std cols §6 (created_at/by, updated_at/by)", "note")

AUDIT_LOG = ("audit_log", [
    ("id: uuid", "pk"),
    ("entity_type: text", "col"), ("entity_id: uuid", "col"), ("entity_no: text", "col"),
    ("action: text (snake_case verb)", "col"),
    ("actor_id: uuid NULL (NULL = system)", "col"), ("actor_name: text", "col"),
    ("actor_department: text NULL", "col"),
    ("before_status: text NULL", "col"), ("after_status: text NULL", "col"),
    ("changes: jsonb NULL", "col"), ("note: text NULL", "col"),
    ("ip_address: text NULL", "col"), ("created_at: timestamptz", "col"),
    ("written in-transaction with the change (§4.10)", "note"),
])

OUTBOX = ("outbox", [
    ("id: uuid", "pk"), ("event_type: text", "col"), ("payload: jsonb", "col"),
    ("created_at: timestamptz", "col"), ("published_at: timestamptz NULL", "col"),
    ("retry_count: int = 0", "col"),
    ("publisher polls WHERE published_at IS NULL (D6)", "note"),
])

PROCESSED = ("processed_event", [
    ("event_id: uuid", "pk"), ("processed_at: timestamptz", "col"),
    ("consumer idempotency (D6)", "note"),
])

# ---------------------------------------------------------------- specs
SPECS = {
    "identity": {
        "colors": ("#dae8fc", "#6c8ebf"),
        "note": "identity-service · schema `identity` · JWT issue; token blacklist lives in Redis (no table). No outbox: emits no events.",
        "tables": [
            ("department", 40, 140, [
                ("id: uuid", "pk"), ("code: text UNIQUE (§7)", "col"), ("name: text", "col"),
            ]),
            ("app_user", 400, 140, [
                ("id: uuid", "pk"),
                ("username: text UNIQUE", "col"), ("password_hash: text", "col"),
                ("full_name: text", "col"), ("email: text UNIQUE", "col"),
                ("department_id → department", "fk"),
                ("status: text ACTIVE|DISABLED", "col"),
                ("last_login_at: timestamptz NULL", "col"),
                STD,
            ]),
            ("role", 40, 300, [
                ("id: uuid", "pk"), ("code: text UNIQUE (§7)", "col"), ("name: text", "col"),
            ]),
            ("user_role", 400, 400, [
                ("user_id → app_user", "fk"), ("role_id → role", "fk"),
                ("PK (user_id, role_id)", "note"),
            ]),
            ("permission", 40, 460, [
                ("id: uuid", "pk"), ("code: text UNIQUE (§10 seeds)", "col"), ("description: text", "col"),
            ]),
            ("role_permission", 400, 530, [
                ("role_id → role", "fk"), ("permission_id → permission", "fk"),
                ("PK (role_id, permission_id)", "note"),
            ]),
            (AUDIT_LOG[0], 760, 140, AUDIT_LOG[1]),
        ],
        "edges": [
            ("department", "app_user"), ("app_user", "user_role"), ("role", "user_role"),
            ("role", "role_permission"), ("permission", "role_permission"),
        ],
        "ghosts": [],
    },

    "contract": {
        "colors": ("#d5e8d4", "#82b366"),
        "note": ("contract-service · schema `contract` · owns customers + contracts + addenda (merged: same owner dept). "
                 "Scheduler: D14d status flips + D9 `document.expiring` (direct publish, no outbox). "
                 "Consumes workflow.completed / esign.session_completed."),
        "tables": [
            ("customer", 40, 140, [
                ("id: uuid", "pk"),
                ("code: text UNIQUE (CUS-seq)", "col"),
                ("name: text", "col"), ("short_name: text NULL", "col"),
                ("tax_code: text", "col"), ("address: text", "col"),
                ("representative_name: text", "col"), ("representative_position: text NULL", "col"),
                ("segment: text NULL (Figma)", "col"),
                ("status: text ACTIVE|SUSPENDED (4.1)", "col"),
                STD,
            ]),
            ("customer_contact", 40, 460, [
                ("id: uuid", "pk"), ("customer_id → customer", "fk"),
                ("full_name: text", "col"), ("title: text NULL", "col"),
                ("email: text NULL", "col"), ("phone: text NULL", "col"),
                ("is_primary: bool = false", "col"),
            ]),
            ("contract", 400, 140, [
                ("id: uuid", "pk"),
                ("contract_no: text UNIQUE (CTR-YYYY-seq)", "col"),
                ("customer_id → customer", "fk"),
                ("description: text NULL", "col"),
                ("service_group: text (§10)", "col"),
                ("value: numeric(18,2)", "col"), ("currency: text = 'VND'", "col"),
                ("valid_from: date", "col"), ("valid_to: date", "col"),
                ("auto_renewal: bool = false (display only, D14b)", "col"),
                ("payment_term: text", "col"),
                ("billing_cycle: text = 'MONTHLY' (§10)", "col"),
                ("vat_rate: numeric(5,2)", "col"),
                ("penalty_terms: text NULL", "col"),
                ("service_clause: text NULL", "col"),
                ("status: text CHECK (§3)", "col"),
                ("version: int (optimistic lock, CTR-01 edits)", "col"),
                ("CHECK valid_from <= valid_to (CTR-02)", "note"),
                STD,
            ]),
            ("addendum", 770, 140, [
                ("id: uuid", "pk"),
                ("addendum_no: text UNIQUE (ADD-YYYY-seq)", "col"),
                ("contract_id → contract", "fk"),
                ("change_type: text (§10)", "col"),
                ("description: text", "col"),
                ("effective_from: date", "col"),
                ("new_valid_to: date NULL (TERM_EXTENSION = renewal, D14b)", "col"),
                ("payment_term_override: text NULL", "col"),
                ("status: text CHECK (§3, same enum as contract)", "col"),
                ("version: int", "col"),
                STD,
            ]),
            ("attachment", 770, 480, [
                ("id: uuid", "pk"),
                ("owner_type: text CONTRACT|ADDENDUM", "col"),
                ("owner_id: uuid", "col"),
                ("file_name: text", "col"), ("content_type: text", "col"),
                ("size_bytes: bigint", "col"),
                ("storage_path: text (mounted volume, plan 1.2)", "col"),
                ("uploaded_by: uuid / uploaded_at", "col"),
                ("≥1 required to submit (CTR-02, app check)", "note"),
            ]),
            (PROCESSED[0], 400, 640, PROCESSED[1]),
            (AUDIT_LOG[0], 1140, 140, AUDIT_LOG[1]),
        ],
        "edges": [
            ("customer", "customer_contact"), ("customer", "contract"), ("contract", "addendum"),
        ],
        "cross_edges": [("contract", "attachment", "polymorphic (owner_type + owner_id)"),
                        ("addendum", "attachment", "")],
        "ghosts": [],
    },

    "pricing": {
        "colors": ("#ffe6cc", "#d79b00"),
        "note": ("pricing-service · schema `pricing` · owns the service catalog + versioned price lists. "
                 "Scheduler: D14d flips + D9 expiring. Consumes workflow.completed. "
                 "PRC-03 via EXCLUDE USING gist (needs btree_gist extension)."),
        "tables": [
            ("service_item", 40, 140, [
                ("id: uuid", "pk"),
                ("code: text UNIQUE (referenced cross-service)", "col"),
                ("name: text", "col"),
                ("unit: text (§10: TEU|tonne|trip|day|set)", "col"),
                ("service_group: text (§10)", "col"),
                ("is_active: bool = true", "col"),
            ]),
            ("price_list", 400, 140, [
                ("id: uuid", "pk"),
                ("price_list_no: text UNIQUE (PRC-YYYY-seq)", "col"),
                ("name: text", "col"),
                ("customer_id: uuid NULL ⇢ contract.customer", "ref"),
                ("customer_name: text NULL (snapshot)", "col"),
                ("contract_id: uuid NULL ⇢ contract.contract", "ref"),
                ("service_group: text NULL (§10)", "col"),
                ("scope_key: text (derived, e.g. CONTRACT:<id>)", "col"),
                ("CHECK ≥1 scope field set (PRC-01)", "note"),
                ("scope frozen once a version exists (key desync guard)", "note"),
                STD,
            ]),
            ("price_list_version", 770, 140, [
                ("id: uuid", "pk"),
                ("price_list_id → price_list", "fk"),
                ("version_no: int", "col"),
                ("valid_from: date / valid_to: date", "col"),
                ("status: text CHECK (§3)", "col"),
                ("scope_key: text (denormalized from list)", "col"),
                ("addendum_id: uuid NULL ⇢ contract.addendum (D8)", "ref"),
                ("UNIQUE (price_list_id, version_no)", "note"),
                ("CHECK valid_from <= valid_to (PRC-02)", "note"),
                ("EXCLUDE gist (scope_key =, daterange &&)", "note"),
                ("  WHERE status IN (APPROVED, EFFECTIVE)  (PRC-03)", "note"),
                ("on approve: overlapping predecessor truncated", "note"),
                ("  valid_to = successor.valid_from − 1  (§9³, PRC-04)", "note"),
                STD,
            ]),
            ("price_line", 1140, 140, [
                ("id: uuid", "pk"),
                ("version_id → price_list_version", "fk"),
                ("service_item_id → service_item", "fk"),
                ("unit_price: numeric(18,2) CHECK >= 0", "col"),
                ("currency: text = 'VND'", "col"),
                ("UNIQUE (version_id, service_item_id)", "note"),
            ]),
            (PROCESSED[0], 400, 560, PROCESSED[1]),
            (AUDIT_LOG[0], 770, 560, AUDIT_LOG[1]),
        ],
        "edges": [
            ("price_list", "price_list_version"), ("price_list_version", "price_line"),
            ("service_item", "price_line"),
        ],
        "ghosts": [
            ("contract.customer", 400, 460), ("contract.contract", 590, 460), ("contract.addendum", 1140, 460),
        ],
        "ghost_edges": [
            ("price_list", "contract.customer"), ("price_list", "contract.contract"),
            ("price_list_version", "contract.addendum"),
        ],
    },

    "operations": {
        "colors": ("#e1d5e7", "#9673a6"),
        "note": ("operations-service · schema `operations` · volumes + period locking (4.5). "
                 "Emits operations.period_locked (direct publish, informational). No processed_event: consumes nothing. "
                 "Billing reads volumes sync; only LOCKED periods are billable (PAY-02)."),
        "tables": [
            ("operation_period", 40, 140, [
                ("id: uuid", "pk"),
                ("period_code: text UNIQUE ('YYYY-MM')", "col"),
                ("start_date: date / end_date: date", "col"),
                ("status: text OPEN|LOCKED (§3)", "col"),
                ("locked_by: uuid NULL / locked_by_name: text", "col"),
                ("locked_at: timestamptz NULL", "col"),
                ("no unlock; post-lock edits need", "note"),
                ("  permission volume.edit_locked + audit (4.5)", "note"),
            ]),
            ("volume_record", 400, 140, [
                ("id: uuid", "pk"),
                ("record_no: text UNIQUE (VOL-YYYY-seq)", "col"),
                ("period_id → operation_period", "fk"),
                ("contract_id: uuid ⇢ contract.contract", "ref"),
                ("customer_id: uuid ⇢ contract.customer", "ref"),
                ("customer_name: text (snapshot, Figma list)", "col"),
                ("service_code: text ⇢ pricing.service_item.code", "ref"),
                ("service_name: text / unit: text (snapshots)", "col"),
                ("quantity: numeric(18,3) CHECK >= 0", "col"),
                ("note: text NULL", "col"),
                ("created_by = recorded-by (Figma)", "note"),
                STD,
            ]),
            (AUDIT_LOG[0], 770, 140, AUDIT_LOG[1]),
        ],
        "edges": [("operation_period", "volume_record")],
        "ghosts": [
            ("contract.contract", 400, 520), ("contract.customer", 590, 520), ("pricing.service_item", 780, 520),
        ],
        "ghost_edges": [
            ("volume_record", "contract.contract"), ("volume_record", "contract.customer"),
            ("volume_record", "pricing.service_item"),
        ],
    },

    "workflow": {
        "colors": ("#fff2cc", "#d6b656"),
        "note": ("workflow-service · schema `workflow` · generic configurable approval engine (4.7, APR-01..07). "
                 "Sequential steps only; e-sign is NOT a step (D10). Definitions are versioned; in-flight instances pin theirs. "
                 "Scheduler: workflow.step_overdue vs sla_hours."),
        "tables": [
            ("document_type_config", 40, 140, [
                ("id: uuid", "pk"),
                ("code: text UNIQUE (§2 enum)", "col"),
                ("name: text", "col"),
                ("number_prefix: text (CTR|ADD|PRC|PMT)", "col"),
                ("esign_enabled: bool (D10)", "col"),
                ("esign_provider: text NULL (Figma: 'MockSign')", "col"),
            ]),
            ("workflow_definition", 40, 320, [
                ("id: uuid", "pk"),
                ("document_type_id → document_type_config", "fk"),
                ("version_no: int", "col"),
                ("name: text", "col"),
                ("is_active: bool", "col"),
                ("partial UNIQUE (document_type_id) WHERE is_active", "note"),
                ("UNIQUE (document_type_id, version_no)", "note"),
                ("created_at / created_by", "col"),
            ]),
            ("workflow_step_definition", 40, 540, [
                ("id: uuid", "pk"),
                ("definition_id → workflow_definition", "fk"),
                ("step_order: int", "col"),
                ("name: text (e.g. 'Legal review')", "col"),
                ("approver_role: text (§7)", "col"),
                ("sla_hours: int NULL (Figma)", "col"),
                ("condition_expr: text NULL (§7 mini-DSL)", "col"),
                ("UNIQUE (definition_id, step_order)", "note"),
            ]),
            ("workflow_instance", 440, 140, [
                ("id: uuid", "pk"),
                ("definition_id → workflow_definition (pinned)", "fk"),
                ("document_type_code: text (§2)", "col"),
                ("document_id: uuid ⇢ owner service", "ref"),
                ("document_no: text (snapshot)", "col"),
                ("customer_name: text NULL (snapshot)", "col"),
                ("document_value: numeric(18,2) NULL (conditions)", "col"),
                ("priority: text = 'NORMAL' (§3)", "col"),
                ("status: text CHECK (§3)", "col"),
                ("current_step_order: int NULL", "col"),
                ("requested_by: uuid / requested_by_name: text", "col"),
                ("created_at / completed_at NULL", "col"),
                ("partial UNIQUE (document_type_code, document_id)", "note"),
                ("  WHERE status = 'IN_PROGRESS'  (D4 double-submit)", "note"),
            ]),
            ("workflow_step_instance", 440, 520, [
                ("id: uuid", "pk"),
                ("instance_id → workflow_instance", "fk"),
                ("step_order: int", "col"),
                ("name: text / approver_role: text (snapshots)", "col"),
                ("sla_hours: int NULL (snapshot)", "col"),
                ("status: text CHECK (§3 step enum)", "col"),
                ("version: int (D5 optimistic lock, APR-02)", "col"),
                ("activated_at / completed_at NULL", "col"),
                ("overdue_notified_at: timestamptz NULL (emit once)", "col"),
                ("acted_by: uuid NULL / acted_by_name: text", "col"),
                ("UNIQUE (instance_id, step_order)", "note"),
            ]),
            ("step_assignee", 840, 520, [
                ("id: uuid", "pk"),
                ("step_instance_id → workflow_step_instance", "fk"),
                ("user_id: uuid ⇢ identity.app_user", "ref"),
                ("user_name: text (snapshot at activation)", "col"),
                ("resolved role → users at activation (APR-01)", "note"),
            ]),
            ("workflow_action", 840, 700, [
                ("id: uuid", "pk"),
                ("step_instance_id → workflow_step_instance", "fk"),
                ("action: text APPROVE|REJECT|REQUEST_REVISION", "col"),
                ("actor_id: uuid / actor_name: text", "col"),
                ("comment: text", "col"),
                ("created_at: timestamptz", "col"),
                ("CHECK action = 'APPROVE'", "note"),
                ("  OR (comment IS NOT NULL AND comment <> '')  (APR-03)", "note"),
            ]),
            (OUTBOX[0], 840, 140, OUTBOX[1]),
            (AUDIT_LOG[0], 1180, 140, AUDIT_LOG[1]),
        ],
        "edges": [
            ("document_type_config", "workflow_definition"),
            ("workflow_definition", "workflow_step_definition"),
            ("workflow_definition", "workflow_instance"),
            ("workflow_instance", "workflow_step_instance"),
            ("workflow_step_instance", "step_assignee"),
            ("workflow_step_instance", "workflow_action"),
        ],
        "ghosts": [("identity.app_user", 1180, 560)],
        "ghost_edges": [("step_assignee", "identity.app_user")],
    },

    "billing": {
        "colors": ("#f8cecc", "#b85450"),
        "note": ("billing-service · schema `billing` · statements built from contract + effective price version + locked volumes "
                 "(sync pulls, §5). All price/party fields are snapshots (PAY-03, D7). "
                 "Consumes workflow.completed + esign.session_completed. No outbox: emits nothing."),
        "tables": [
            ("payment_statement", 40, 140, [
                ("id: uuid", "pk"),
                ("statement_no: text UNIQUE (PMT-YYYY-seq)", "col"),
                ("contract_id: uuid ⇢ contract.contract", "ref"),
                ("contract_no: text (snapshot)", "col"),
                ("customer_id: uuid ⇢ contract.customer", "ref"),
                ("customer_name: text (snapshot)", "col"),
                ("period_code: text ⇢ operations.operation_period", "ref"),
                ("period_start / period_end: date (snapshots)", "col"),
                ("price_list_version_id: uuid ⇢ pricing", "ref"),
                ("price_list_no: text / version_no: int (snapshots)", "col"),
                ("payment_term: text (snapshot, due-date calc)", "col"),
                ("vat_rate: numeric(5,2) (snapshot from contract)", "col"),
                ("subtotal / tax_amount: numeric(18,2)", "col"),
                ("total_amount: numeric(18,2) CHECK >= 0 (PAY-04)", "col"),
                ("currency: text = 'VND'", "col"),
                ("status: text CHECK (§3)", "col"),
                ("adjusts_statement_id → payment_statement NULL", "fk"),
                ("  (adjustment doc, PAY-05)", "note"),
                ("reconciled_at / reconciled_by NULL", "col"),
                ("issued_at NULL / due_date: date NULL", "col"),
                ("version: int (controlled edits, 4.6)", "col"),
                STD,
            ]),
            ("statement_line", 460, 140, [
                ("id: uuid", "pk"),
                ("statement_id → payment_statement", "fk"),
                ("line_no: int", "col"),
                ("service_code: text ⇢ pricing.service_item.code", "ref"),
                ("service_name: text / unit: text (snapshots)", "col"),
                ("unit_price: numeric(18,2) (snapshot, PAY-03)", "col"),
                ("quantity: numeric(18,3)", "col"),
                ("amount: numeric(18,2)", "col"),
                ("source: text CALCULATED|MANUAL (§10)", "col"),
                ("note: text NULL", "col"),
                ("UNIQUE (statement_id, line_no)", "note"),
            ]),
            ("statement_line_volume", 460, 480, [
                ("id: uuid", "pk"),
                ("line_id → statement_line", "fk"),
                ("volume_record_id: uuid ⇢ operations", "ref"),
                ("record_no: text (snapshot)", "col"),
                ("quantity: numeric(18,3) (snapshot)", "col"),
                ("'Source volumes' tab traceability (PAY-02)", "note"),
            ]),
            (PROCESSED[0], 460, 680, PROCESSED[1]),
            (AUDIT_LOG[0], 860, 140, AUDIT_LOG[1]),
        ],
        "edges": [
            ("payment_statement", "statement_line"), ("statement_line", "statement_line_volume"),
        ],
        "ghosts": [
            ("contract.contract", 860, 560), ("contract.customer", 1050, 560),
            ("operations.operation_period", 860, 660), ("operations.volume_record", 1050, 660),
            ("pricing.price_list_version", 860, 760),
        ],
        "ghost_edges": [
            ("payment_statement", "contract.contract"), ("payment_statement", "contract.customer"),
            ("payment_statement", "operations.operation_period"),
            ("payment_statement", "pricing.price_list_version"),
            ("statement_line_volume", "operations.volume_record"),
        ],
    },

    "esign": {
        "colors": ("#d6eaf8", "#5499c7"),
        "note": ("esign-service · schema `esign` · thin adapter to the external mock provider (4.8). "
                 "Generic over (document_type, document_id) for CONTRACT | ADDENDUM | PAYMENT_STATEMENT (D10). "
                 "Callback races guarded by version; emits esign.session_completed via outbox (D6)."),
        "tables": [
            ("signing_session", 40, 140, [
                ("id: uuid", "pk"),
                ("session_no: text UNIQUE (SIG-seq)", "col"),
                ("document_type_code: text (§2)", "col"),
                ("document_id: uuid ⇢ owner service", "ref"),
                ("document_no: text (snapshot)", "col"),
                ("customer_name: text NULL (snapshot)", "col"),
                ("signer_name: text / signer_email: text", "col"),
                ("provider: text = 'MockSign'", "col"),
                ("provider_ref: text NULL (provider's id)", "col"),
                ("status: text CHECK (§3 SIGNING_SESSION)", "col"),
                ("attempts: int = 0 (Figma)", "col"),
                ("last_error: text NULL", "col"),
                ("requested_by: uuid / requested_by_name: text", "col"),
                ("version: int (callback race guard)", "col"),
                ("sent_at / completed_at NULL", "col"),
                ("created_at: timestamptz", "col"),
                ("partial UNIQUE (document_type_code, document_id)", "note"),
                ("  WHERE status IN (PENDING_SEND, SIGNING)", "note"),
            ]),
            ("signing_callback_log", 440, 140, [
                ("id: uuid", "pk"),
                ("session_id → signing_session NULL (unknown ref)", "fk"),
                ("provider_ref: text", "col"),
                ("received_at: timestamptz", "col"),
                ("result: text", "col"),
                ("raw_payload: jsonb", "col"),
                ("full webhook trace (4.10 'phiên ký')", "note"),
            ]),
            (OUTBOX[0], 440, 360, OUTBOX[1]),
            (AUDIT_LOG[0], 800, 140, AUDIT_LOG[1]),
        ],
        "edges": [("signing_session", "signing_callback_log")],
        "ghosts": [("owner document (contract | addendum | statement)", 40, 560)],
        "ghost_edges": [("signing_session", "owner document (contract | addendum | statement)")],
    },

    "notification": {
        "colors": ("#e8f5e9", "#66bb6a"),
        "note": ("notification-service · schema `notification` · pure event consumer (4.9). "
                 "Recipient resolution: assignee/requester ids from event payload; role-addressed events resolved via "
                 "identity GET /internal/users?role=. No outbox, no audit_log (not a business document)."),
        "tables": [
            ("notification", 40, 140, [
                ("id: uuid", "pk"),
                ("recipient_user_id: uuid ⇢ identity.app_user", "ref"),
                ("category: text (§8: APPROVAL|ESIGN|EXPIRY|SYSTEM)", "col"),
                ("title: text", "col"),
                ("body: text", "col"),
                ("document_type_code: text NULL (§2)", "col"),
                ("document_id: uuid NULL / document_no: text NULL", "col"),
                ("event_id: uuid NULL (trace to source event)", "col"),
                ("read_at: timestamptz NULL", "col"),
                ("created_at: timestamptz", "col"),
                ("INDEX (recipient_user_id, read_at)", "note"),
            ]),
            (PROCESSED[0], 440, 140, PROCESSED[1]),
        ],
        "edges": [],
        "ghosts": [("identity.app_user", 440, 300)],
        "ghost_edges": [("notification", "identity.app_user")],
    },
}

# ---------------------------------------------------------------- rendering

def table_cells(cid, name, x, y, rows, fill, stroke, ghost=False):
    h = HDR_H + ROW_H * len(rows)
    style = ("swimlane;fontStyle=1;align=center;verticalAlign=top;childLayout=stackLayout;"
             "horizontal=1;startSize=28;horizontalStack=0;resizeParent=0;resizeParentMax=0;"
             f"collapsible=0;rounded=0;shadow=0;html=1;fontSize=12;strokeColor={stroke};fillColor={fill};")
    if ghost:
        style += "dashed=1;fontColor=#666666;"
    cells = [f'<mxCell id="{cid}" value="{escape(name)}" style="{style}" vertex="1" parent="1">'
             f'<mxGeometry x="{x}" y="{y}" width="{W}" height="{h}" as="geometry" /></mxCell>']
    for i, (txt, kind) in enumerate(rows):
        rstyle = ("text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;"
                  "spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];"
                  "portConstraint=eastwest;fontSize=11;whiteSpace=wrap;html=1;")
        label = txt
        if kind == "pk":
            rstyle += "fontStyle=1;"
            label = "PK  " + txt
        elif kind == "fk":
            label = "FK  " + txt
        elif kind == "ref":
            rstyle += "fontColor=#78909c;"
        elif kind == "note":
            rstyle += "fontStyle=2;fontColor=#8a8a8a;fontSize=10;"
        cells.append(f'<mxCell id="{cid}_r{i}" value="{escape(label)}" style="{rstyle}" vertex="1" parent="{cid}">'
                     f'<mxGeometry y="{HDR_H + i * ROW_H}" width="{W}" height="{ROW_H}" as="geometry" /></mxCell>')
    return cells


def edge_cell(eid, src, dst, label="", cross=False):
    if cross:
        style = ("edgeStyle=orthogonalEdgeStyle;rounded=0;dashed=1;strokeColor=#90a4ae;"
                 "endArrow=open;endFill=0;fontSize=10;html=1;")
    else:
        style = ("edgeStyle=entityRelationEdgeStyle;rounded=0;strokeColor=#546e7a;"
                 "startArrow=ERone;startFill=0;endArrow=ERmany;endFill=0;fontSize=10;html=1;")
    return (f'<mxCell id="{eid}" value="{escape(label)}" style="{style}" edge="1" parent="1" '
            f'source="{src}" target="{dst}"><mxGeometry relative="1" as="geometry" /></mxCell>')


LEGEND = ("Legend — PK bold · FK = in-schema foreign key (crow's-foot edge) · "
          "⇢ = cross-service opaque uuid reference, D7 (dashed edge / grey row, never a real FK) · "
          "std cols §6 = created_at/by, updated_at/by · enums & transitions: registry §3/§9")


def build(service, spec):
    tid = lambda t: f"{service}__{t.replace('.', '_').replace(' ', '_').replace('|', '').replace('(', '').replace(')', '')}"
    cells = []
    cells.append(f'<mxCell id="title" value="{escape(f"PAS · db schema · {service}-service")}" '
                 'style="text;html=1;fontSize=18;fontStyle=1;align=left;" vertex="1" parent="1">'
                 '<mxGeometry x="40" y="20" width="700" height="30" as="geometry" /></mxCell>')
    cells.append(f'<mxCell id="svcnote" value="{escape(spec["note"])}" '
                 'style="text;html=1;fontSize=11;align=left;fontColor=#555555;whiteSpace=wrap;" vertex="1" parent="1">'
                 '<mxGeometry x="40" y="52" width="1100" height="40" as="geometry" /></mxCell>')
    cells.append(f'<mxCell id="legend" value="{escape(LEGEND)}" '
                 'style="text;html=1;fontSize=10;align=left;fontColor=#888888;whiteSpace=wrap;" vertex="1" parent="1">'
                 '<mxGeometry x="40" y="94" width="1100" height="30" as="geometry" /></mxCell>')
    fill, stroke = spec["colors"]
    for name, x, y, rows in spec["tables"]:
        f, s = (fill, stroke)
        if name in ("audit_log", "outbox", "processed_event"):
            f, s = NEUTRAL
        cells += table_cells(tid(name), name, x, y, rows, f, s)
    for i, (gname, gx, gy) in enumerate(spec.get("ghosts", [])):
        cells += table_cells(tid(gname), gname, gx, gy,
                             [("(external — see owning service diagram)", "note")],
                             GHOST[0], GHOST[1], ghost=True)
    for i, e in enumerate(spec.get("edges", [])):
        cells.append(edge_cell(f"e{i}", tid(e[0]), tid(e[1]), e[2] if len(e) > 2 else ""))
    for i, e in enumerate(spec.get("cross_edges", []) + spec.get("ghost_edges", [])):
        cells.append(edge_cell(f"x{i}", tid(e[0]), tid(e[1]), e[2] if len(e) > 2 else "", cross=True))
    inner = "\n        ".join(cells)
    return f'''<mxfile host="app.diagrams.net">
  <diagram id="db-{service}" name="db-{service}">
    <mxGraphModel dx="1400" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1650" pageHeight="1200" math="0" shadow="0">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />
        {inner}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
'''


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for service, spec in SPECS.items():
        xml_text = build(service, spec)
        xml.dom.minidom.parseString(xml_text)  # well-formedness check
        path = os.path.join(OUT_DIR, f"db-{service}.drawio")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(xml_text)
        print(f"wrote {os.path.relpath(path)}")


if __name__ == "__main__":
    main()
