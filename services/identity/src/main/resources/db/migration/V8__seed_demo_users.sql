-- Demo users for runbook (password = username, BCrypt strength 10).
-- Seeded via migration so `make down-v && make up` is ready to demo without manual user creation.
-- All demo passwords are the username itself (e.g. sales_officer / sales_officer) for easy copy-paste.
-- See the "Demo accounts" table in README.md for the role mapping.

-- Privileged demo role for the special permissions (volume:edit_locked, statement:cancel_approved)
-- that are in no default bundle (registry §7). Assigned in addition to OPS_OFFICER.
insert into role (id, code, name) values
    ('889d4ec9-1225-4d2f-99d2-b0b6a2d69df2', 'DEMO_PRIVILEGE', 'Demo Privileged (volume edit locked + cancel approved)')
on conflict (code) do nothing;

insert into role_permission (role_id, permission_id)
select '889d4ec9-1225-4d2f-99d2-b0b6a2d69df2'::uuid, p.id from permission p where p.code = 'volume:edit_locked'
on conflict do nothing;
insert into role_permission (role_id, permission_id)
select '889d4ec9-1225-4d2f-99d2-b0b6a2d69df2'::uuid, p.id from permission p where p.code = 'statement:cancel_approved'
on conflict do nothing;

-- sales_officer
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  'be2f867f-33f6-422e-9ad4-cc3b608d3df1', 'sales_officer', 'sales.officer@pas.test',
  '$2b$10$qyf/rjwGkPn3R8NNZrLfzOTfHINyzhpiAgcz5jEBLk47yC1QbC3Qi', 'Nguyen Van Sale',
  (select id from department where code = 'SALES'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select 'be2f867f-33f6-422e-9ad4-cc3b608d3df1'::uuid, r.id from role r where r.code = 'SALES_OFFICER'
on conflict do nothing;

-- sales_manager
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  'bd6fad4e-5c9a-4d9b-81ad-c14f00f1c3be', 'sales_manager', 'sales.manager@pas.test',
  '$2b$10$AB9WPhzDlRHU5Rgycu.QVO.PeQrywS3Jvp1KdtXuv1DGtQHtNsSES', 'Tran Thi Manager',
  (select id from department where code = 'SALES'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select 'bd6fad4e-5c9a-4d9b-81ad-c14f00f1c3be'::uuid, r.id from role r where r.code = 'SALES_MANAGER'
on conflict do nothing;

-- legal_reviewer
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  'b6344825-7b14-4ec3-ad2a-a1f110335980', 'legal_reviewer', 'legal.reviewer@pas.test',
  '$2b$10$HOzp2c8WumArvQBEU2QDluoGzn3X0VUATf7GJizBUqLlk812VDYGa', 'Le Van Legal',
  (select id from department where code = 'LEGAL'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select 'b6344825-7b14-4ec3-ad2a-a1f110335980'::uuid, r.id from role r where r.code = 'LEGAL_REVIEWER'
on conflict do nothing;

-- director
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  '03510331-c0bc-4894-b68b-6ba206fae388', 'director', 'director@pas.test',
  '$2b$10$FJsG05.Q7.34O51fXtikTu6y13dG8pbdiWi1gVEwUfZPLdflK89gW', 'Pham Thi Director',
  (select id from department where code = 'BOARD'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select '03510331-c0bc-4894-b68b-6ba206fae388'::uuid, r.id from role r where r.code = 'DIRECTOR'
on conflict do nothing;

-- accountant
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  '824244a6-d082-486c-9ef4-81332da12c63', 'accountant', 'accountant@pas.test',
  '$2b$10$cQXng7PApt4HlABCVLjj2ejIyxBdnlYGnb2gsLNc.dKN3X6zd8yLq', 'Hoang Van Accountant',
  (select id from department where code = 'ACCOUNTING'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select '824244a6-d082-486c-9ef4-81332da12c63'::uuid, r.id from role r where r.code = 'ACCOUNTANT'
on conflict do nothing;

-- ops_officer
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  'fbdf6a6f-8f7b-4114-9d6e-9507960c4cfc', 'ops_officer', 'ops.officer@pas.test',
  '$2b$10$JZfrmGPhIp1g/ixKQ8T3suIMg9FXNvJx5V4hwNUkLdxBHtcbmpnae', 'Do Thi Ops',
  (select id from department where code = 'OPERATIONS'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select 'fbdf6a6f-8f7b-4114-9d6e-9507960c4cfc'::uuid, r.id from role r where r.code = 'OPS_OFFICER'
on conflict do nothing;

-- ops_privileged (OPS_OFFICER + DEMO_PRIVILEGE for the two special perms)
insert into app_user (id, username, email, password_hash, full_name, department_id, status, created_at, updated_at)
values (
  '9a5b845c-ca02-4ec4-9faf-72ecd47d30d7', 'ops_privileged', 'ops.privileged@pas.test',
  '$2b$10$7iE6j6eXc.Vi3SVBhjO8eeNSmJ6KEOsUYLCwWEzbXaABkZLIPwhR2', 'Privileged Ops',
  (select id from department where code = 'OPERATIONS'), 'ACTIVE', now(), now()
) on conflict (username) do nothing;
insert into user_role (user_id, role_id)
select '9a5b845c-ca02-4ec4-9faf-72ecd47d30d7'::uuid, r.id from role r where r.code = 'OPS_OFFICER'
on conflict do nothing;
insert into user_role (user_id, role_id)
select '9a5b845c-ca02-4ec4-9faf-72ecd47d30d7'::uuid, r.id from role r where r.code = 'DEMO_PRIVILEGE'
on conflict do nothing;
