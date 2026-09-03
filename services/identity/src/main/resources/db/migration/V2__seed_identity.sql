insert into department (id, code, name) values
    (gen_random_uuid(), 'SALES',      'Kinh doanh'),
    (gen_random_uuid(), 'LEGAL',      'Pháp chế'),
    (gen_random_uuid(), 'ACCOUNTING', 'Kế toán'),
    (gen_random_uuid(), 'OPERATIONS', 'Khai thác'),
    (gen_random_uuid(), 'BOARD',      'Ban Giám đốc'),
    (gen_random_uuid(), 'IT',         'Quản trị hệ thống');

insert into role (id, code, name) values
    (gen_random_uuid(), 'SALES_OFFICER',  'Nhân viên kinh doanh'),
    (gen_random_uuid(), 'SALES_MANAGER',  'Trưởng phòng kinh doanh'),
    (gen_random_uuid(), 'LEGAL_REVIEWER', 'Chuyên viên pháp chế'),
    (gen_random_uuid(), 'ACCOUNTANT',     'Kế toán'),
    (gen_random_uuid(), 'OPS_OFFICER',    'Nhân viên khai thác'),
    (gen_random_uuid(), 'DIRECTOR',       'Giám đốc'),
    (gen_random_uuid(), 'SYSTEM_ADMIN',   'Quản trị hệ thống');

insert into permission (id, code, description) values
    (gen_random_uuid(), 'customer:read',            'Xem khách hàng'),
    (gen_random_uuid(), 'customer:write',           'Tạo/sửa khách hàng'),
    (gen_random_uuid(), 'contract:read',            'Xem hợp đồng'),
    (gen_random_uuid(), 'contract:write',           'Tạo/sửa hợp đồng'),
    (gen_random_uuid(), 'contract:cancel_active',   'Hủy hợp đồng đang hiệu lực'),
    (gen_random_uuid(), 'addendum:read',            'Xem phụ lục'),
    (gen_random_uuid(), 'addendum:write',           'Tạo/sửa phụ lục'),
    (gen_random_uuid(), 'pricelist:read',           'Xem bảng giá'),
    (gen_random_uuid(), 'pricelist:write',          'Tạo/sửa bảng giá'),
    (gen_random_uuid(), 'volume:read',              'Xem sản lượng'),
    (gen_random_uuid(), 'volume:write',             'Ghi nhận/sửa sản lượng'),
    (gen_random_uuid(), 'volume:lock_period',       'Khóa kỳ sản lượng'),
    (gen_random_uuid(), 'volume:edit_locked',       'Sửa sản lượng sau khi khóa kỳ'),
    (gen_random_uuid(), 'statement:read',           'Xem bảng thanh toán'),
    (gen_random_uuid(), 'statement:write',          'Lập/sửa bảng thanh toán'),
    (gen_random_uuid(), 'statement:cancel_approved','Hủy bảng thanh toán đã duyệt'),
    (gen_random_uuid(), 'approval:act',             'Phê duyệt/từ chối/yêu cầu chỉnh sửa'),
    (gen_random_uuid(), 'esign:send',               'Gửi ký điện tử'),
    (gen_random_uuid(), 'esign:cancel',             'Hủy phiên ký'),
    (gen_random_uuid(), 'user:manage',              'Quản lý người dùng và phân quyền'),
    (gen_random_uuid(), 'workflow:configure',       'Cấu hình quy trình phê duyệt'),
    (gen_random_uuid(), 'doctype:configure',        'Cấu hình loại hồ sơ'),
    (gen_random_uuid(), 'audit:view_all',           'Tra cứu toàn bộ nhật ký');

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on p.code in (
    'customer:read', 'customer:write',
    'contract:read', 'contract:write',
    'addendum:read', 'addendum:write',
    'pricelist:read', 'pricelist:write',
    'volume:read', 'statement:read', 'esign:send', 'esign:cancel')
where r.code = 'SALES_OFFICER';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on p.code in (
    'customer:read', 'customer:write',
    'contract:read', 'contract:write', 'contract:cancel_active',
    'addendum:read', 'addendum:write',
    'pricelist:read', 'pricelist:write',
    'volume:read', 'statement:read', 'esign:send', 'esign:cancel', 'approval:act')
where r.code = 'SALES_MANAGER';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on p.code in (
    'customer:read', 'contract:read', 'addendum:read', 'pricelist:read', 'approval:act')
where r.code = 'LEGAL_REVIEWER';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on p.code in (
    'customer:read', 'contract:read', 'pricelist:read', 'volume:read',
    'statement:read', 'statement:write', 'esign:send', 'esign:cancel', 'approval:act')
where r.code = 'ACCOUNTANT';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on p.code in (
    'contract:read', 'volume:read', 'volume:write', 'volume:lock_period')
where r.code = 'OPS_OFFICER';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r join permission p on (p.code like '%:read' or p.code = 'approval:act')
where r.code = 'DIRECTOR';

-- Admin is superuser: grant every permission (not just reads + admin perms)
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p
where r.code = 'SYSTEM_ADMIN'
on conflict do nothing;
