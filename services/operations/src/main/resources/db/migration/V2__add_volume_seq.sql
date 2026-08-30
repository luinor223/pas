-- Per-year counter fix for finding P0-1: global sequence avoids O(N) scan and race.
-- Registry 00-registry.md:39 requires {PREFIX}-{YYYY}-{seq} unique per year; global seq with year prefix guarantees uniqueness
-- and is monotonic. If strict per-year reset is required later, replace with volume_record_counter table + SELECT FOR UPDATE.
create sequence if not exists operations.volume_record_no_seq start 1;
