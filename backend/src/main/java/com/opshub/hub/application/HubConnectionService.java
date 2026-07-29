package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks Hub connectivity and heartbeat state, one row per (hub, platform) in
 * hub_platforms - a Hub is created on first contact (WebSocket connect or first poll) so the
 * Python Local Hub does not need a separate registration step.
 */
@Service
public class HubConnectionService {
    private final JdbcTemplate jdbcTemplate;

    public HubConnectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String DEFAULT_PLATFORM = "ANDROID";

    public void markOnline(UUID hubId, String transport, String platform) {
        upsert(hubId, transport, "ONLINE", null, null, platform);
    }

    public void markOffline(UUID hubId, String platform) {
        jdbcTemplate.update(
                "UPDATE hub_platforms SET connection_status = 'OFFLINE' WHERE hub_id = ? AND platform = ?",
                hubId, platform);
    }

    public void heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady, String platform) {
        upsert(hubId, transport, "ONLINE", deviceReady, runnerReady, platform);
    }

    private void upsert(UUID hubId, String transport, String status, Boolean deviceReady, Boolean runnerReady, String platform) {
        Timestamp heartbeatAt = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                        INSERT INTO hubs (id, name, created_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """, hubId, hubId.toString(), heartbeatAt);

        // Single atomic upsert - hub_platforms has a real (hub_id, platform) primary key, so two
        // concurrent requests for the same new pair used to be able to both take an
        // INSERT-after-failed-UPDATE branch and collide on it. ON CONFLICT makes this race-free.
        // device_ready/runner_ready should only be overwritten when the caller actually supplied
        // a non-null value (heartbeat() always does; markOnline() passes null for both, meaning
        // "leave whatever was already stored, or default to false for a brand-new row").
        jdbcTemplate.update("""
                        INSERT INTO hub_platforms
                            (hub_id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
                        VALUES (?, ?, ?, ?, ?, COALESCE(?, FALSE), COALESCE(?, FALSE))
                        ON CONFLICT (hub_id, platform) DO UPDATE SET
                            connection_status = EXCLUDED.connection_status,
                            transport = EXCLUDED.transport,
                            last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                            device_ready = COALESCE(?, hub_platforms.device_ready),
                            runner_ready = COALESCE(?, hub_platforms.runner_ready)
                        """, hubId, platform, status, transport, heartbeatAt, deviceReady, runnerReady,
                deviceReady, runnerReady);
    }

    // The X-Hub-Platform header (or WS handshake attribute) is caller-supplied and not
    // otherwise validated - hub_platforms.platform has a CHECK constraint (ANDROID/WEB only), so
    // anything else would fail every write on this value. This is the single normalization
    // boundary: callers (HubWebSocketConfig.extractPlatform, HubPollingController) must run any
    // raw X-Hub-Platform value through this *before* using it anywhere - for the write path
    // (markOnline/heartbeat above, which now use the value as-is) AND for the read path
    // (ExecutionService/LeaseService's dispatch and lease-renewal queries). Normalizing only at
    // write time (the old behavior) let a stray/malformed header value write an "ANDROID" row
    // while a same-request read kept querying by the raw, un-normalized value - permanently
    // failing to find it. Normalizing once, here, at the boundary, keeps every downstream
    // consumer looking at the exact same canonical value.
    public static String normalizePlatform(String platform) {
        return "WEB".equals(platform) ? "WEB" : DEFAULT_PLATFORM;
    }
}
