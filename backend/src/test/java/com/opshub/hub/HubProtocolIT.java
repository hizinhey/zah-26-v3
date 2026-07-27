package com.opshub.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.execution.application.ExecutionDto;
import com.opshub.execution.application.ExecutionService;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage proving the WebSocket transport and the HTTPS long-polling fallback speak
 * exactly the same {@link HubEnvelopeV1} protocol, per the task-6 brief.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HubProtocolIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String TOKEN = "dev-hub-token";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pollingFallbackOffersAndAcceptsAResultUsingTheHubEnvelope() throws Exception {
        UUID operationId = createApprovedOperation("MOB-800");
        ExecutionDto execution = executionService.start(operationId, 1, "poll-key-" + UUID.randomUUID());
        UUID hubId = UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hub-Token", TOKEN);
        ResponseEntity<HubEnvelopeV1> offerResponse = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=5",
                HttpMethod.GET, new HttpEntity<>(headers), HubEnvelopeV1.class, port, hubId);

        assertThat(offerResponse.getStatusCode().value()).isEqualTo(200);
        HubEnvelopeV1 offer = offerResponse.getBody();
        assertThat(offer).isNotNull();
        assertThat(offer.type()).isEqualTo(HubEnvelopeV1.TYPE_JOB_OFFERED);
        assertThat(offer.version()).isEqualTo(1);
        HubPayloads.JobOfferedPayload payload = objectMapper.convertValue(offer.payload(), HubPayloads.JobOfferedPayload.class);
        assertThat(payload.executionId()).isEqualTo(execution.id());
        assertThat(payload.testCases()).hasSize(5);

        UUID testCaseId = payload.testCases().get(0).testCaseId();
        HubEnvelopeV1 resultEnvelope = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 1200, null));
        ResponseEntity<Void> resultResponse = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/results",
                HttpMethod.POST, new HttpEntity<>(resultEnvelope, headers), Void.class, port, hubId);
        assertThat(resultResponse.getStatusCode().value()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT status FROM test_results WHERE execution_id = ? AND test_case_id = ?
                        """, String.class, execution.id(), testCaseId)).isEqualTo("PASSED");
    }

    @Test
    void pollingRejectsAnInvalidHubToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hub-Token", "wrong-token");
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=1",
                HttpMethod.GET, new HttpEntity<>(headers), String.class, port, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void webSocketDeliversTheSameJobOfferedEnvelopeShapeAsPolling() throws Exception {
        UUID operationId = createApprovedOperation("MOB-801");
        ExecutionDto execution = executionService.start(operationId, 1, "ws-key-" + UUID.randomUUID());
        UUID hubId = UUID.randomUUID();

        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        }, "ws://localhost:{port}/ws/v1/hubs/{hubId}?token={token}", port, hubId, TOKEN).get(5, TimeUnit.SECONDS);

        try {
            HubEnvelopeV1 heartbeat = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_HEARTBEAT,
                    new HubPayloads.HeartbeatPayload(true, true));
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(heartbeat)));

            String raw = received.poll(5, TimeUnit.SECONDS);
            assertThat(raw).isNotNull();
            HubEnvelopeV1 offer = objectMapper.readValue(raw, HubEnvelopeV1.class);
            assertThat(offer.type()).isEqualTo(HubEnvelopeV1.TYPE_JOB_OFFERED);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) offer.payload();
            assertThat(UUID.fromString((String) payload.get("executionId"))).isEqualTo(execution.id());
        } finally {
            session.close();
        }
    }

    private UUID createApprovedOperation(String jiraId) {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO operations (id, jira_id, revision, status, created_at, updated_at)
                        VALUES (?, ?, 1, 'DRAFT', now(), now())
                        """, operationId, jiraId);
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, 1, 'catalog-v1', 'READY', 'APPROVED')
                        """, planId, operationId);
        for (int order = 1; order <= 5; order++) {
            jdbcTemplate.update("""
                            INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                            VALUES (?, ?, 1, ?, ?, 1, 'sha', '{}', 'READY')
                            """, UUID.randomUUID(), planId, order, "android-template-" + order);
        }
        jdbcTemplate.update("""
                        UPDATE operations SET status = 'APPROVED', plan_id = ?, approved_plan_id = ? WHERE id = ?
                        """, planId, planId, operationId);
        return operationId;
    }
}
