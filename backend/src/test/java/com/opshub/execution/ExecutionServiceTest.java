package com.opshub.execution;

import com.opshub.execution.application.ExecutionDto;
import com.opshub.execution.application.ExecutionService;
import com.opshub.execution.application.HubNotOnlineException;
import com.opshub.execution.application.LeaseService;
import com.opshub.execution.application.OperationNotApprovedException;
import com.opshub.hub.application.HubConnectionService;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import com.opshub.operation.application.RevisionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class ExecutionServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ExecutionService executionService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private HubConnectionService hubConnectionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void requiresTheOperationToBeCurrentlyApprovedBeforeStartingAnExecution() {
        UUID operationId = createDraftOperation("MOB-600");

        assertThatThrownBy(() -> executionService.start(operationId, 1, "key-1"))
                .isInstanceOf(OperationNotApprovedException.class);
    }

    @Test
    void rejectsAStaleRevisionWhenStartingAnExecution() {
        UUID operationId = createDraftOperation("MOB-601");
        UUID planId = approvePlan(operationId, 1);

        assertThatThrownBy(() -> executionService.start(operationId, 99, "key-2"))
                .isInstanceOfSatisfying(RevisionConflictException.class,
                        conflict -> assertThat(conflict.getCurrentRevision()).isEqualTo(1));
    }

    @Test
    void duplicateIdempotencyKeysReturnTheOriginalExecution() {
        UUID operationId = createDraftOperation("MOB-602");
        approvePlan(operationId, 1);

        ExecutionDto first = executionService.start(operationId, 1, "duplicate-key");
        ExecutionDto second = executionService.start(operationId, 1, "duplicate-key");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE idempotency_key = ?", Integer.class, "duplicate-key"))
                .isEqualTo(1);
    }

    @Test
    void requiresAnOnlineHubBeforeOfferingAJob() {
        UUID operationId = createDraftOperation("MOB-603");
        approvePlan(operationId, 1);
        executionService.start(operationId, 1, "key-offline");
        UUID hubId = UUID.randomUUID();

        assertThatThrownBy(() -> executionService.offerNextJob(hubId))
                .isInstanceOf(HubNotOnlineException.class);
    }

    @Test
    void leasesExactlyOneActiveJobPerHub() {
        UUID operationId = createDraftOperation("MOB-604");
        approvePlan(operationId, 1);
        executionService.start(operationId, 1, "key-lease");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");

        Optional<HubEnvelopeV1> firstOffer = executionService.offerNextJob(hubId);
        assertThat(firstOffer).isPresent();
        assertThat(firstOffer.get().type()).isEqualTo(HubEnvelopeV1.TYPE_JOB_OFFERED);
        HubPayloads.JobOfferedPayload payload = (HubPayloads.JobOfferedPayload) firstOffer.get().payload();
        assertThat(payload.testCases()).hasSize(5);
        assertThat(payload.leaseToken()).isNotNull();

        Optional<HubEnvelopeV1> secondOffer = executionService.offerNextJob(hubId);
        assertThat(secondOffer).isEmpty();
    }

    /**
     * Regression coverage for a review finding on the first cut of this test: it queried
     * {@code lease_token} straight out of the DB and called {@code executionService.renewLease(...)}
     * directly, which proved the CAS-renewal SQL works but never exercised the actual heartbeat
     * path a real Hub uses. Real end-to-end coverage - heartbeat over both the WebSocket and HTTPS
     * polling transports actually extending the lease's expiry - lives in
     * {@code com.opshub.hub.HubProtocolIT}, which drives the real
     * {@code HubWebSocketHandler}/{@code HubPollingController} endpoints. This test instead proves
     * the lease-token-free, hub-ID-keyed renewal entry point those handlers call
     * ({@link ExecutionService#renewActiveLease}) is itself correct.
     */
    @Test
    void renewActiveLeaseExtendsTheHubsCurrentLeaseWithoutNeedingTheToken() {
        UUID operationId = createDraftOperation("MOB-606");
        approvePlan(operationId, 1);
        executionService.start(operationId, 1, "key-renew-active");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");

        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId);
        assertThat(offer).isPresent();

        Instant expiresBefore = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM job_leases WHERE hub_id = ?", Instant.class, hubId);

        boolean renewed = executionService.renewActiveLease(hubId);
        assertThat(renewed).isTrue();

        Instant expiresAfter = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM job_leases WHERE hub_id = ?", Instant.class, hubId);
        assertThat(expiresAfter).isAfter(expiresBefore);
    }

    @Test
    void renewActiveLeaseIsANoOpWhenTheHubHasNoLease() {
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");

        assertThat(executionService.renewActiveLease(hubId)).isFalse();
    }

    @Test
    void expiredJobsCanBeReofferedWithoutDuplicatingStoredResults() {
        UUID operationId = createDraftOperation("MOB-605");
        UUID planId = approvePlan(operationId, 1);
        ExecutionDto execution = executionService.start(operationId, 1, "key-expire");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");

        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId);
        assertThat(offer).isPresent();
        UUID testCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM test_cases WHERE plan_id = ? ORDER BY case_order LIMIT 1", UUID.class, planId);

        executionService.recordResult(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 500, null)));

        // Simulate the lease expiring without the Hub finishing the remaining test cases.
        jdbcTemplate.update("UPDATE job_leases SET expires_at = ? WHERE hub_id = ?", Instant.now().minusSeconds(5), hubId);

        Optional<HubEnvelopeV1> reoffer = executionService.offerNextJob(hubId);
        assertThat(reoffer).isPresent();

        executionService.recordResult(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 750, null)));

        Integer storedResultsForCase = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM test_results WHERE execution_id = ? AND test_case_id = ?
                        """, Integer.class, execution.id(), testCaseId);
        assertThat(storedResultsForCase).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM test_results WHERE execution_id = ? AND test_case_id = ?",
                        String.class, execution.id(), testCaseId))
                .isEqualTo("PASSED");
    }

    @Test
    void recordResultComputesADeterministicTestResultIdFromExecutionTestCaseAndAttempt() {
        UUID operationId = createDraftOperation("MOB-606");
        UUID planId = approvePlan(operationId, 1);
        ExecutionDto execution = executionService.start(operationId, 1, "key-deterministic-id");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");
        executionService.offerNextJob(hubId);
        UUID testCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM test_cases WHERE plan_id = ? ORDER BY case_order LIMIT 1", UUID.class, planId);

        executionService.recordResult(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 500, null)));

        UUID expectedTestResultId = UUID.nameUUIDFromBytes(
                (execution.id() + ":" + testCaseId + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID storedTestResultId = jdbcTemplate.queryForObject(
                "SELECT id FROM test_results WHERE execution_id = ? AND test_case_id = ? AND attempt = 1",
                UUID.class, execution.id(), testCaseId);
        assertThat(storedTestResultId).isEqualTo(expectedTestResultId);

        // Idempotent redelivery of the same result must upsert the same row (same id), not
        // create a second one - the Hub can retry safely without evidence uploads targeting
        // a stale or duplicate test_results.id.
        executionService.recordResult(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 600, null)));
        UUID storedTestResultIdAfterRedelivery = jdbcTemplate.queryForObject(
                "SELECT id FROM test_results WHERE execution_id = ? AND test_case_id = ? AND attempt = 1",
                UUID.class, execution.id(), testCaseId);
        assertThat(storedTestResultIdAfterRedelivery).isEqualTo(expectedTestResultId);
    }

    @Test
    void deterministicTestResultIdMatchesTheHubSideReferenceValue() {
        // Cross-language parity check with opshub_hub.tests.test_evidence's equivalent
        // case. Both this literal and the Python one were independently derived by
        // hand-tracing the MD5 + version/variant bit-twiddling algorithm; if both tests
        // pass with the same literal, the two implementations are proven byte-for-byte
        // identical for this input.
        UUID executionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID testCaseId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        int attempt = 1;

        UUID result = UUID.nameUUIDFromBytes(
                (executionId + ":" + testCaseId + ":" + attempt).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(UUID.fromString("060241da-e04f-3366-bf26-c1a67252bb48"));
    }

    @Test
    void findByIdReturnsExecutionStatusWithTestResultsIncludingDeterministicIds() {
        UUID operationId = createDraftOperation("MOB-607");
        UUID planId = approvePlan(operationId, 1);
        ExecutionDto execution = executionService.start(operationId, 1, "key-find-by-id");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");
        executionService.offerNextJob(hubId);
        UUID testCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM test_cases WHERE plan_id = ? ORDER BY case_order LIMIT 1", UUID.class, planId);
        executionService.recordResult(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_TEST_RESULT,
                new HubPayloads.TestResultPayload(execution.id(), testCaseId, 1, "PASSED", 500, null)));

        ExecutionService.ExecutionStatusDto status = executionService.findById(execution.id());

        assertThat(status.execution().id()).isEqualTo(execution.id());
        assertThat(status.results()).hasSize(1);
        ExecutionService.TestResultDto result = status.results().get(0);
        assertThat(result.testCaseId()).isEqualTo(testCaseId);
        assertThat(result.status()).isEqualTo("PASSED");
        UUID expectedTestResultId = UUID.nameUUIDFromBytes(
                (execution.id() + ":" + testCaseId + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(result.id()).isEqualTo(expectedTestResultId);
    }

    @Test
    void sweepAbandonedExecutionsFailsARunningExecutionWhoseLeaseExpiredWellPastTheGracePeriod() {
        UUID operationId = createDraftOperation("MOB-610");
        approvePlan(operationId, 1);
        ExecutionDto execution = executionService.start(operationId, 1, "key-abandoned");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");
        assertThat(executionService.offerNextJob(hubId)).isPresent();

        // Simulate a Hub that died mid-run: its lease expired long past the sweep's grace period,
        // and nothing ever renewed or replaced it.
        jdbcTemplate.update("UPDATE job_leases SET expires_at = ? WHERE hub_id = ?",
                Instant.now().minus(ExecutionService.ABANDONED_EXECUTION_GRACE_PERIOD).minusSeconds(60), hubId);

        executionService.sweepAbandonedExecutions();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM executions WHERE id = ?", String.class, execution.id());
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void sweepAbandonedExecutionsLeavesARecentlyExpiredLeaseAloneWithinTheGracePeriod() {
        UUID operationId = createDraftOperation("MOB-611");
        approvePlan(operationId, 1);
        ExecutionDto execution = executionService.start(operationId, 1, "key-not-yet-abandoned");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");
        assertThat(executionService.offerNextJob(hubId)).isPresent();

        // Lease expired a moment ago, well within the grace period - still eligible for re-offer,
        // not yet abandoned.
        jdbcTemplate.update("UPDATE job_leases SET expires_at = ? WHERE hub_id = ?",
                Instant.now().minusSeconds(5), hubId);

        executionService.sweepAbandonedExecutions();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM executions WHERE id = ?", String.class, execution.id());
        assertThat(status).isEqualTo("RUNNING");
    }

    @Test
    void findByIdThrowsWhenExecutionDoesNotExist() {
        assertThatThrownBy(() -> executionService.findById(UUID.randomUUID()))
                .isInstanceOf(com.opshub.execution.application.ExecutionNotFoundException.class);
    }

    private UUID createDraftOperation(String jiraId) {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO operations (id, jira_id, revision, status, created_at, updated_at)
                        VALUES (?, ?, 1, 'DRAFT', now(), now())
                        """, operationId, jiraId);
        return operationId;
    }

    private UUID approvePlan(UUID operationId, int revision) {
        UUID planId = UUID.randomUUID();
        // buildJobOfferedEnvelope joins test_cases -> official_accounts on (operation_id,
        // oa_order) to populate the C1 oaOrder/oaName fields, so a row here is required for
        // offerNextJob to return any test cases at all.
        jdbcTemplate.update("""
                        INSERT INTO official_accounts (id, operation_id, oa_order, platform, oa_name, thumbnail_url, content, button_text, redirect_url)
                        VALUES (?, ?, 1, 'ANDROID', 'Test OA', 'https://example.test/thumb.png', 'content', 'Open', 'https://example.test/redirect')
                        """, UUID.randomUUID(), operationId);
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, ?, 'catalog-v1', 'READY', 'APPROVED')
                        """, planId, operationId, revision);
        for (int order = 1; order <= 5; order++) {
            jdbcTemplate.update("""
                            INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                            VALUES (?, ?, 1, ?, ?, 1, 'sha', '{}', 'READY')
                            """, UUID.randomUUID(), planId, order, "android-template-" + order);
        }
        jdbcTemplate.update("""
                        UPDATE operations SET status = 'APPROVED', plan_id = ?, approved_plan_id = ?, revision = ?
                        WHERE id = ?
                        """, planId, planId, revision, operationId);
        return planId;
    }
}
