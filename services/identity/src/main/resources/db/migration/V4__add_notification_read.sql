-- notification:read was missing from V2 seed; every role holds it (00-registry.md:153).
insert into permission (id, code, description)
values (gen_random_uuid(), 'notification:read', 'Xem thông báo')
on conflict (code) do nothing;

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p
where p.code = 'notification:read'
on conflict do nothing;
