# db-identity — notes (step 2.1)

Schema `identity`, owned by identity-service. Diagram: [db-identity.drawio](db-identity.drawio).

## Key decisions
- **Plain RBAC**: `role` ⟷ `permission` via `role_permission`; users get roles via `user_role`. No per-object ACLs — contextual authorization (APR-01 "assignee of the current step") is enforced by workflow-service against `step_assignee`, not here (D11 layer 2).
- **No refresh-token / blacklist table**: JWT access tokens; revocation via Redis blacklist keyed by jti (registry §1). A DB table would duplicate Redis state.
- **`last_login_at`, `status ACTIVE|DISABLED`**: from Figma Administration screen (user list shows both).
- **No `processed_event`**: identity consumes no events; workflow and notification call it synchronously (`IdentityInternal.ListUsersByRole`).
- **`outbox` for audit only** (D15): identity emits no business events, but user/role/permission changes are auditable, so `audit.recorded` rows are written to `outbox` in the same transaction as the change.
- Password hashing: bcrypt/argon2 — implementation detail, no schema impact beyond `password_hash text`.
- **Permissions ride the JWT, not a lookup endpoint**: `permissions[]` is computed at token issuance (`user_role → role_permission → permission`) and embedded alongside `roles[]` (registry §6). This is why there's no `GET /internal/users/{id}/permissions` endpoint — contract, operations, billing and workflow each check their permission (`contract.cancel_active`, `volume.edit_locked`, `statement.cancel_approved`, `workflow.configure`) directly against the claim, the same way they'd check a role; identity-service checks its own `user.manage` the same way for its user/role administration endpoints, rather than special-casing itself. A grant/revoke via "Phân quyền" takes effect at the user's next token issuance, not immediately — accepted, same staleness `roles[]` already has.
- **`GET /internal/users?role=` returns `status='ACTIVE'` users only** (registry §5): a `DISABLED` user can never be newly resolved as a workflow step assignee (APR-01) or a `recipient_role` notification target (§4) — closes the case where every holder of a role has left/been disabled and a submitted document would otherwise wait on someone who can no longer act. This is filtered here, at the resolution query, rather than left to the caller. Residual, accepted gap: a user disabled *after* already being snapshotted onto an active step isn't retroactively removed (`step_assignee` is a point-in-time snapshot, D7) — only strands the step if they were its sole assignee; live reassignment isn't attempted since no requirement asks for it.
- **`app_user.created_by`/`updated_by` and `audit_log.actor_id` are real FKs here, unlike everywhere else**: every other service's `created_by`/`updated_by`/`actor_id` names a user in the *identity* schema, so it's rightly an opaque cross-schema `uuid` with no FK (D7/D12). Identity is the one schema where `app_user` and `audit_log` are local to each other — the diagram now draws these as real self-referencing FKs to `app_user.id` (previously it mechanically reused the cross-schema convention even here). All three are **nullable**: `created_by`/`updated_by` NULL for the system-seeded first user; `actor_id` NULL for automated actions (registry §6 audit_log shape already specifies this). FK action is `ON DELETE RESTRICT` (the Postgres default) — not `SET NULL` — because `app_user` rows are never physically deleted in this design, only flipped to `DISABLED` (registry §3 USER enum); there is no delete path for the constraint to react to.

## Seeds (registry §7, §10)
Departments `SALES, LEGAL, ACCOUNTING, OPERATIONS, BOARD, IT`; roles `SALES_OFFICER, SALES_MANAGER, LEGAL_REVIEWER, ACCOUNTANT, OPS_OFFICER, DIRECTOR, SYSTEM_ADMIN`; permissions: the full `resource:action` vocabulary in registry §10, bundled into roles per registry §7.

**Authorization style**: code checks **permissions, never roles** — `hasRole(SALES_MANAGER)` would hard-code policy and make "add a role" a redeploy, contradicting §2's runtime *"phân quyền"*. Roles are editable bundles; only permission codes appear in code. Separator convention: `:` = permission, `.` = event type.

**Permission resolution**: the JWT carries `roles[]`, not `permissions[]`. Each service resolves role → permissions from a map cached in Redis and refreshed from identity — a fat claim would go stale until re-login (an admin revoking access must take effect immediately), while a per-request identity call would sit on the hot path.

## Rule / requirement mapping
| Source | Design element |
|---|---|
| Admin UC "Quản lý người dùng" | `app_user` CRUD + `status` |
| Admin UC "Phân quyền" | `user_role`, `role_permission` |
| req §6 JWT | issued here; validated at gateway (D11 layer 1) |
| APR-01 assignee resolution | `IdentityInternal.ListUsersByRole` consumed by workflow once per role, for every non-SKIPPED step, at instance creation; returns `status='ACTIVE'` users only |
| 4.5 "quyền đặc biệt" | permission `volume:edit_locked` |
| Permission propagation | `role_permission` exposed as the role→permission map services cache in Redis (registry §6) — no per-request call |

## Figma adoptions / discrepancies
- Adopted: `last_login_at`, user `status` badge (Active/Disabled), department + role columns.
- **Dropped: "Continue with company SSO"** (was on the login screen, node `8:32`). No requirement backing (req §6 asks for plain JWT auth only) and not cheap — a real implementation needs an external IdP, OAuth/SAML client config, and JIT user provisioning, none of which any other artifact assumes. Removed from the Figma login screen directly (not just noted) since, unlike the sidebar-by-role screen, this isn't a state worth demonstrating both ways — it's a permanent scope cut.
- **Deferred: "Forgot password?"** (login screen, node `8:29`) — left in place in Figma as UI-only. No requirement backs a reset flow either, but unlike SSO it could be built cheaply later (a short-lived signed link, no `password_reset_token` table) if ever needed; no backend exists for it in this design, and the login screen keeps the link for visual completeness only.
- None else contradicting requirements.

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `department.code`, `app_user.username`, `app_user.email`, `role.code`, `permission.code`.
- Composite PKs: `user_role (user_id, role_id)`, `role_permission (role_id, permission_id)`.
- `app_user.status` CHECK vs registry §3 USER enum; std cols per registry §6.
- Self-referencing FKs (drawn in the diagram, unique to this schema — see Key decisions): `app_user.created_by → app_user.id`, `app_user.updated_by → app_user.id`, `audit_log.actor_id → app_user.id`; all three `NULL`able, `ON DELETE RESTRICT`.
