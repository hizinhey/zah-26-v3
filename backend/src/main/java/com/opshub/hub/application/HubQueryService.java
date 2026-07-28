package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of Hub connectivity state for the operator-facing UI (see {@link HubConnectionService}
 * for the write side, updated on every Hub poll/heartbeat/WebSocket connect).
 */
@Service
public class HubQueryService {
    /**
     * A killed/crashed backend process never runs HubWebSocketHandler#afterConnectionClosed - the
     * only thing that calls HubConnectionService#markOffline - so hubs.connection_status can be
     * stuck at ONLINE indefinitely for a Hub that's actually gone. 3x the Local Hub's 20s heartbeat
     * interval tolerates one missed beat (matching the "three consecutive failures" threshold the
     * Hub itself uses before failing over transports) without flapping the indicator on jitter.
     */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HubQueryService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public List<HubSummary> listHubs() {
        return jdbcTemplate.query("""
                SELECT id, name, connection_status, transport, platform, device_ready, runner_ready,
                       last_heartbeat_at, created_at
                FROM hubs
                ORDER BY last_heartbeat_at DESC NULLS LAST, created_at DESC
                """,
                (rs, rowNum) -> {
                    Instant lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at") == null
                            ? null : rs.getTimestamp("last_heartbeat_at").toInstant();
                    return new HubSummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            effectiveConnectionStatus(rs.getString("connection_status"), lastHeartbeatAt),
                            rs.getString("transport"),
                            rs.getString("platform"),
                            rs.getBoolean("device_ready"),
                            rs.getBoolean("runner_ready"),
                            lastHeartbeatAt,
                            rs.getTimestamp("created_at").toInstant());
                });
    }

    private String effectiveConnectionStatus(String rawConnectionStatus, Instant lastHeartbeatAt) {
        if (!"ONLINE".equals(rawConnectionStatus)) {
            return rawConnectionStatus;
        }
        boolean stale = lastHeartbeatAt == null || Duration.between(lastHeartbeatAt, clock.instant()).compareTo(STALE_AFTER) > 0;
        return stale ? "OFFLINE" : "ONLINE";
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
