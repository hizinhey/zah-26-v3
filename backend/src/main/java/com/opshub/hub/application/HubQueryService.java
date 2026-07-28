package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of Hub connectivity state for the operator-facing UI (see {@link HubConnectionService}
 * for the write side, updated on every Hub poll/heartbeat/WebSocket connect).
 */
@Service
public class HubQueryService {
    private final JdbcTemplate jdbcTemplate;

    public HubQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<HubSummary> listHubs() {
        return jdbcTemplate.query("""
                SELECT id, name, connection_status, transport, platform, device_ready, runner_ready,
                       last_heartbeat_at, created_at
                FROM hubs
                ORDER BY last_heartbeat_at DESC NULLS LAST, created_at DESC
                """,
                (rs, rowNum) -> new HubSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("connection_status"),
                        rs.getString("transport"),
                        rs.getString("platform"),
                        rs.getBoolean("device_ready"),
                        rs.getBoolean("runner_ready"),
                        rs.getTimestamp("last_heartbeat_at") == null ? null : rs.getTimestamp("last_heartbeat_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public record HubSummary(
            UUID id,
            String name,
            String connectionStatus,
            String transport,
            String platform,
            boolean deviceReady,
            boolean runnerReady,
            Instant lastHeartbeatAt,
            Instant createdAt) {
    }
}
