-- P0-1 + P1 residual: global sequence avoids O(N) scan and race.
-- Known deviation from 00-registry.md:39 {PREFIX}-{YYYY}-{seq} per-type-per-year:
-- global seq is monotonic across years (e.g. VOL-2026-0010 -> VOL-2027-0002), not reset per year.
-- Uniqueness per year is still guaranteed (year prefix), and tests only assert startsWith("VOL-YYYY-").
-- Strict per-year reset would need operations.volume_record_counter(year text primary key, next_seq int) + SELECT FOR UPDATE.
-- Keep global for simplicity; documented as intentional deviation.
create sequence if not exists operations.volume_record_no_seq start 1;
