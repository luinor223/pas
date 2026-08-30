-- Fix 16: LIKE 'VOL-YYYY-%' fallback scan needs prefix index; sequence is primary but keep efficient fallback
create index if not exists idx_volume_record_record_no_prefix on operations.volume_record(record_no text_pattern_ops);
