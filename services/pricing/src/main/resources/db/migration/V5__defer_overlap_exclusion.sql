-- Truncate-then-approve (§9³) updates the predecessor and flips the successor to APPROVED in one
-- transaction. An immediate EXCLUDE is checked per statement, so the successor's flip trips it
-- before the predecessor's truncation is visible. Deferring the check to commit lets the pair land
-- together; the final committed state is still non-overlapping.
alter table pricing.price_list_version drop constraint excl_plv_overlap;
alter table pricing.price_list_version add constraint excl_plv_overlap
    exclude using gist (
        scope_key with =,
        daterange(valid_from, valid_to, '[]') with &&
    ) where (status in ('APPROVED','EFFECTIVE'))
    deferrable initially deferred;
