create table contract.signing_request_guard (
    document_type   varchar(32) not null,
    document_id     uuid not null,
    idempotency_key uuid not null,
    session_id      uuid,
    active          boolean not null,
    updated_at      timestamptz not null,
    primary key (document_type, document_id)
);

create unique index ux_signing_request_guard_key
    on contract.signing_request_guard (idempotency_key);

-- Preserve the oldest request that was queued but not dispatched at upgrade time, matching the
-- relay's oldest-first order. There is deliberately no
-- backfill for published rows: the contract database cannot distinguish an active legacy session
-- from a terminal one. If such an active session receives a one-time duplicate request, esign's
-- active-session unique constraint refuses it and the contract relay releases the newly made guard.
insert into contract.signing_request_guard
    (document_type, document_id, idempotency_key, session_id, active, updated_at)
select distinct on (aggregate_type, aggregate_id)
       aggregate_type,
       aggregate_id,
       (payload ->> 'idempotencyKey')::uuid,
       null,
       true,
       created_at
from contract.outbox
where event_type = 'esign.session_requested'
  and published_at is null
  and cancelled_at is null
order by aggregate_type, aggregate_id, created_at asc, id asc;
