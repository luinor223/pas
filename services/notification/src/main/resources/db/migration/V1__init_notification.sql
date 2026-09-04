-- Notification schema (db-notification.md) — pas_notification / schema notification.
-- A pure sink: every row here is written by consuming pas.events. No outbox, no audit_log,
-- no versioning — notifications are not business documents.
create schema if not exists notification;

-- notification — one row per recipient per event (4.9). title/body are write-time snapshots,
-- so a notification stays readable after the source document is renamed or cancelled.
create table notification.notification (
    id                uuid primary key default gen_random_uuid(),
    recipient_user_id uuid not null,
    category          text not null check (category in ('APPROVAL','ESIGN','EXPIRY','SYSTEM')),
    -- traces the row to its source event; not unique, one event fans out to many recipients
    event_id          uuid not null,
    event_type        text not null,
    document_type     text,
    document_id       uuid,
    document_no       text,
    title             text not null,
    body              text not null,
    read_at           timestamptz,
    created_at        timestamptz not null default now(),
    created_by        uuid,
    updated_at        timestamptz not null default now(),
    updated_by        uuid
);

-- the inbox list and the unread badge, which are the only two reads this service serves
create index idx_notification_inbox
    on notification.notification(recipient_user_id, read_at);
-- the list is ordered newest-first and the tabs filter on category
create index idx_notification_inbox_recent
    on notification.notification(recipient_user_id, created_at desc);
create index idx_notification_category
    on notification.notification(recipient_user_id, category);
-- redelivery lookups and the fan-out's own dedup check
create index idx_notification_event on notification.notification(event_id);

-- processed_event — D6. Kafka commits the offset after processing, so a mid-batch death
-- re-reads; without this the inbox doubles. Written in the same transaction as the rows above.
create table notification.processed_event (
    event_id     uuid primary key,
    processed_at timestamptz not null default now()
);
