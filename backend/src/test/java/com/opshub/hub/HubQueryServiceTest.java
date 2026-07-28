package com.opshub.hub;

import com.opshub.hub.application.HubQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubQueryServiceTest {

    @SuppressWarnings("unchecked")
    private static RowMapper<HubQueryService.HubPlatformRow> captureRowMapper(JdbcTemplate jdbcTemplate) {
        org.mockito.ArgumentCaptor<RowMapper<HubQueryService.HubPlatformRow>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), captor.capture())).thenReturn(List.of());
        new HubQueryService(jdbcTemplate, Clock.systemUTC()).listHubs();
        return captor.getValue();
    }

    private static ResultSet row(UUID hubId, String name, Instant createdAt, String platform,
                                  String connectionStatus, String transport, boolean deviceReady,
                                  boolean runnerReady, Instant lastHeartbeatAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(hubId);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
        when(rs.getString("platform")).thenReturn(platform);
        when(rs.getString("connection_status")).thenReturn(connectionStatus);
        when(rs.getString("transport")).thenReturn(transport);
        when(rs.getBoolean("device_ready")).thenReturn(deviceReady);
        when(rs.getBoolean("runner_ready")).thenReturn(runnerReady);
        when(rs.getTimestamp("last_heartbeat_at")).thenReturn(lastHeartbeatAt == null ? null : Timestamp.from(lastHeartbeatAt));
        return rs;
    }

    @Test
    void groupsMultiplePlatformRowsUnderOneHubSummary() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID hubId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-27T09:00:00Z");
        Instant heartbeatAt = Instant.parse("2026-07-28T10:00:00Z");

        ResultSet androidRow = row(hubId, "my-hub", createdAt, "ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt);
        ResultSet webRow = row(hubId, "my-hub", createdAt, "WEB", "OFFLINE", "HTTPS_POLLING", false, false, null);

        RowMapper<HubQueryService.HubPlatformRow> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubPlatformRow mappedAndroid = mapper.mapRow(androidRow, 0);
        HubQueryService.HubPlatformRow mappedWeb = mapper.mapRow(webRow, 1);

        List<HubQueryService.HubSummary> summaries = HubQueryService.groupByHub(
                List.of(mappedAndroid, mappedWeb), Clock.fixed(heartbeatAt, ZoneOffset.UTC));

        assertThat(summaries).hasSize(1);
        HubQueryService.HubSummary hub = summaries.get(0);
        assertThat(hub.id()).isEqualTo(hubId);
        assertThat(hub.platforms()).hasSize(2);
        HubQueryService.PlatformStatus android = hub.platforms().stream()
                .filter(p -> p.platform().equals("ANDROID")).findFirst().orElseThrow();
        assertThat(android.connectionStatus()).isEqualTo("ONLINE");
        assertThat(android.deviceReady()).isTrue();
        HubQueryService.PlatformStatus web = hub.platforms().stream()
                .filter(p -> p.platform().equals("WEB")).findFirst().orElseThrow();
        assertThat(web.connectionStatus()).isEqualTo("OFFLINE");
        assertThat(web.lastHeartbeatAt()).isNull();
    }

    @Test
    void downgradesToOfflineWhenTheLastHeartbeatIsOlderThanTheStaleThreshold() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID hubId = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T14:58:13Z");
        Instant now = heartbeatAt.plusSeconds(61);

        ResultSet androidRow = row(hubId, "hub", heartbeatAt, "ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt);
        RowMapper<HubQueryService.HubPlatformRow> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubPlatformRow mapped = mapper.mapRow(androidRow, 0);

        List<HubQueryService.HubSummary> summaries = HubQueryService.groupByHub(
                List.of(mapped), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(summaries.get(0).platforms().get(0).connectionStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void ordersMostRecentlyCreatedHubsFirst() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class))).thenReturn(List.of());

        new HubQueryService(jdbcTemplate, Clock.systemUTC()).listHubs();

        String sql = sqlCaptor.getValue().toUpperCase();
        assertThat(sql).contains("ORDER BY");
        assertThat(sql).contains("CREATED_AT");
    }
}
