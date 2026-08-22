create table refresh_token (
    id           uuid primary key,
    user_id      uuid not null references app_user (id),
    family_id    uuid not null,
    token_hash   text not null unique,
    issued_at    timestamptz not null,
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    replaced_by  uuid references refresh_token (id)
);

create index idx_refresh_token_user on refresh_token (user_id);
create index idx_refresh_token_family on refresh_token (family_id);
create index idx_refresh_token_active on refresh_token (user_id)
    where revoked_at is null;
