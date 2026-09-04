-- Fix raw_payload type: jsonb -> text (Hibernate maps as String, not as PGobject)
ALTER TABLE esign.signing_callback_log ALTER COLUMN raw_payload TYPE TEXT;
