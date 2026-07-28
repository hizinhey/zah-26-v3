package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks Hub connectivity and heartbeat state. A Hub is created on first contact (WebSocket
 * connect or first poll) so the Python Local Hub (Task 7) does not need a separate registration
 * step for this MVP.
 */
@Service
public class HubConnectionService {
    private final JdbcTemplate jdbcTemplate;

    public HubConnectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void markOnline(UUID hubId, String transport, String platform) {
        upsert(hubId, transport, "ONLINE", null, null, platform);
    }

    public void markOffline(UUID hubId) {
        jdbcTemplate.update("UPDATE hubs SET connection_status = 'OFFLINE' WHERE id = ?", hubId);
    }

    public void heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady, String platform) {
        upsert(hubId, transport, "ONLINE", deviceReady, runnerReady, platform);
    }

    private void upsert(UUID hubId, String transport, String status, Boolean deviceReady, Boolean runnerReady, String platform) {
        Timestamp heartbeatAt = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update("""
                        UPDATE hubs
                        SET connection_status = ?, transport = ?, last_heartbeat_at = ?, platform = ?,
                            device_ready = COALESCE(?, device_ready), runner_ready = COALESCE(?, runner_ready)
                        WHERE id = ?
                        """, status, transport, heartbeatAt, platform, deviceReady, runnerReady, hubId);
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO hubs (id, name, connection_status, transport, last_heartbeat_at, device_ready, runner_ready, platform)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, hubId, hubId.toString(), status, transport, heartbeatAt,
                    deviceReady != null && deviceReady, runnerReady != null && runnerReady, platform);
        }
    }
}
