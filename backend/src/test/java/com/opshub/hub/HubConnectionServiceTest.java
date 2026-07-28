package com.opshub.hub;

import com.opshub.hub.application.HubConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class HubConnectionServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private HubConnectionService hubConnectionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void marksOnlineIndependentlyPerPlatformUnderTheSameHubId() {
        UUID hubId = UUID.randomUUID();

        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hub_platforms WHERE hub_id = ?", Integer.class, hubId);
        assertThat(rowCount).isEqualTo(2);

        String androidStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                String.class, hubId);
        assertThat(androidStatus).isEqualTo("ONLINE");
    }

    @Test
    void heartbeatUpdatesOnlyItsOwnPlatformsReadiness() {
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        hubConnectionService.heartbeat(hubId, "WEBSOCKET", true, true, "ANDROID");
        hubConnectionService.heartbeat(hubId, "WEBSOCKET", false, false, "WEB");

        Boolean androidDeviceReady = jdbcTemplate.queryForObject(
                "SELECT device_ready FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                Boolean.class, hubId);
        Boolean webDeviceReady = jdbcTemplate.queryForObject(
                "SELECT device_ready FROM hub_platforms WHERE hub_id = ? AND platform = 'WEB'",
                Boolean.class, hubId);
        assertThat(androidDeviceReady).isTrue();
        assertThat(webDeviceReady).isFalse();
    }

    @Test
    void markOfflineAffectsOnlyTheGivenPlatform() {
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        hubConnectionService.markOffline(hubId, "ANDROID");

        String androidStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                String.class, hubId);
        String webStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'WEB'",
                String.class, hubId);
        assertThat(androidStatus).isEqualTo("OFFLINE");
        assertThat(webStatus).isEqualTo("ONLINE");
    }
}
