#!/usr/bin/env python3
"""Generate draw.io ER diagrams for the PAS service DB schemas (design phase 2).

Usage:  python3 scripts/gen_er_drawio.py
Writes: docs/design/db/db-<service>.drawio (one file per service schema)

Format follows the standard draw.io ER table shape (shape=table/tableRow/
partialRectangle): a narrow key cell (PK / FK1 / PK,FK1 ...) + a "name type"
cell per column. No constraint/annotation rows inside tables — constraints,
uniqueness, nullability and snapshot semantics live in each db-<service>.md
("Constraints & indexes") and docs/design/00-registry.md.

Spec row = (keys, "name type", target):
  keys   ∈ "" | "PK" | "FK" | "PK,FK"   (FKs are auto-numbered per table)
  target = in-schema table name (solid crow's-foot edge)
           or ghost table name (dashed edge, cross-service opaque uuid ref, D7)
"""
import os
import xml.dom.minidom
from xml.sax.saxutils import escape

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "design", "db")
ROW_H, HDR_H = 30, 30
KEY_W = 60
TBL_W = 280
GHOST_W = 220

NEUTRAL = ("#f5f5f5", "#666666")
GHOST_C = ("#fafafa", "#999999")

STD_COLS = [
    ("", "created_at timestamptz", None),
    ("", "created_by uuid", None),
    ("", "updated_at timestamptz", None),
    ("", "updated_by uuid", None),
]

OUTBOX = ("outbox", [
    ("PK", "id uuid", None),
    ("", "event_type text", None),
    ("", "aggregate_type text", None),
    ("", "aggregate_id uuid", None),
    ("", "payload jsonb", None),
    ("", "created_at timestamptz", None),
    ("", "claimed_at timestamptz", None),
    ("", "published_at timestamptz", None),
    ("", "cancelled_at timestamptz", None),
    ("", "retry_count int", None),
])

def status_history(x, y, owner_rows):
    """Append-only transition log (D17). Domain data, not audit: business rules may
    read it synchronously. One per owning schema; INSERT+SELECT only."""
    return ("status_history", x, y, [("PK", "id uuid", None)] + owner_rows + [
        ("", "from_status text", None),
        ("", "to_status text", None),
        ("", "trigger_kind text", None),
        ("", "trigger_ref uuid", None),
        ("", "actor_id uuid", None),
        ("", "actor_name text", None),
        ("", "note text", None),
        ("", "occurred_at timestamptz", None),
    ])


PROCESSED = ("processed_event", [
    ("PK", "event_id uuid", None),
    ("", "processed_at timestamptz", None),
])

