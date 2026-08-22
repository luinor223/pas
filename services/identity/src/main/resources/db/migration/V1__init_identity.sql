create table department (
    id   uuid primary key,
    code text not null unique,
    name text not null
);

create table app_user (
    id            uuid primary key,
    username      text not null unique,
    email         text not null unique,
    password_hash text not null,
    full_name     text not null,
    department_id uuid not null references department (id),
    status        text not null check (status in ('ACTIVE', 'DISABLED')),
    last_login_at timestamptz,
    created_at    timestamptz not null,
    created_by    uuid references app_user (id),
    updated_at    timestamptz not null,
    updated_by    uuid references app_user (id)
);

create index idx_app_user_department on app_user (department_id);

create table role (
    id   uuid primary key,
    code text not null unique,
    name text not null
);

create table permission (
    id          uuid primary key,
    code        text not null unique,
    description text
);

create table user_role (
    user_id uuid not null references app_user (id),
    role_id uuid not null references role (id),
    primary key (user_id, role_id)
);

create table role_permission (
    role_id       uuid not null references role (id),
    permission_id uuid not null references permission (id),
    primary key (role_id, permission_id)
);

create table outbox (
    id             uuid primary key,
    event_type     text not null,
    aggregate_type text not null,
    aggregate_id   uuid not null,
    payload        jsonb not null,
    created_at     timestamptz not null,
    claimed_at     timestamptz,
    published_at   timestamptz,
    cancelled_at   timestamptz,
    retry_count    int not null default 0
);

create index idx_outbox_unpublished on outbox (created_at)
    where published_at is null and cancelled_at is null;
