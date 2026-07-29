-- A Hub's connection/readiness state used to be one column per hub row, assuming exactly
-- one platform per Hub. It now lives here, one row per (hub, platform), so two platforms
-- under the same hub_id can independently connect/disconnect/report readiness without
-- clobbering each other (see docs/superpowers/specs/2026-07-28-unified-multiplatform-hub-design.md).
CREATE TABLE hub_platforms (
    hub_id UUID NOT NULL REFERENCES hubs(id),
    platform VARCHAR(16) NOT NULL CHECK (platform IN ('ANDROID', 'WEB')),
    connection_status VARCHAR(64) NOT NULL,
    transport VARCHAR(64) NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    device_ready BOOLEAN NOT NULL DEFAULT FALSE,
    runner_ready BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (hub_id, platform)
);

INSERT INTO hub_platforms (hub_id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
SELECT id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready
FROM hubs;

ALTER TABLE hubs
    DROP COLUMN connection_status,
    DROP COLUMN transport,
    DROP COLUMN last_heartbeat_at,
    DROP COLUMN device_ready,
    DROP COLUMN runner_ready,
    DROP COLUMN platform;

-- job_leases: one active lease per (hub, platform) instead of per hub - the actual
-- concurrency unlock, letting a Hub hold an ANDROID lease and a WEB lease at once.
ALTER TABLE job_leases
    ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'ANDROID' CHECK (platform IN ('ANDROID', 'WEB'));

-- The default above only exists to backfill any pre-existing rows at migration time above -
-- going forward, LeaseService.acquire always supplies platform explicitly, so no default
-- should linger to silently misfile a future INSERT that forgets to specify one.
ALTER TABLE job_leases ALTER COLUMN platform DROP DEFAULT;

ALTER TABLE job_leases DROP CONSTRAINT job_leases_hub_id_unique;
ALTER TABLE job_leases ADD CONSTRAINT job_leases_hub_id_platform_unique UNIQUE (hub_id, platform);