SPECS = {
    "identity": {
        "colors": ("#dae8fc", "#6c8ebf"),
        "caption": "identity-service · schema `identity` · constraints & conventions: db-identity.md, registry §6",
        "tables": [
            ("department", 40, 140, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "name text", None),
            ]),
            ("app_user", 400, 140, [
                ("PK", "id uuid", None),
                ("", "username text", None),
                ("", "password_hash text", None),
                ("", "full_name text", None),
                ("", "email text", None),
                ("FK", "department_id uuid", "department"),
                ("", "status text", None),
                ("", "last_login_at timestamptz", None),
                ("", "created_at timestamptz", None),
                # Only identity-service has app_user in-schema with audit/actor columns —
                # everywhere else created_by/updated_by/actor_id are opaque cross-schema
                # uuids (D7/D12, no FK). Here they're genuinely local: real FK to app_user.
                ("FK", "created_by uuid", "app_user"),
                ("", "updated_at timestamptz", None),
                ("FK", "updated_by uuid", "app_user"),
            ]),
            ("role", 40, 320, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "name text", None),
            ]),
            ("user_role", 400, 600, [
                ("PK,FK", "user_id uuid", "app_user"),
                ("PK,FK", "role_id uuid", "role"),
            ]),
            ("permission", 40, 500, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "description text", None),
            ]),
            ("role_permission", 400, 740, [
                ("PK,FK", "role_id uuid", "role"),
                ("PK,FK", "permission_id uuid", "permission"),
            ]),
            (OUTBOX[0], 760, 140, OUTBOX[1]),
        ],
        "ghosts": [],
    },

    "contract": {
        "colors": ("#d5e8d4", "#82b366"),
        "caption": "contract-service · schema `contract` · constraints & conventions: db-contract.md, registry §6/§9",
        "tables": [
            ("customer", 40, 140, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "name text", None),
                ("", "short_name text", None),
                ("", "tax_code text", None),
                ("", "address text", None),
                ("", "representative_name text", None),
                ("", "representative_position text", None),
                ("", "segment text", None),
                ("", "status text", None),
            ] + STD_COLS),
            ("customer_contact", 40, 640, [
                ("PK", "id uuid", None),
                ("FK", "customer_id uuid", "customer"),
                ("", "full_name text", None),
                ("", "title text", None),
                ("", "email text", None),
                ("", "phone text", None),
                ("", "is_primary boolean", None),
            ]),
            ("contract", 400, 140, [
                ("PK", "id uuid", None),
                ("", "contract_no text", None),
                ("FK", "customer_id uuid", "customer"),
                ("", "description text", None),
                ("", "service_group text", None),
                ("", "value numeric(18,2)", None),
                ("", "currency text", None),
                ("", "valid_from date", None),
                ("", "valid_to date", None),
                ("", "auto_renewal boolean", None),
                ("", "payment_term text", None),
                ("", "billing_cycle text", None),
                ("", "vat_rate numeric(5,2)", None),
                ("", "penalty_terms text", None),
                ("", "service_clause text", None),
                ("", "status text", None),
                ("", "version int", None),
            ] + STD_COLS),
            ("addendum", 760, 140, [
                ("PK", "id uuid", None),
                ("", "addendum_no text", None),
                ("FK", "contract_id uuid", "contract"),
                ("", "change_type text", None),
                ("", "description text", None),
                ("", "effective_from date", None),
                ("", "new_valid_to date", None),
                ("", "payment_term_override text", None),
                ("", "status text", None),
                ("", "version int", None),
            ] + STD_COLS),
            ("attachment", 760, 640, [
                ("PK", "id uuid", None),
                ("", "owner_type text", None),
                ("", "owner_id uuid", None),
                ("", "file_name text", None),
                ("", "content_type text", None),
                ("", "size_bytes bigint", None),
                ("", "storage_path text", None),
                ("", "uploaded_by uuid", None),
                ("", "uploaded_at timestamptz", None),
            ]),
            (PROCESSED[0], 40, 920, PROCESSED[1]),
            (OUTBOX[0], 1120, 140, OUTBOX[1]),
            # polymorphic over CONTRACT|ADDENDUM (same pattern as `attachment`)
            status_history(1120, 480, [("", "entity_type text", None),
                                       ("", "entity_id uuid", None)]),
        ],
        "ghosts": [],
    },

    "pricing": {
        "colors": ("#ffe6cc", "#d79b00"),
        "caption": "pricing-service · schema `pricing` · constraints & conventions: db-pricing.md, registry §6/§9",
        "tables": [
            ("service_item", 40, 140, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "name text", None),
                ("", "unit text", None),
                ("", "service_group text", None),
                ("", "is_active boolean", None),
            ]),
            ("price_list", 400, 140, [
                ("PK", "id uuid", None),
                ("", "price_list_no text", None),
                ("", "name text", None),
                ("", "customer_id uuid", "contract.customer"),
                ("", "customer_name text", None),
                ("", "contract_id uuid", "contract.contract"),
                ("", "service_group text", None),
                ("", "scope_key text", None),
            ] + STD_COLS),
            ("price_list_version", 760, 140, [
                ("PK", "id uuid", None),
                ("FK", "price_list_id uuid", "price_list"),
                ("", "version_no int", None),
                ("", "valid_from date", None),
                ("", "valid_to date", None),
                ("", "status text", None),
                ("", "scope_key text", None),
                ("", "addendum_id uuid", "contract.addendum"),
            ] + STD_COLS),
            ("price_line", 1120, 140, [
                ("PK", "id uuid", None),
                ("FK", "version_id uuid", "price_list_version"),
                ("FK", "service_item_id uuid", "service_item"),
                ("", "unit_price numeric(18,2)", None),
                ("", "currency text", None),
            ]),
            (PROCESSED[0], 40, 400, PROCESSED[1]),
            (OUTBOX[0], 1120, 380, OUTBOX[1]),
            status_history(1120, 700, [("FK", "version_id uuid", "price_list_version")]),
        ],
        "ghosts": [
            ("contract.customer", 400, 600),
            ("contract.contract", 660, 600),
            ("contract.addendum", 400, 700),
        ],
    },

    "operations": {
        "colors": ("#e1d5e7", "#9673a6"),
        "caption": "operations-service · schema `operations` · constraints & conventions: db-operations.md, registry §6/§9",
        "tables": [
            ("operation_period", 40, 140, [
                ("PK", "id uuid", None),
                ("", "period_code text", None),
                ("", "start_date date", None),
                ("", "end_date date", None),
                ("", "status text", None),
                ("", "locked_by uuid", None),
                ("", "locked_by_name text", None),
                ("", "locked_at timestamptz", None),
            ]),
            ("volume_record", 400, 140, [
                ("PK", "id uuid", None),
                ("", "record_no text", None),
                ("FK", "period_id uuid", "operation_period"),
                ("", "contract_id uuid", "contract.contract"),
                ("", "customer_id uuid", "contract.customer"),
                ("", "customer_name text", None),
                ("", "service_code text", "pricing.service_item"),
                ("", "service_name text", None),
                ("", "unit text", None),
                ("", "quantity numeric(18,3)", None),
                ("", "note text", None),
            ] + STD_COLS),
            (OUTBOX[0], 760, 140, OUTBOX[1]),
        ],
        "ghosts": [
            ("contract.contract", 400, 680),
            ("contract.customer", 660, 680),
            ("pricing.service_item", 920, 680),
        ],
    },

    "workflow": {
        "colors": ("#fff2cc", "#d6b656"),
        "caption": "workflow-service · schema `workflow` · constraints & conventions: db-workflow.md, registry §6/§7/§9",
        "tables": [
            ("document_type_config", 40, 140, [
                ("PK", "id uuid", None),
                ("", "code text", None),
                ("", "name text", None),
                ("", "number_prefix text", None),
                ("", "esign_enabled boolean", None),
                ("", "esign_provider text", None),
            ]),
            ("workflow_definition", 40, 380, [
                ("PK", "id uuid", None),
                ("FK", "document_type_id uuid", "document_type_config"),
                ("", "version_no int", None),
                ("", "name text", None),
                ("", "is_active boolean", None),
                ("", "created_at timestamptz", None),
                ("", "created_by uuid", None),
            ]),
            ("workflow_step_definition", 40, 660, [
                ("PK", "id uuid", None),
                ("FK", "definition_id uuid", "workflow_definition"),
                ("", "step_order int", None),
                ("", "name text", None),
                ("", "approver_role text", None),
                ("", "sla_hours int", None),
            ]),
            ("workflow_instance", 400, 140, [
                ("PK", "id uuid", None),
                ("FK", "definition_id uuid", "workflow_definition"),
                ("", "idempotency_key uuid", None),
                ("", "document_type_code text", None),
                ("", "document_id uuid", None),
                ("", "document_no text", None),
                ("", "customer_name text", None),
                ("", "priority text", None),
                ("", "status text", None),
                ("", "current_step_order int", None),
                ("", "requested_by uuid", None),
                ("", "requested_by_name text", None),
                ("", "created_at timestamptz", None),
                ("", "completed_at timestamptz", None),
            ]),
            ("workflow_step_instance", 400, 640, [
                ("PK", "id uuid", None),
                ("FK", "instance_id uuid", "workflow_instance"),
                ("", "step_order int", None),
                ("", "name text", None),
                ("", "approver_role text", None),
                ("", "sla_hours int", None),
                ("", "status text", None),
                ("", "version int", None),
                ("", "activated_at timestamptz", None),
                ("", "completed_at timestamptz", None),
                ("", "overdue_notified_at timestamptz", None),
                ("", "acted_by uuid", None),
                ("", "acted_by_name text", None),
            ]),
            ("step_assignee", 760, 640, [
                ("PK", "id uuid", None),
                ("FK", "step_instance_id uuid", "workflow_step_instance"),
                ("", "user_id uuid", "identity.app_user"),
                ("", "user_name text", None),
            ]),
            ("workflow_action", 760, 820, [
                ("PK", "id uuid", None),
                ("FK", "step_instance_id uuid", "workflow_step_instance"),
                ("", "action text", None),
                ("", "actor_id uuid", None),
                ("", "actor_name text", None),
                ("", "comment text", None),
                ("", "created_at timestamptz", None),
            ]),
            (OUTBOX[0], 1120, 140, OUTBOX[1]),
        ],
        "ghosts": [("identity.app_user", 1120, 640)],
    },

    "billing": {
        "colors": ("#f8cecc", "#b85450"),
        "caption": "billing-service · schema `billing` · constraints & conventions: db-billing.md, registry §6/§9",
        "tables": [
            ("payment_statement", 40, 140, [
                ("PK", "id uuid", None),
                ("", "statement_no text", None),
                ("", "contract_id uuid", "contract.contract"),
                ("", "contract_no text", None),
                ("", "customer_id uuid", "contract.customer"),
                ("", "customer_name text", None),
                ("", "period_code text", "operations.operation_period"),
                ("", "period_start date", None),
                ("", "period_end date", None),
                ("", "price_list_version_id uuid", "pricing.price_list_version"),
                ("", "price_list_no text", None),
                ("", "price_list_version_no int", None),
                ("", "payment_term text", None),
                ("", "vat_rate numeric(5,2)", None),
                ("", "subtotal numeric(18,2)", None),
                ("", "tax_amount numeric(18,2)", None),
                ("", "total_amount numeric(18,2)", None),
                ("", "currency text", None),
                ("", "status text", None),
                ("FK", "adjusts_statement_id uuid", "payment_statement"),
                ("", "reconciled_at timestamptz", None),
                ("", "reconciled_by uuid", None),
                ("", "issued_at timestamptz", None),
                ("", "due_date date", None),
                ("", "version int", None),
            ] + STD_COLS),
            ("statement_line", 400, 140, [
                ("PK", "id uuid", None),
                ("FK", "statement_id uuid", "payment_statement"),
                ("", "line_no int", None),
                ("", "service_code text", "pricing.service_item"),
                ("", "service_name text", None),
                ("", "unit text", None),
                ("", "unit_price numeric(18,2)", None),
                ("", "quantity numeric(18,3)", None),
                ("", "amount numeric(18,2)", None),
                ("", "source text", None),
                ("", "note text", None),
            ]),
            ("statement_line_volume", 400, 560, [
                ("PK", "id uuid", None),
                ("FK", "line_id uuid", "statement_line"),
                ("", "volume_record_id uuid", "operations.volume_record"),
                ("", "record_no text", None),
                ("", "quantity numeric(18,3)", None),
            ]),
            (PROCESSED[0], 400, 800, PROCESSED[1]),
            (OUTBOX[0], 400, 940, OUTBOX[1]),
            status_history(760, 1000, [("FK", "statement_id uuid", "payment_statement")]),
        ],
        "ghosts": [
            ("contract.contract", 760, 620),
            ("contract.customer", 1020, 620),
            ("operations.operation_period", 760, 740),
            ("operations.volume_record", 1020, 740),
            ("pricing.price_list_version", 760, 860),
            ("pricing.service_item", 1020, 860),
        ],
    },

    "esign": {
        "colors": ("#d6eaf8", "#5499c7"),
        "caption": "esign-service · schema `esign` · constraints & conventions: db-esign.md, registry §6/§9",
        "tables": [
            ("signing_session", 40, 140, [
                ("PK", "id uuid", None),
                ("", "session_no text", None),
                ("", "document_type_code text", None),
                ("", "document_id uuid", "owner document"),
                ("", "document_no text", None),
                ("", "customer_name text", None),
                ("", "signer_name text", None),
                ("", "signer_email text", None),
                ("", "provider text", None),
                ("", "provider_ref text", None),
                ("", "status text", None),
                ("", "attempts int", None),
                ("", "last_error text", None),
                ("", "requested_by uuid", None),
                ("", "requested_by_name text", None),
                ("", "version int", None),
                ("", "sent_at timestamptz", None),
                ("", "completed_at timestamptz", None),
                ("", "created_at timestamptz", None),
            ]),
            ("signing_callback_log", 400, 140, [
                ("PK", "id uuid", None),
                ("FK", "session_id uuid", "signing_session"),
                ("", "provider_ref text", None),
                ("", "received_at timestamptz", None),
                ("", "result text", None),
                ("", "raw_payload jsonb", None),
            ]),
            (OUTBOX[0], 400, 400, OUTBOX[1]),
            status_history(760, 140, [("FK", "session_id uuid", "signing_session")]),
        ],
        "ghosts": [("owner document", 400, 680)],
    },

    "notification": {
        "colors": ("#e8f5e9", "#66bb6a"),
        "caption": "notification-service · schema `notification` · constraints & conventions: db-notification.md, registry §6",
        "tables": [
            ("notification", 40, 140, [
                ("PK", "id uuid", None),
                ("", "recipient_user_id uuid", "identity.app_user"),
                ("", "category text", None),
                ("", "title text", None),
                ("", "body text", None),
                ("", "document_type_code text", None),
                ("", "document_id uuid", None),
                ("", "document_no text", None),
                ("", "event_id uuid", None),
                ("", "read_at timestamptz", None),
                ("", "created_at timestamptz", None),
            ]),
            (PROCESSED[0], 400, 140, PROCESSED[1]),
        ],
        "ghosts": [("identity.app_user", 400, 300)],
    },

    "audit": {
        "colors": ("#cfe8e4", "#2f9e8f"),
        "caption": "audit-service · schema `audit` · read model, not system of record · constraints & conventions: db-audit.md, registry §6",
        "tables": [
            ("audit_record", 40, 140, [
                ("PK", "id uuid", None),
                ("", "source_service text", None),
                ("", "entity_type text", None),
                ("", "entity_id uuid", None),
                ("", "entity_no text", None),
                ("", "action text", None),
                ("", "actor_id uuid", "identity.app_user"),
                ("", "actor_name text", None),
                ("", "actor_department text", None),
                ("", "before_status text", None),
                ("", "after_status text", None),
                ("", "changes jsonb", None),
                ("", "note text", None),
                ("", "ip_address text", None),
                ("", "occurred_at timestamptz", None),
                ("", "received_at timestamptz", None),
            ]),
        ],
        "ghosts": [("identity.app_user", 400, 140)],
    },
}

