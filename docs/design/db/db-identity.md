# db-identity — notes (step 2.1)

Schema `identity`, owned by identity-service. Diagram: [db-identity.drawio](db-identity.drawio).

## Key decisions
- **Plain RBAC**: `role` ⟷ `permission` via `role_permission`; users get roles via `user_role`. No per-object ACLs — contextual authorization (APR-01 "assignee of the current step") is enforced by workflow-service against `step_assignee`, not here (D11 layer 2).
- **No refresh-token / blacklist table**: JWT access tokens; revocation via Redis blacklist keyed by jti (registry §1). A DB table would duplicate Redis state.
- **`last_login_at`, `status ACTIVE|DISABLED`**: from Figma Administration screen (user list shows both).
- **No `processed_event`**: identity consumes no events; workflow and notification call it synchronously (`IdentityInternal.ListUsersByRole`).
- **`outbox` for audit only** (D15): identity emits no business events, but user/role/permission changes are auditable, so `audit.recorded` rows are written to `outbox` in the same transaction as the change.
- **The "Phân quyền" write path, ordered** (seq-01): `PUT /roles/{role_code}/permissions`, gated by `user:manage`, commits the `role_permission` rows and the `audit.recorded` outbox row in one transaction, and only **then** overwrites `perm:role:{role_code}` in Redis. The cache write is deliberately **outside** the transaction and best-effort — Redis cannot join a Postgres commit, and failing the request over a cache write would report a lie about a change that is already durable; a failed `SET` is logged and left to the hourly sweep (≤ 1h, TTL 6h backstop). Note the asymmetry with the read path: enforcement fails **closed** (`PERMISSION_DENIED` when Redis is unavailable, §M1), propagation fails **open**.
- Password hashing: bcrypt/argon2 — implementation detail, no schema impact beyond `password_hash text`.
- **Permissions are resolved from a cached map, not carried in the JWT** (registry §6, fully specified there — writer, key/TTL, startup warm + hourly refresh, invalidation, fail-closed behavior; supersedes the earlier `permissions[]`-claim decision): the token carries `roles[]` only; each service reads `perm:role:{role_code}` from Redis, written *only* by identity-service — attempted immediately on every `role_permission` change (best-effort, not guaranteed), on an hourly schedule regardless of change (so an idle role's key never just expires with nothing to rewrite it), and once for every role at startup (no cold-start gap). There is still no `/permissions` lookup endpoint on the hot path — contract, operations, billing and workflow check their permission (`contract:cancel_active`, `volume:edit_locked`, `statement:cancel_approved`, `workflow:configure`) against the resolved set, and identity checks its own `user:manage` the same way rather than special-casing itself. Difference from the claim approach: a grant/revoke via "Phân quyền" takes effect as soon as identity rewrites the role's Redis key (same request, best-effort), not at the user's next login — bounded by the next hourly sweep (or the 6h defensive TTL) if that write is somehow missed.
- **`IdentityInternal.ListUsersByRole` returns `status='ACTIVE'` users only** (registry §5): a `DISABLED` user can never be newly resolved as a workflow step assignee (APR-01) or a `recipient_role` notification target (§4) — closes the case where every holder of a role has left/been disabled and a submitted document would otherwise wait on someone who can no longer act. This is filtered here, at the resolution query, rather than left to the caller. Residual, accepted gap: a user disabled *after* already being snapshotted onto an active step isn't retroactively removed (`step_assignee` is a point-in-time snapshot, D7) — only strands the step if they were its sole assignee; live reassignment isn't attempted since no requirement asks for it.
- **`app_user.created_by`/`updated_by` are real FKs here, unlike everywhere else**: every other service's `created_by`/`updated_by`/`actor_id` names a user in the *identity* schema, so it's rightly an opaque cross-schema `uuid` with no FK (D7/D12). Identity is the one schema where those columns are local, so the diagram draws them as real self-referencing FKs to `app_user.id` rather than mechanically reusing the cross-schema convention. Both are **nullable** (NULL for the system-seeded first user). FK action is `ON DELETE RESTRICT` (the Postgres default), not `SET NULL` — `app_user` rows are never physically deleted, only flipped to `DISABLED` (registry §3), so there is no delete path to react to. (The same argument previously covered `audit_log.actor_id`; that table is gone under D15, and audit actors are snapshots in audit-service with no FK anywhere.)

## Seeds (registry §7, §10)
Departments `SALES, LEGAL, ACCOUNTING, OPERATIONS, BOARD, IT`; roles `SALES_OFFICER, SALES_MANAGER, LEGAL_REVIEWER, ACCOUNTANT, OPS_OFFICER, DIRECTOR, SYSTEM_ADMIN`; permissions: the full `resource:action` vocabulary in registry §10, bundled into roles per registry §7.

**Authorization style**: code checks **permissions, never roles** — `hasRole(SALES_MANAGER)` would hard-code policy and make "add a role" a redeploy, contradicting §2's runtime *"phân quyền"*. Roles are editable bundles; only permission codes appear in code. Separator convention: `:` = permission, `.` = event type.

**Permission resolution**: the JWT carries `roles[]`, not `permissions[]`. Each service resolves role → permissions from a map cached in Redis and refreshed from identity — a fat claim would go stale until re-login, while a per-request identity call would sit on the hot path. This buys **bounded eventual revocation** (usually near-immediate, formally bounded by the next hourly sweep, registry §6) rather than a hard immediacy guarantee — good enough here since nothing in the requirement demands instant revocation, just something better than "wait for re-login."

## Rule / requirement mapping
| Source | Design element |
|---|---|
| Admin UC "Quản lý người dùng" | `app_user` CRUD + `status` |
| Admin UC "Phân quyền" | `user_role`, `role_permission`; **`PUT /roles/{role_code}/permissions`** (`user:manage`) rewrites `role_permission` and then overwrites `perm:role:{role_code}` in Redis — seq-01 |
| req §6 JWT | issued here; validated at gateway (D11 layer 1) |
| APR-01 assignee resolution | `IdentityInternal.ListUsersByRole` consumed by workflow once per role, for every step, at instance creation; returns `status='ACTIVE'` users only |
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
- Self-referencing FKs (drawn in the diagram, unique to this schema — see Key decisions): `app_user.created_by → app_user.id`, `app_user.updated_by → app_user.id`; both `NULL`able, `ON DELETE RESTRICT`.
