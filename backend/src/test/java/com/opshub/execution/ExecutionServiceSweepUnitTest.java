package com.opshub.execution;

import com.opshub.execution.application.ExecutionService;
import com.opshub.execution.application.LeaseService;
import com.opshub.execution.application.WebWorkerLauncher;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit coverage of {@link ExecutionService#sweepAbandonedExecutions()}'s staleness decision,
 * exercised with a mocked {@link JdbcTemplate} rather than a real database - this environment's
 * Docker daemon rejects the Testcontainers version used by {@link ExecutionServiceTest} entirely
 * (unrelated to this change), so this is the only way to get a real RED/GREEN cycle on the fix for
 * the 2026-07-28 incident (see the javadoc on sweepAbandonedExecutions for the full story).
 */
class ExecutionServiceSweepUnitTest {

    @SuppressWarnings("unchecked")
    private static void stubOneRunningExecution(JdbcTemplate jdbcTemplate, UUID executionId, Instant startedAt) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id")).thenReturn(executionId);
        when(row.getTimestamp("started_at")).thenReturn(Timestamp.from(startedAt));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
    }

    @Test
    void marksAnExecutionFailedWhenItStartedWellPastTheGracePeriodWithNoActivity() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LeaseService leaseService = mock(LeaseService.class);
        ExecutionService executionService = new ExecutionService(jdbcTemplate, leaseService, mock(WebWorkerLauncher.class));
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now().minus(ExecutionService.ABANDONED_EXECUTION_GRACE_PERIOD).minusSeconds(60);
        stubOneRunningExecution(jdbcTemplate, executionId, startedAt);

        executionService.sweepAbandonedExecutions();

        verify(jdbcTemplate).update(contains("FAILED"), any(Timestamp.class), eq(executionId));
        verify(leaseService).release(executionId);
    }

    @Test
    void leavesAnExecutionAloneWhenItStartedRecently() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LeaseService leaseService = mock(LeaseService.class);
        ExecutionService executionService = new ExecutionService(jdbcTemplate, leaseService, mock(WebWorkerLauncher.class));
        UUID executionId = UUID.randomUUID();
        stubOneRunningExecution(jdbcTemplate, executionId, Instant.now());

        executionService.sweepAbandonedExecutions();

        verify(jdbcTemplate, never()).update(contains("FAILED"), any(), any());
        verify(leaseService, never()).release(executionId);
    }

    /**
     * The actual production bug: renewActiveLease is called on every heartbeat regardless of
     * whether the Hub is still working the leased execution, so job_leases.expires_at alone can
     * never prove an execution is genuinely still alive. This proves the fix doesn't reference
     * lease state at all - a long-abandoned execution is failed purely off its own started_at /
     * last-message activity, independent of anything renewActiveLease did to the lease row.
     */
    @Test
    void marksAnExecutionFailedEvenWhileLeaseServiceReportsItsLeaseAsRecentlyRenewed() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LeaseService leaseService = mock(LeaseService.class);
        // Simulates what a Hub's regular heartbeat does via renewActiveLease - kept entirely
        // separate from the sweep's own inputs to prove the sweep no longer looks at lease state
        // at all, which is the actual fix (see the class javadoc).
        when(leaseService.renewActiveLease(any())).thenReturn(true);
        ExecutionService executionService = new ExecutionService(jdbcTemplate, leaseService, mock(WebWorkerLauncher.class));
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now().minus(ExecutionService.ABANDONED_EXECUTION_GRACE_PERIOD).minusSeconds(60);
        stubOneRunningExecution(jdbcTemplate, executionId, startedAt);

        assertThat(leaseService.renewActiveLease(UUID.randomUUID())).isTrue();
        executionService.sweepAbandonedExecutions();

        verify(jdbcTemplate).update(contains("FAILED"), any(Timestamp.class), eq(executionId));
        verify(leaseService).release(executionId);
    }

    @Test
    void leavesAnExecutionAloneWhenItHasRecentGenuineProgressDespiteStartingLongAgo() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LeaseService leaseService = mock(LeaseService.class);
        ExecutionService executionService = new ExecutionService(jdbcTemplate, leaseService, mock(WebWorkerLauncher.class));
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now().minus(ExecutionService.ABANDONED_EXECUTION_GRACE_PERIOD).minusSeconds(60);
        stubOneRunningExecution(jdbcTemplate, executionId, startedAt);

        executionService.recordProgress(HubEnvelopeV1.of(HubEnvelopeV1.TYPE_JOB_PROGRESS,
                new HubPayloads.JobProgressPayload(executionId, UUID.randomUUID(), "RUNNING", "Executing")));

        executionService.sweepAbandonedExecutions();

        verify(jdbcTemplate, never()).update(contains("FAILED"), any(), any());
        verify(leaseService, never()).release(executionId);
    }
}
