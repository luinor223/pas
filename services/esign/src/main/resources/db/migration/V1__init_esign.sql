-- esign schema: signing sessions, callback log, status history, outbox

CREATE SCHEMA IF NOT EXISTS esign;

-- Signing session: generic over (document_type_code, document_id)
CREATE TABLE esign.signing_session (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_no          TEXT NOT NULL UNIQUE,
    document_type_code  TEXT NOT NULL,
    document_id         UUID NOT NULL,
    document_no         TEXT,
    customer_name       TEXT,
    signer_name         TEXT NOT NULL,
    signer_email        TEXT NOT NULL,
    provider            TEXT NOT NULL DEFAULT 'MockSign',
    provider_ref        TEXT,
    idempotency_key     UUID NOT NULL UNIQUE,
    status              TEXT NOT NULL DEFAULT 'PENDING_SEND'
                        CHECK (status IN ('PENDING_SEND','SIGNING','SIGNED','FAILED','CANCELLED')),
    attempts            INT NOT NULL DEFAULT 0,
    last_error          TEXT,
    requested_by        UUID NOT NULL,
    requested_by_name   TEXT,
    version             INT NOT NULL DEFAULT 0,
    sent_at             TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One active session per document
CREATE UNIQUE INDEX idx_signing_session_active
    ON esign.signing_session (document_type_code, document_id)
    WHERE status IN ('PENDING_SEND', 'SIGNING');

-- Signing callback log: raw provider webhooks
CREATE TABLE esign.signing_callback_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID REFERENCES esign.signing_session(id),
    provider_ref    TEXT,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    result          TEXT,
    raw_payload     JSONB
);

-- Status history: append-only, per session
CREATE TABLE esign.status_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES esign.signing_session(id),
    from_status     TEXT,
    to_status       TEXT NOT NULL,
    trigger_kind    TEXT NOT NULL,
    trigger_ref     UUID,
    actor_id        UUID,
    actor_name      TEXT,
    note            TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_session ON esign.status_history (session_id, occurred_at);

-- Outbox: event relay table
CREATE TABLE esign.outbox (
    id              UUID PRIMARY KEY,
    event_type      TEXT NOT NULL,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    retry_count     INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON esign.outbox (created_at)
    WHERE published_at IS NULL AND cancelled_at IS NULL;
