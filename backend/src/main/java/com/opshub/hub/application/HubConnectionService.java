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
                hubId, normalize(platform));
    }

    public void heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady, String platform) {
        upsert(hubId, transport, "ONLINE", deviceReady, runnerReady, platform);
    }

    private void upsert(UUID hubId, String transport, String status, Boolean deviceReady, Boolean runnerReady, String platform) {
        String normalizedPlatform = normalize(platform);
        Timestamp heartbeatAt = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                        INSERT INTO hubs (id, name, created_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """, hubId, hubId.toString(), heartbeatAt);

        int updated = jdbcTemplate.update("""
                        UPDATE hub_platforms
                        SET connection_status = ?, transport = ?, last_heartbeat_at = ?,
                            device_ready = COALESCE(?, device_ready), runner_ready = COALESCE(?, runner_ready)
                        WHERE hub_id = ? AND platform = ?
                        """, status, transport, heartbeatAt, deviceReady, runnerReady, hubId, normalizedPlatform);
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO hub_platforms
                                (hub_id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, hubId, normalizedPlatform, status, transport, heartbeatAt,
                    deviceReady != null && deviceReady, runnerReady != null && runnerReady);
        }
    }

    // The X-Hub-Platform header (or WS handshake attribute) is caller-supplied and not
    // otherwise validated before reaching here - hub_platforms.platform has a CHECK
    // constraint (ANDROID/WEB only), so anything else would fail this call on every
    // poll/heartbeat. Fall back to the documented default rather than let a stray/malformed
    // header value take a platform permanently un-upsertable.
    private static String normalize(String platform) {
        return "WEB".equals(platform) ? "WEB" : DEFAULT_PLATFORM;
    }
}