# ---------------------------------------------------------------- rendering

def sid(service, name):
    keep = "".join(c if c.isalnum() else "_" for c in name)
    return f"{service}__{keep}"


def table_cells(cid, name, x, y, rows, fill, stroke, w=TBL_W, ghost=False):
    h = HDR_H + ROW_H * len(rows)
    style = ("shape=table;startSize=30;container=1;collapsible=1;childLayout=tableLayout;"
             "fixedRows=1;rowLines=0;fontStyle=1;align=center;resizeLast=1;html=1;whiteSpace=wrap;"
             f"fillColor={fill};strokeColor={stroke};")
    if ghost:
        style += "dashed=1;fontColor=#666666;"
    cells = [f'<mxCell id="{cid}" value="{escape(name)}" style="{style}" vertex="1" parent="1">'
             f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry" /></mxCell>']
    fk_n = 0
    last_pk = max((i for i, (k, _, _) in enumerate(rows) if "PK" in k), default=-1)
    for i, (keys, text, _target) in enumerate(rows):
        marker = keys
        if "FK" in keys:
            fk_n += 1
            marker = keys.replace("FK", f"FK{fk_n}")
        is_pk = "PK" in keys
        bottom = 1 if i == last_pk else 0
        row_id = f"{cid}_r{i}"
        row_style = ("shape=tableRow;horizontal=0;startSize=0;swimlaneHead=0;swimlaneBody=0;"
                     "fillColor=none;collapsible=0;dropTarget=0;points=[[0,0.5],[1,0.5]];"
                     f"portConstraint=eastwest;top=0;left=0;right=0;bottom={bottom};html=1;")
        cells.append(f'<mxCell id="{row_id}" value="" style="{row_style}" vertex="1" parent="{cid}">'
                     f'<mxGeometry y="{HDR_H + i * ROW_H}" width="{w}" height="{ROW_H}" as="geometry" /></mxCell>')
        key_style = ("shape=partialRectangle;connectable=0;fillColor=none;top=0;left=0;bottom=0;right=0;"
                     "fontStyle=1;overflow=hidden;whiteSpace=wrap;html=1;")
        cells.append(f'<mxCell id="{row_id}_k" value="{escape(marker)}" style="{key_style}" vertex="1" parent="{row_id}">'
                     f'<mxGeometry width="{KEY_W}" height="{ROW_H}" as="geometry">'
                     f'<mxRectangle width="{KEY_W}" height="{ROW_H}" as="alternateBounds" /></mxGeometry></mxCell>')
        val_style = ("shape=partialRectangle;connectable=0;fillColor=none;top=0;left=0;bottom=0;right=0;"
                     "align=left;spacingLeft=6;overflow=hidden;whiteSpace=wrap;html=1;")
        if is_pk:
            val_style += "fontStyle=5;"
        cells.append(f'<mxCell id="{row_id}_v" value="{escape(text)}" style="{val_style}" vertex="1" parent="{row_id}">'
                     f'<mxGeometry x="{KEY_W}" width="{w - KEY_W}" height="{ROW_H}" as="geometry">'
                     f'<mxRectangle width="{w - KEY_W}" height="{ROW_H}" as="alternateBounds" /></mxGeometry></mxCell>')
    return cells


def edge_cell(eid, src, dst, cross=False):
    if cross:
        style = ("edgeStyle=orthogonalEdgeStyle;rounded=0;dashed=1;strokeColor=#90a4ae;"
                 "endArrow=open;endFill=0;fontSize=10;html=1;exitX=1;exitY=0.5;exitDx=0;exitDy=0;")
    else:
        style = ("edgeStyle=entityRelationEdgeStyle;rounded=0;strokeColor=#546e7a;"
                 "startArrow=ERmany;startFill=0;endArrow=ERone;endFill=0;fontSize=10;html=1;")
    return (f'<mxCell id="{eid}" style="{style}" edge="1" parent="1" '
            f'source="{src}" target="{dst}"><mxGeometry relative="1" as="geometry" /></mxCell>')


def build(service, spec):
    fill, stroke = spec["colors"]
    cells = []
    cells.append(f'<mxCell id="title" value="{escape(f"PAS · db schema · {service}-service")}" '
                 'style="text;html=1;fontSize=18;fontStyle=1;align=left;" vertex="1" parent="1">'
                 '<mxGeometry x="40" y="30" width="700" height="30" as="geometry" /></mxCell>')
    cells.append(f'<mxCell id="caption" value="{escape(spec["caption"])}" '
                 'style="text;html=1;fontSize=11;align=left;fontColor=#777777;" vertex="1" parent="1">'
                 '<mxGeometry x="40" y="62" width="1200" height="24" as="geometry" /></mxCell>')

    table_names = {name for name, _, _, _ in spec["tables"]}
    ghost_names = {g[0] for g in spec.get("ghosts", [])}

    for name, x, y, rows in spec["tables"]:
        f, s = (fill, stroke)
        if name in ("outbox", "processed_event"):
            f, s = NEUTRAL
        cells += table_cells(sid(service, name), name, x, y, rows, f, s)
    for gname, gx, gy in spec.get("ghosts", []):
        cells += table_cells(sid(service, gname), gname, gx, gy,
                             [("PK", "id uuid", None)], GHOST_C[0], GHOST_C[1],
                             w=GHOST_W, ghost=True)

    en = 0
    for name, _, _, rows in spec["tables"]:
        for i, (keys, _text, target) in enumerate(rows):
            if not target:
                continue
            row_id = f"{sid(service, name)}_r{i}"
            en += 1
            if target in table_names:
                cells.append(edge_cell(f"e{en}", row_id, sid(service, target)))
            elif target in ghost_names:
                cells.append(edge_cell(f"e{en}", row_id, sid(service, target), cross=True))
            else:
                raise ValueError(f"{service}.{name}: unknown edge target {target!r}")

    inner = "\n        ".join(cells)
    return f'''<mxfile host="app.diagrams.net">
  <diagram id="db-{service}" name="db-{service}">
    <mxGraphModel dx="1400" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1700" pageHeight="1400" math="0" shadow="0">
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
