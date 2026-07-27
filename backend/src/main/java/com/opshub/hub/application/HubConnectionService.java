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

    public void markOnline(UUID hubId, String transport) {
        upsert(hubId, transport, "ONLINE", null, null);
    }

    public void markOffline(UUID hubId) {
        jdbcTemplate.update("UPDATE hubs SET connection_status = 'OFFLINE' WHERE id = ?", hubId);
    }

    public void heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady) {
        upsert(hubId, transport, "ONLINE", deviceReady, runnerReady);
    }

    private void upsert(UUID hubId, String transport, String status, Boolean deviceReady, Boolean runnerReady) {
        Timestamp heartbeatAt = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update("""
                        UPDATE hubs
                        SET connection_status = ?, transport = ?, last_heartbeat_at = ?,
                            device_ready = COALESCE(?, device_ready), runner_ready = COALESCE(?, runner_ready)
                        WHERE id = ?
                        """, status, transport, heartbeatAt, deviceReady, runnerReady, hubId);
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO hubs (id, name, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, hubId, hubId.toString(), status, transport, heartbeatAt,
                    deviceReady != null && deviceReady, runnerReady != null && runnerReady);
        }
    }
}
