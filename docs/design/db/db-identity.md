# db-identity — notes (step 2.1)

Schema `identity`, owned by identity-service. Diagram: [db-identity.drawio](db-identity.drawio).

## Key decisions
- **Plain RBAC**: `role` ⟷ `permission` via `role_permission`; users get roles via `user_role`. No per-object ACLs — contextual authorization (APR-01 "assignee of the current step") is enforced by workflow-service against `step_assignee`, not here (D11 layer 2).
- **No refresh-token / blacklist table**: JWT access tokens; revocation via Redis blacklist keyed by jti (registry §1). A DB table would duplicate Redis state.
- **`last_login_at`, `status ACTIVE|DISABLED`**: from Figma Administration screen (user list shows both).
- **No outbox / processed_event**: identity emits no events and consumes none; workflow and notification call it synchronously (`GET /internal/users?role=`).
- Password hashing: bcrypt/argon2 — implementation detail, no schema impact beyond `password_hash text`.

## Seeds (registry §7, §10)
Departments `SALES, LEGAL, ACCOUNTING, OPERATIONS, BOARD, IT`; roles `SALES_OFFICER, SALES_MANAGER, LEGAL_REVIEWER, ACCOUNTANT, OPS_OFFICER, DIRECTOR, SYSTEM_ADMIN`; permissions `volume.edit_locked, contract.cancel_active, statement.cancel_approved, workflow.configure, user.manage, audit.view_all`.

## Rule / requirement mapping
| Source | Design element |
|---|---|
| Admin UC "Quản lý người dùng" | `app_user` CRUD + `status` |
| Admin UC "Phân quyền" | `user_role`, `role_permission` |
| req §6 JWT | issued here; validated at gateway (D11 layer 1) |
| APR-01 assignee resolution | `GET /internal/users?role=` consumed by workflow at step activation |
| 4.5 "quyền đặc biệt" | permission `volume.edit_locked` |

## Figma adoptions / discrepancies
- Adopted: `last_login_at`, user `status` badge (Active/Disabled), department + role columns.
- None contradicting requirements.

## Constraints & indexes (not shown in the diagram)
- UNIQUE: `department.code`, `app_user.username`, `app_user.email`, `role.code`, `permission.code`.
- Composite PKs: `user_role (user_id, role_id)`, `role_permission (role_id, permission_id)`.
- `app_user.status` CHECK vs registry §3 USER enum; std cols per registry §6.
