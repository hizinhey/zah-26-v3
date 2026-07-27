-- I6 fix: test_results.duration_ms was INTEGER NOT NULL, but the wire type is a 64-bit `long`
-- (Java HubPayloads.TestResultPayload.durationMs) / unbounded int (Python
-- TestResultPayload.durationMs). Not reachable at realistic test durations, but the column
-- should match the wire contract rather than silently truncate on overflow.
ALTER TABLE test_results
    ALTER COLUMN duration_ms TYPE BIGINT;
