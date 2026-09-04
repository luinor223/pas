-- requested_by is empty when the caller has no user context (a system-driven send),
-- so the snapshot column must allow null instead of forcing a fabricated uuid.
ALTER TABLE esign.signing_session ALTER COLUMN requested_by DROP NOT NULL;
