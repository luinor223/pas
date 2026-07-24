# db-pricing — notes (step 2.3)

Schema `pricing`, owned by pricing-service. Diagram: [db-pricing.drawio](db-pricing.drawio).

## Key decisions
- **Service catalog lives here** (`service_item`): pricing is its natural owner (price lines are per item); operations & billing reference items by **`code`** (stable business key, human-legible in volume entry) — the one deliberate exception to uuid refs, recorded in registry §5/§6 usage.
- **Scope model (PRC-01)**: `price_list` carries nullable `customer_id`, `contract_id`, `service_group` + `CHECK` that ≥1 is set. `scope_key` is a derived normalization (e.g. `CONTRACT:<id>` or `CUSTOMER:<id>:GROUP:<grp>`) used for overlap enforcement.
- **Overlap (PRC-03)** — the concrete mechanism: on `price_list_version`,
  `EXCLUDE USING gist (scope_key WITH =, daterange(valid_from, valid_to, '[]') WITH &&) WHERE (status IN ('APPROVED','EFFECTIVE'))` — requires the `btree_gist` extension (text equality inside gist). Residual gap: two lists with *different* scope kinds targeting the same customer (e.g. one CONTRACT-scoped, one CUSTOMER-scoped) can't collide in the constraint — mostly neutralized by the lookup precedence rule (shadowing, above); the remaining warning is an app-level validation run at **submit and again at approve** (check-then-act, best-effort — documented limitation).
- **Versioning (PRC-04/05)**: `price_list_version` per version, `UNIQUE(price_list_id, version_no)`. A version used by billing is `EFFECTIVE`/`SUPERSEDED` — never editable (edits only in DRAFT per §9), so PRC-05 holds structurally.
- **Truncate-then-approve (PRC-03 × PRC-04, registry §9³)**: approving a successor whose range overlaps the predecessor truncates the predecessor **first, in the same transaction** (`valid_to = successor.valid_from − 1 day`) — otherwise the exclusion constraint would reject the APPROVED flip. When the successor later flips EFFECTIVE (scheduler), the predecessor is marked SUPERSEDED. Truncation never touches unit prices, so PAY-03 snapshots are unaffected; it also keeps ever-effective ranges non-overlapping, making historical lookups unambiguous.
- **Effective lookup for billing** (registry §5): `GET /internal/price-lists/effective?contract_id&customer_id&service_group&date` — billing passes the contract's customer + service_group (it pulled the contract first). Precedence: CONTRACT-scoped > CUSTOMER+GROUP > CUSTOMER (most specific wins; also converts most cross-scope overlaps into deterministic shadowing). `date` = the statement's `period_end`; the lookup is historical (includes SUPERSEDED/EXPIRED versions whose validity contains `date`), so a June statement built in July still resolves the then-valid version. Mid-period version boundaries: the version effective at `period_end` wins and the reconciliation panel surfaces a warning — documented limitation, not silent.
- **Scope immutability**: `price_list` scope columns are frozen once any version exists (else the denormalized `scope_key` desyncs).
- **`addendum_id` on version** (D8): set only when Sales created the version from an approved addendum; `valid_from` defaults to the addendum's `effective_from`. No automation, no event.
- **Status flips**: consumes `workflow.completed` (processed_event); scheduler flips APPROVED→EFFECTIVE→EXPIRED + emits `document.expiring` (D9 direct publish — no outbox).

## Rule / requirement mapping
| Rule | Design element |
|---|---|
| PRC-01 | scope CHECK on `price_list` |
| PRC-02 | `valid_from <= valid_to` CHECK; both NOT NULL |
| PRC-03 | gist exclusion constraint (above) |
| PRC-04 | supersede-in-transaction on successor activation |
| PRC-05 | statuses past DRAFT are read-only; correction = new version |
| PRC-06 | REJECTED → revise → DRAFT (registry §9) |
| 4.4 versions & history | version rows + audit_log |

## Figma adoptions / discrepancies
- Adopted: PRC numbering, per-row version display (`v1..v4`), items count (computed), customer+service-group listing, catalog seeds (Container lift on/off · TEU; Storage beyond free time · day; Lashing & securing · TEU; Reefer monitoring · day; Documentation handling · set; Weighing (VGM) · TEU).
- Discrepancy: badge "Under Review" = enum `SUBMITTED` (registry §3 label mapping).
