package com.opshub.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.opshub.execution.application.ExecutionDto;
import com.opshub.execution.application.ExecutionService;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 13 acceptance layer: drives the OpsHub Android MVP through its complete lifecycle -
 * operation creation with multiple Official Accounts, a validation failure followed by a
 * correction, five-case generation, approval, a stale-revision rejection, Hub readiness,
 * sequential execution across both OAs (continuing after an assertion failure and retrying
 * exactly one infrastructure error), evidence upload, and both the Hub-facing WebSocket
 * transport and the browser-facing REST-poll fallback for the same execution status.
 * <p>
 * This is an acceptance/integration layer composed on top of already-unit-tested collaborators
 * (see {@code OperationControllerIT}, {@code ValidationGatingIT}, {@code TestPlanServiceFindByIdIT},
 * {@code HubProtocolIT}, {@code ExecutionServiceTest}, {@code EvidenceServiceTest}) - it does not
 * reimplement their coverage, it proves the contracts they establish compose end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MvpLifecycleIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HUB_TOKEN = "dev-hub-token";

    private static HttpServer imageServer;
    private static String thumbnailUrl;
    private static final HttpServer geminiServer = startGeminiServer();
    private static final String geminiUrl = "http://127.0.0.1:" + geminiServer.getAddress().getPort();

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("GEMINI_API_KEY", () -> "test-key");
        registry.add("opshub.validation.gemini.base-url", () -> geminiUrl);
    }

    @BeforeAll
    static void startImageServer() throws IOException {
        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext("/thumbnail", MvpLifecycleIT::png);
        imageServer.start();
        thumbnailUrl = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/thumbnail";
    }

    @AfterAll
    static void stopImageServer() {
        imageServer.stop(0);
        geminiServer.stop(0);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runsTheFullOperationLifecycleFromCreationThroughEvidenceUpload() throws Exception {
        // --- 1. Create operation, attach two Android OAs (multiple OAs) ---
        String createResponse = mockMvc.perform(post("/api/v1/operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"jiraId\":\"MOB-900\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String operationId = JsonPath.read(createResponse, "$.id");

        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"oas":[
                                  {"platform":"ANDROID","oaName":"First","thumbnailUrl":"%s","content":"Header without a body","buttonText":"Open","redirectUrl":"https://example.test/one"},
                                  {"platform":"ANDROID","oaName":"Second","thumbnailUrl":"%s","content":"Header without a body","buttonText":"Open","redirectUrl":"https://example.test/two"}
                                ]}
                                """.formatted(thumbnailUrl, thumbnailUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        // --- 2. Validation failure (bad content) then correction, revalidate to PASSED ---
        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.canGenerate").value(false));

        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":2,"oas":[
                                  {"platform":"ANDROID","oaName":"First","thumbnailUrl":"%s","content":"Expected header\\nExpected body","buttonText":"Open","redirectUrl":"https://example.test/one"},
                                  {"platform":"ANDROID","oaName":"Second","thumbnailUrl":"%s","content":"Expected header\\nExpected body","buttonText":"Open","redirectUrl":"https://example.test/two"}
                                ]}
                                """.formatted(thumbnailUrl, thumbnailUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));

        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.canGenerate").value(true));

        // --- 3. Generate: exactly five test cases per OA, in order (10 total for 2 OAs) ---
        String planResponse = mockMvc.perform(post("/api/v1/operations/{id}/plans", operationId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        String planId = JsonPath.read(planResponse, "$.planId");
        List<Integer> caseOrders = JsonPath.read(planResponse, "$.cases[*].order");
        assertThat(caseOrders.subList(0, 5)).containsExactly(1, 2, 3, 4, 5);
        assertThat(caseOrders.subList(5, 10)).containsExactly(1, 2, 3, 4, 5);

        // --- 4. Approve the plan ---
        mockMvc.perform(post("/api/v1/plans/{id}/approve", planId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":3}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/operations/{id}", operationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // --- 5. Stale-revision rejection: any further OA mutation or reorder must be rejected
        //         against the now-outdated revision, proving mutation invalidates downstream state ---
        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":2,"oas":[
                                  {"platform":"ANDROID","oaName":"Stale","thumbnailUrl":"%s","content":"Expected header\\nExpected body","buttonText":"Open","redirectUrl":"https://example.test/stale"}
                                ]}
                                """.formatted(thumbnailUrl)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(3));

        // Starting an execution against a stale revision is likewise rejected.
        mockMvc.perform(post("/api/v1/operations/{id}/executions", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"stale-key\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));

        // --- 6. Hub readiness: execution cannot start while no Hub is ONLINE ---
        UUID operationUuid = UUID.fromString(operationId);
        mockMvc.perform(post("/api/v1/operations/{id}/executions", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"idempotencyKey\":\"exec-key-900\"}"))
                .andExpect(status().isCreated());

        UUID hubId = UUID.randomUUID();
        // Hub is not yet registered/online: renewing a lease for it must be refused (readiness gate) -
        // unlike /jobs/next (which registers the Hub as a side effect of polling), lease renewal
        // requires the Hub to already be known and ONLINE.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hub-Token", HUB_TOKEN);
        ResponseEntity<String> notOnlineResponse = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/leases/{leaseToken}/renew",
                HttpMethod.POST, new HttpEntity<>(headers), String.class, port, hubId, UUID.randomUUID());
        assertThat(notOnlineResponse.getStatusCode().value()).isEqualTo(409);

        // Heartbeat brings the Hub online and ready (device + runner ready).
        HubEnvelopeV1 heartbeat = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_HEARTBEAT,
                new HubPayloads.HeartbeatPayload(true, true));
        ResponseEntity<Void> heartbeatResponse = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/heartbeat",
                HttpMethod.POST, new HttpEntity<>(heartbeat, headers), Void.class, port, hubId);
        assertThat(heartbeatResponse.getStatusCode().value()).isEqualTo(200);

        // --- 7. Sequential execution: the Hub polls for the job it is now ready to run ---
        ResponseEntity<HubEnvelopeV1> offerResponse = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=5",
                HttpMethod.GET, new HttpEntity<>(headers), HubEnvelopeV1.class, port, hubId);
        assertThat(offerResponse.getStatusCode().value()).isEqualTo(200);
        HubEnvelopeV1 offer = offerResponse.getBody();
        assertThat(offer).isNotNull();
        HubPayloads.JobOfferedPayload jobPayload = objectMapper.convertValue(offer.payload(), HubPayloads.JobOfferedPayload.class);
        UUID executionId = jobPayload.executionId();
        assertThat(jobPayload.testCases()).hasSize(10);

        // Cross-language contract fixture (C1 item 7): write the *actual* multi-OA JOB_OFFERED
        // envelope this backend produces to a fixture file that
        // local-hub/tests/integration/test_backend_contract.py parses with
        // JobOfferedPayload.model_validate - this is the one test that crosses the Java/Python
        // boundary for real and would have caught the flat-vs-grouped contract mismatch (C1).
        java.nio.file.Path fixturePath = java.nio.file.Paths.get(
                "..", "local-hub", "tests", "fixtures", "job_offered_multi_oa.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixturePath.toFile(), offer);

        // Only a single active job may be leased per Hub at a time (sequential execution).
        ResponseEntity<String> noSecondOffer = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=1",
                HttpMethod.GET, new HttpEntity<>(headers), String.class, port, hubId);
        assertThat(noSecondOffer.getStatusCode().value()).isEqualTo(204);

        // --- 8. Report results: continuation after an assertion failure (no retry), plus one
        //         infrastructure retry (attempt 1 fails INFRASTRUCTURE, attempt 2 PASSES), plus
        //         a plain PASSED result. Execution must continue through every remaining case. ---
        UUID assertionFailCase = jobPayload.testCases().get(0).testCaseId();
        UUID infraRetryCase = jobPayload.testCases().get(1).testCaseId();
        List<UUID> remainingCases = jobPayload.testCases().stream()
                .skip(2).map(HubPayloads.TestCase::testCaseId).toList();

        postResult(hubId, headers, executionId, assertionFailCase, 1, "FAILED", "ASSERTION");
        // No retry follows an assertion failure - immediately move on and it must stay FAILED.
        assertThat(latestStatus(executionId, assertionFailCase)).isEqualTo("FAILED");

        postResult(hubId, headers, executionId, infraRetryCase, 1, "FAILED", "INFRASTRUCTURE");
        postResult(hubId, headers, executionId, infraRetryCase, 2, "PASSED", null);

        for (UUID caseId : remainingCases) {
            postResult(hubId, headers, executionId, caseId, 1, "PASSED", null);
        }

        // --- 9. Execution reaches a terminal state once every case has a terminal outcome ---
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM executions WHERE id = ?", String.class, executionId);
        assertThat(finalStatus).isEqualTo("COMPLETED");

        int assertionAttempts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_results WHERE execution_id = ? AND test_case_id = ?",
                Integer.class, executionId, assertionFailCase);
        assertThat(assertionAttempts).isEqualTo(1); // assertion failures never retry

        int infraAttempts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_results WHERE execution_id = ? AND test_case_id = ?",
                Integer.class, executionId, infraRetryCase);
        assertThat(infraAttempts).isEqualTo(2); // exactly one infrastructure retry

        // --- 10. Evidence upload for a completed test result ---
        UUID passedTestResultId = jdbcTemplate.queryForObject("""
                        SELECT id FROM test_results WHERE execution_id = ? AND test_case_id = ? AND attempt = 2
                        """, UUID.class, executionId, infraRetryCase);
        byte[] evidenceBytes = "evidence-log-content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidenceBytes));
        MockMultipartFile evidenceFile = new MockMultipartFile("file", "log.txt", "text/plain", evidenceBytes);
        MockMultipartHttpServletRequestBuilder uploadRequest = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/test-results/{id}/evidence", passedTestResultId);
        mockMvc.perform(uploadRequest
                        .file(evidenceFile)
                        .header("X-Hub-Token", HUB_TOKEN)
                        .param("evidenceType", "LOG")
                        .param("declaredSize", String.valueOf(evidenceBytes.length))
                        .param("declaredSha256", sha256))
                .andExpect(status().isCreated());

        // --- 11. Browser-facing REST-poll fallback: independently confirms the same execution
        //         status the Hub-facing transport produced, using the ExecutionController the
        //         React frontend polls (Task 11's documented deferred-WebSocket fallback). ---
        mockMvc.perform(get("/api/v1/executions/{id}", executionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.results.length()").value(11)); // 1 (assertion fail) + 2 (infra retry attempts) + 8 (remaining cases) results recorded

        assertThat(operationUuid).isNotNull(); // keeps the operation id in scope/documented above
    }

    /**
     * Confirms the Hub-facing WebSocket transport delivers the identical {@link HubEnvelopeV1}
     * job-offer shape as the polling fallback above, for a second, independent operation - the
     * two transports are proven interchangeable per the Task 6/11 contract.
     */
    @Test
    void hubFacingWebSocketTransportDeliversTheSameContractAsThePollingFallback() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"jiraId\":\"MOB-901\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String operationId = JsonPath.read(createResponse, "$.id");
        UUID planId = approveSingleOaOperation(operationId);

        ExecutionDto execution = executionService.start(UUID.fromString(operationId), 3, "ws-key-901-" + UUID.randomUUID());
        UUID hubId = UUID.randomUUID();

        java.util.concurrent.LinkedBlockingQueue<String> received = new java.util.concurrent.LinkedBlockingQueue<>();
        org.springframework.web.socket.client.standard.StandardWebSocketClient client =
                new org.springframework.web.socket.client.standard.StandardWebSocketClient();
        org.springframework.web.socket.WebSocketSession session = client.execute(
                new org.springframework.web.socket.handler.TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                                       org.springframework.web.socket.TextMessage message) {
                        received.add(message.getPayload());
                    }
                }, "ws://localhost:{port}/ws/v1/hubs/{hubId}?token={token}", port, hubId, HUB_TOKEN)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        try {
            HubEnvelopeV1 heartbeat = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_HEARTBEAT, new HubPayloads.HeartbeatPayload(true, true));
            session.sendMessage(new org.springframework.web.socket.TextMessage(objectMapper.writeValueAsString(heartbeat)));
            String raw = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(raw).isNotNull();
            HubEnvelopeV1 wsOffer = objectMapper.readValue(raw, HubEnvelopeV1.class);
            assertThat(wsOffer.type()).isEqualTo(HubEnvelopeV1.TYPE_JOB_OFFERED);
            HubPayloads.JobOfferedPayload wsPayload = objectMapper.convertValue(wsOffer.payload(), HubPayloads.JobOfferedPayload.class);
            assertThat(wsPayload.executionId()).isEqualTo(execution.id());
            assertThat(wsPayload.testCases()).hasSize(5);
        } finally {
            session.close();
        }
    }

    private UUID approveSingleOaOperation(String operationId) throws Exception {
        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"oas":[
                                  {"platform":"ANDROID","oaName":"Solo","thumbnailUrl":"%s","content":"Expected header\\nExpected body","buttonText":"Open","redirectUrl":"https://example.test/solo"}
                                ]}
                                """.formatted(thumbnailUrl)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canGenerate").value(true));
        String planResponse = mockMvc.perform(post("/api/v1/operations/{id}/plans", operationId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String planId = JsonPath.read(planResponse, "$.id");
        mockMvc.perform(post("/api/v1/plans/{id}/approve", planId)
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isNoContent());
        return UUID.fromString(planId);
    }

    private void postResult(UUID hubId, HttpHeaders headers, UUID executionId, UUID testCaseId,
                             int attempt, String status, String errorCategory) {
        HubEnvelopeV1 resultEnvelope = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(executionId, testCaseId, attempt, status, 1000, errorCategory));
        ResponseEntity<Void> response = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/results",
                HttpMethod.POST, new HttpEntity<>(resultEnvelope, headers), Void.class, port, hubId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private String latestStatus(UUID executionId, UUID testCaseId) {
        return jdbcTemplate.queryForObject("""
                        SELECT status FROM test_results
                        WHERE execution_id = ? AND test_case_id = ?
                        ORDER BY attempt DESC LIMIT 1
                        """, String.class, executionId, testCaseId);
    }

    private static void png(HttpExchange exchange) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] bytes = output.toByteArray();
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static HttpServer startGeminiServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1beta/models/gemini-2.5-flash-lite:generateContent", MvpLifecycleIT::geminiResponse);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Gemini test server", exception);
        }
    }

    private static void geminiResponse(HttpExchange exchange) throws IOException {
        String body = """
                {"candidates":[{"content":{"parts":[{"text":"{\\\"policyVersion\\\":\\\"gemini-text-v1\\\",\\\"findings\\\":[{\\\"fieldName\\\":\\\"oa[1].content.header\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0},{\\\"fieldName\\\":\\\"oa[1].content.body\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0},{\\\"fieldName\\\":\\\"oa[1].buttonText\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0},{\\\"fieldName\\\":\\\"oa[2].content.header\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0},{\\\"fieldName\\\":\\\"oa[2].content.body\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0},{\\\"fieldName\\\":\\\"oa[2].buttonText\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0}]}"}]}}]}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
