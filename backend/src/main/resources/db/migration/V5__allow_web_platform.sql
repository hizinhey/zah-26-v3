-- V1's official_accounts.platform column had an inline, unnamed CHECK (platform = 'ANDROID').
-- Postgres auto-names unnamed column-level CHECK constraints as <table>_<column>_check, so this
-- constraint is named official_accounts_platform_check. OperationService already validates and
-- accepts "WEB" official accounts at the Java layer (see UnsupportedPlatformException, which only
-- rejects platforms other than ANDROID/WEB), but the database itself still rejects any WEB row
-- outright. Relax the constraint to match: allow ANDROID or WEB, nothing else.
ALTER TABLE official_accounts
    DROP CONSTRAINT IF EXISTS official_accounts_platform_check;

ALTER TABLE official_accounts
    ADD CONSTRAINT official_accounts_platform_check CHECK (platform IN ('ANDROID', 'WEB'));
