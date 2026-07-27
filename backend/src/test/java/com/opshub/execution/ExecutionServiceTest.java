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
    void leasesExactlyOneActiveJobPerHubAndRenewsOnHeartbeat() {
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

        Optional<HubEnvelopeV1> secondOffer = executionService.offerNextJob(hubId);
        assertThat(secondOffer).isEmpty();

        UUID leaseToken = jdbcTemplate.queryForObject(
                "SELECT lease_token FROM job_leases WHERE hub_id = ?", UUID.class, hubId);
        boolean renewed = executionService.renewLease(hubId, leaseToken);
        assertThat(renewed).isTrue();
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
