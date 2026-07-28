package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of Hub connectivity state for the operator-facing UI (see {@link HubConnectionService}
 * for the write side, updated on every Hub poll/heartbeat/WebSocket connect). One Hub identity can
 * now run multiple platforms concurrently, so this groups the (hub, platform) rows in
 * hub_platforms into one HubSummary per hub_id with a list of per-platform statuses.
 */
@Service
public class HubQueryService {
    /**
     * A killed/crashed backend process never runs HubWebSocketHandler#afterConnectionClosed - the
     * only thing that calls HubConnectionService#markOffline - so hub_platforms.connection_status
     * can be stuck at ONLINE indefinitely for a Hub that's actually gone. 3x the Local Hub's 20s
     * heartbeat interval tolerates one missed beat (matching the "three consecutive failures"
     * threshold the Hub itself uses before failing over transports) without flapping the
     * indicator on jitter.
     */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HubQueryService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public List<HubSummary> listHubs() {
        List<HubPlatformRow> rows = jdbcTemplate.query("""
                SELECT h.id, h.name, h.created_at, hp.platform, hp.connection_status, hp.transport,
                       hp.device_ready, hp.runner_ready, hp.last_heartbeat_at
                FROM hubs h
                LEFT JOIN hub_platforms hp ON hp.hub_id = h.id
                ORDER BY h.created_at DESC, hp.platform ASC
                """,
                (rs, rowNum) -> new HubPlatformRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("platform"),
                        rs.getString("connection_status"),
                        rs.getString("transport"),
                        rs.getBoolean("device_ready"),
                        rs.getBoolean("runner_ready"),
                        rs.getTimestamp("last_heartbeat_at") == null ? null : rs.getTimestamp("last_heartbeat_at").toInstant()));
        return groupByHub(rows, clock);
    }

    public static List<HubSummary> groupByHub(List<HubPlatformRow> rows, Clock clock) {
        Map<UUID, List<HubPlatformRow>> byHub = new LinkedHashMap<>();
        for (HubPlatformRow row : rows) {
            byHub.computeIfAbsent(row.hubId(), id -> new ArrayList<>()).add(row);
        }
        List<HubSummary> summaries = new ArrayList<>();
        for (Map.Entry<UUID, List<HubPlatformRow>> entry : byHub.entrySet()) {
            List<HubPlatformRow> hubRows = entry.getValue();
            HubPlatformRow first = hubRows.get(0);
            List<PlatformStatus> platforms = new ArrayList<>();
            for (HubPlatformRow row : hubRows) {
                if (row.platform() == null) {
                    continue;
                }
                platforms.add(new PlatformStatus(row.platform(),
                        effectiveConnectionStatus(row.connectionStatus(), row.lastHeartbeatAt(), clock),
                        row.transport(), row.deviceReady(), row.runnerReady(), row.lastHeartbeatAt()));
            }
            summaries.add(new HubSummary(entry.getKey(), first.name(), first.createdAt(), platforms));
        }
        return summaries;
    }

    private static String effectiveConnectionStatus(String rawConnectionStatus, Instant lastHeartbeatAt, Clock clock) {
        if (!"ONLINE".equals(rawConnectionStatus)) {
            return rawConnectionStatus;
        }
        boolean stale = lastHeartbeatAt == null || Duration.between(lastHeartbeatAt, clock.instant()).compareTo(STALE_AFTER) > 0;
        return stale ? "OFFLINE" : "ONLINE";
    }

    /** One row of the hubs/hub_platforms LEFT JOIN, before grouping. */
    public record HubPlatformRow(
            UUID hubId, String name, Instant createdAt, String platform, String connectionStatus,
            String transport, boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt) {
    }

    public record PlatformStatus(
            String platform, String connectionStatus, String transport,
            boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt) {
    }

    public record HubSummary(UUID id, String name, Instant createdAt, List<PlatformStatus> platforms) {
    }
}
