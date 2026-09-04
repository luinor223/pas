-- V5: SYSTEM_ADMIN is superuser — grant every permission (not just reads + admin
-- perms). Split out of V2, which stays frozen with the original narrower grant.
-- `on conflict do nothing` makes this a pure delta on top of V2 (re-granting the
-- V2 rows is a no-op thanks to role_permission's unique constraint).
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p
where r.code = 'SYSTEM_ADMIN'
on conflict do nothing;
