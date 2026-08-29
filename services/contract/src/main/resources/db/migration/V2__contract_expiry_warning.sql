-- D9 expiry warning bookkeeping.
--
-- The stamp is the expiry DATE already warned for, not a timestamp of the last warning: a
-- TERM_EXTENSION addendum moves contract.valid_to forward, and a timestamp would suppress the
-- warning for the new term for ever (warned in 2026, extended to 2027, silent in 2027). Comparing
-- against valid_to makes the extension reset the warning by construction, with nothing to
-- remember to clear when the parent effects are applied.
--
-- Null = never warned. document.expiring is a direct publish with no outbox row, so this column
-- is the only record that it went out; it is stamped only after Kafka acks, and a failed send
-- therefore re-warns on the next sweep (D9 self-healing).
alter table contract.contract
    add column last_expiry_warning_for date;

-- the D9 warning sweep: ACTIVE contracts inside the warning horizon, minus those already warned
-- for the valid_to they currently carry
create index idx_contract_expiry_warning
    on contract.contract(status, valid_to, last_expiry_warning_for);
