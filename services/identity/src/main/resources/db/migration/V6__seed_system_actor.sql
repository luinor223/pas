-- The SYSTEM principal (registry D-actor, change log 2026-09-04): the identity credited with
-- system-initiated writes (scheduler flips, auto-applied addenda, relay-driven starts with no
-- human submitter), so a null actor_id now means missing or corrupt attribution, not "the system
-- did it". Fixed id matches SystemActor.ID in libs/common. Unusable password and no roles: it can
-- never authenticate and is never resolved as a workflow assignee (ListUsersByRole joins user_role).
insert into app_user (id, username, email, password_hash, full_name, department_id, status,
                      created_at, updated_at)
values ('00000000-0000-0000-0000-000000000001', 'system', 'system@pas.internal',
        '!', 'System',
        (select id from department where code = 'IT'), 'ACTIVE', now(), now());
