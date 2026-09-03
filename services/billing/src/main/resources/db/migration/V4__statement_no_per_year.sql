-- Per-year statement counter (registry §2: {PREFIX}-{YYYY}-{seq} from a per-type-per-year counter).
-- The previous global billing.statement_no_seq is kept for compatibility but no longer used.

CREATE TABLE IF NOT EXISTS billing.statement_no_counter (
    year INT PRIMARY KEY,
    last_no INT NOT NULL DEFAULT 0
);
