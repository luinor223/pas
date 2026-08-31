-- D9 expiry warning: stamp the version once the warning has been acked, so a healthy run does not
-- re-publish every sweep. A lost warning (crash before the stamp) simply re-fires next run.
alter table pricing.price_list_version add column expiry_warned_at timestamptz;
