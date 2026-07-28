package com.opshub.hub;

import com.opshub.hub.application.HubQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubQueryServiceTest {

    @SuppressWarnings("unchecked")
    private static RowMapper<HubQueryService.HubSummary> captureRowMapper(JdbcTemplate jdbcTemplate) {
        org.mockito.ArgumentCaptor<RowMapper<HubQueryService.HubSummary>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), captor.capture())).thenReturn(List.of());
        new HubQueryService(jdbcTemplate).listHubs();
        return captor.getValue();
    }

    @Test
    void mapsEveryColumnOfAConnectedHub() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant createdAt = Instant.parse("2026-07-27T09:00:00Z");

        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(id);
        when(row.getString("name")).thenReturn("3c75ce1d-...");
        when(row.getString("connection_status")).thenReturn("ONLINE");
        when(row.getString("transport")).thenReturn("WEBSOCKET");
        when(row.getString("platform")).thenReturn("ANDROID");
        when(row.getBoolean("device_ready")).thenReturn(true);
        when(row.getBoolean("runner_ready")).thenReturn(false);
        when(row.getTimestamp("last_heartbeat_at")).thenReturn(Timestamp.from(heartbeatAt));
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));

        RowMapper<HubQueryService.HubSummary> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubSummary hub = mapper.mapRow(row, 0);

        assertThat(hub.id()).isEqualTo(id);
        assertThat(hub.name()).isEqualTo("3c75ce1d-...");
        assertThat(hub.connectionStatus()).isEqualTo("ONLINE");
        assertThat(hub.transport()).isEqualTo("WEBSOCKET");
        assertThat(hub.platform()).isEqualTo("ANDROID");
        assertThat(hub.deviceReady()).isTrue();
        assertThat(hub.runnerReady()).isFalse();
        assertThat(hub.lastHeartbeatAt()).isEqualTo(heartbeatAt);
        assertThat(hub.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void mapsAMissingLastHeartbeatToNullRatherThanThrowing() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getString("name")).thenReturn("hub");
        when(row.getString("connection_status")).thenReturn("OFFLINE");
        when(row.getString("transport")).thenReturn("HTTPS_POLLING");
        when(row.getString("platform")).thenReturn("ANDROID");
        when(row.getBoolean("device_ready")).thenReturn(false);
        when(row.getBoolean("runner_ready")).thenReturn(false);
        when(row.getTimestamp("last_heartbeat_at")).thenReturn(null);
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-07-27T09:00:00Z")));

        RowMapper<HubQueryService.HubSummary> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubSummary hub = mapper.mapRow(row, 0);

        assertThat(hub.lastHeartbeatAt()).isNull();
    }

    @Test
    void ordersMostRecentlyActiveHubsFirst() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class))).thenReturn(List.of());

        new HubQueryService(jdbcTemplate).listHubs();

        String sql = sqlCaptor.getValue().toUpperCase();
        assertThat(sql).contains("ORDER BY");
        assertThat(sql).contains("LAST_HEARTBEAT_AT");
    }
}
