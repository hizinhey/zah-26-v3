-- The Hub only ever reported a coarse errorCategory enum for a failed/errored test result -
-- there was no way to see *why* it actually failed (e.g. a genuinely empty OA field vs an
-- Appium session drop) without SSHing into the Hub host and reading its local log file.
-- TestResultPayload.errorMessage (Local Hub side) now carries a short, human-readable summary -
-- persist it alongside error_category.
ALTER TABLE test_results
    ADD COLUMN error_message TEXT;
