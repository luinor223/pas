-- Registry §7 catalogues notification:read and says every role holds it, but V2 seeded neither
-- the permission nor a grant. NotificationController gates the whole inbox on it, so every user
-- got 403 from the bell. New migration, not an edit to V2: V2 is already applied.
insert into permission (id, code, description)
values (gen_random_uuid(), 'notification:read', 'Xem thông báo của mình');

-- every role, not a bundle: the inbox is always the caller's own and there is no path to anyone
-- else's, so there is nobody to withhold it from
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p where p.code = 'notification:read'
on conflict do nothing;
