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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * normalize()/normalizePlatform() used to coerce any non-"WEB" value to "ANDROID" only when
     * *writing* to hub_platforms, while dispatch/lease reads used the raw caller-supplied
     * platform - so a stray/lowercase/malformed X-Hub-Platform value wrote an "ANDROID" row that
     * a same-request read (by the raw value) could never find again. Normalization now happens
     * once, at the HTTP/WS boundary (HubWebSocketConfig.extractPlatform,
     * HubPollingController), so HubConnectionService itself only ever sees an
     * already-normalized value - this just pins down that {@link HubConnectionService#normalizePlatform}
     * itself is deterministic and produces only "ANDROID" or "WEB", which every caller relies on.
     */
    @Test
    void normalizePlatformAlwaysProducesExactlyAndroidOrWeb() {
        assertThat(HubConnectionService.normalizePlatform("WEB")).isEqualTo("WEB");
        assertThat(HubConnectionService.normalizePlatform("ANDROID")).isEqualTo("ANDROID");
        assertThat(HubConnectionService.normalizePlatform("android")).isEqualTo("ANDROID");
        assertThat(HubConnectionService.normalizePlatform("web")).isEqualTo("ANDROID");
        assertThat(HubConnectionService.normalizePlatform("chrome")).isEqualTo("ANDROID");
        assertThat(HubConnectionService.normalizePlatform(null)).isEqualTo("ANDROID");
    }

    /**
     * Regression test for the race in the old UPDATE-then-INSERT-if-zero-rows upsert: two
     * concurrent first-contacts for the same brand-new (hub_id, platform) pair could both take
     * the INSERT branch and one would hit a primary-key violation. The current
     * INSERT ... ON CONFLICT (hub_id, platform) DO UPDATE is a single atomic statement, so this
     * must never throw and must always leave exactly one row behind.
     */
    @Test
    void concurrentFirstContactForTheSameNewHubAndPlatformNeverThrows() throws Exception {
        UUID hubId = UUID.randomUUID();
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startingGun = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startingGun.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                Integer.class, hubId);
        assertThat(rowCount).isEqualTo(1);
    }
}
