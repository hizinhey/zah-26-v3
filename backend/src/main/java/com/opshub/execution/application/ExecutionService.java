package com.opshub.execution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import com.opshub.operation.application.OperationNotFoundException;
import com.opshub.operation.application.RevisionConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The execution queue: admits new executions only against the currently approved plan/revision,
 * offers exactly one leased job per online Hub at a time, and records progress/results reported
 * back over either transport using the shared {@link HubEnvelopeV1} payloads.
 * <p>
 * Execution continues through every test case even after an assertion failure; only infrastructure
 * errors are eligible for a single retry. Both behaviors are driven by the Hub, which is the
 * component that actually runs the tests - this service simply persists whatever attempts/results
 * are reported and only closes the execution once every test case has reached a terminal outcome.
 */
@Service
public class ExecutionService {
    private final JdbcTemplate jdbcTemplate;
    private final LeaseService leaseService;
    private final WebWorkerLauncher webWorkerLauncher;
    private final ObjectMapper objectMapper;

    /** Guards against concurrent job offers racing each other for the same Hub. */
    private final ConcurrentHashMap<UUID, ReentrantLock> hubLocks = new ConcurrentHashMap<>();
    /** Tracks the last accepted message timestamp per execution to enforce monotonic delivery. */
    private final ConcurrentHashMap<UUID, Instant> lastMessageTimestamps = new ConcurrentHashMap<>();

    private static final RowMapper<ExecutionDto> EXECUTION_ROW_MAPPER = (rs, rowNum) -> new ExecutionDto(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("operation_id"),
            (UUID) rs.getObject("plan_id"),
            rs.getInt("source_revision"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getTimestamp("queued_at").toInstant()
    );

    @Autowired
    public ExecutionService(JdbcTemplate jdbcTemplate, LeaseService leaseService, WebWorkerLauncher webWorkerLauncher) {
        this(jdbcTemplate, leaseService, webWorkerLauncher, new ObjectMapper());
    }

    ExecutionService(JdbcTemplate jdbcTemplate, LeaseService leaseService, WebWorkerLauncher webWorkerLauncher, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseService = leaseService;
        this.webWorkerLauncher = webWorkerLauncher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExecutionDto start(UUID operationId, int expectedRevision, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Optional<ExecutionDto> existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        OperationApprovalRow operation = jdbcTemplate.query("""
                        SELECT revision, status, approved_plan_id FROM operations WHERE id = ?
                        """, rs -> rs.next()
                        ? new OperationApprovalRow(rs.getInt("revision"), rs.getString("status"), (UUID) rs.getObject("approved_plan_id"))
                        : null,
                operationId);
        if (operation == null) {
            throw new OperationNotFoundException(operationId);
        }
        if (operation.revision() != expectedRevision) {
            throw new RevisionConflictException(operation.revision());
        }
        if (!"APPROVED".equals(operation.status()) || operation.approvedPlanId() == null) {
            throw new OperationNotApprovedException(operationId);
        }

        UUID executionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO executions (id, operation_id, plan_id, source_revision, idempotency_key, status, queued_at)
                        VALUES (?, ?, ?, ?, ?, 'QUEUED', ?)
                        """, executionId, operationId, operation.approvedPlanId(), expectedRevision, idempotencyKey,
                Timestamp.from(now));
        String platform = jdbcTemplate.queryForObject(
                "SELECT platform FROM official_accounts WHERE operation_id = ? LIMIT 1", String.class, operationId);
        if ("WEB".equals(platform)) {
            webWorkerLauncher.launchIfNeeded();
        }
        return new ExecutionDto(executionId, operationId, operation.approvedPlanId(), expectedRevision, idempotencyKey, "QUEUED", now);
    }

    public ExecutionStatusDto findById(UUID executionId) {
        ExecutionDto execution = jdbcTemplate.query("""
                        SELECT id, operation_id, plan_id, source_revision, idempotency_key, status, queued_at
                        FROM executions WHERE id = ?
                        """, EXECUTION_ROW_MAPPER, executionId)
                .stream().findFirst()
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
        List<TestResultDto> results = jdbcTemplate.query("""
                        SELECT id, test_case_id, attempt, status, duration_ms, error_category
                        FROM test_results WHERE execution_id = ? ORDER BY test_case_id, attempt
                        """, (rs, rowNum) -> new TestResultDto(
                        (UUID) rs.getObject("id"), (UUID) rs.getObject("test_case_id"), rs.getInt("attempt"),
                        rs.getString("status"), (Long) rs.getObject("duration_ms"), rs.getString("error_category")),
                executionId);
        return new ExecutionStatusDto(execution, results);
    }

    // I6 fix: duration_ms is NOT NULL BIGINT on the wire and in the DB (V4__widen_duration_ms.sql)
    // - read as Long, not the previously nullable Integer, which was the more clearly wrong side
    // of that mismatch (a NOT NULL column read into a nullable narrower type).
    public record TestResultDto(UUID id, UUID testCaseId, int attempt, String status, Long durationMs, String errorCategory) {
    }

    public record ExecutionStatusDto(ExecutionDto execution, List<TestResultDto> results) {
    }

    public Optional<ExecutionDto> findByIdempotencyKey(String idempotencyKey) {
        List<ExecutionDto> rows = jdbcTemplate.query("""
                        SELECT id, operation_id, plan_id, source_revision, idempotency_key, status, queued_at
                        FROM executions WHERE idempotency_key = ?
                        """, EXECUTION_ROW_MAPPER, idempotencyKey);
        return rows.stream().findFirst();
    }

    /**
     * Offers the next queued/expired job to the given Hub, honoring the "one active job leased
     * per Hub" invariant. Returns empty when the Hub is not online, already holds a live lease,
     * or there is nothing to offer.
     */
    @Transactional
    public Optional<HubEnvelopeV1> offerNextJob(UUID hubId) {
        requireHubOnline(hubId);
        ReentrantLock lock = hubLocks.computeIfAbsent(hubId, id -> new ReentrantLock());
        lock.lock();
        try {
            if (leaseService.hasActiveLease(hubId)) {
                return Optional.empty();
            }
            Optional<UUID> candidate = leaseService.nextOfferableExecution();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            UUID executionId = candidate.get();
            UUID leaseToken;
            try {
                leaseToken = leaseService.acquire(hubId, executionId);
            } catch (org.springframework.dao.DataIntegrityViolationException lostRace) {
                // Another backend instance won the race to lease this Hub first (enforced by the
                // job_leases_hub_id_unique DB constraint) - nothing to offer from this instance.
                return Optional.empty();
            }
            jdbcTemplate.update("""
                            UPDATE executions
                            SET status = 'RUNNING', hub_id = ?, started_at = COALESCE(started_at, ?)
                            WHERE id = ?
                            """, hubId, Timestamp.from(Instant.now()), executionId);
            return Optional.of(buildJobOfferedEnvelope(executionId, leaseToken));
        } finally {
            lock.unlock();
        }
    }

    public boolean renewLease(UUID hubId, UUID leaseToken) {
        requireHubOnline(hubId);
        return leaseService.renew(hubId, leaseToken);
    }

    /**
     * Renews whichever lease is currently active for the Hub, without requiring the Hub to echo
     * the lease token back. Used by heartbeat handling on both transports so a Hub only needs to
     * keep sending heartbeats (already required to stay ONLINE) to keep a long-running job's lease
     * alive - it does not need to separately track and resend the lease token on every heartbeat.
     * Returns {@code true} when a lease was found and renewed, {@code false} when the Hub has no
     * active lease (nothing to renew - not an error).
     */
    public boolean renewActiveLease(UUID hubId) {
        requireHubOnline(hubId);
        return leaseService.renewActiveLease(hubId);
    }

    /**
     * Rejects envelopes reported out of order for the same execution while allowing exact
     * duplicate redelivery (idempotent retry from the Hub) to pass through as a no-op.
     */
    public void recordProgress(HubEnvelopeV1 envelope) {
        HubPayloads.JobProgressPayload payload = objectMapper.convertValue(envelope.payload(), HubPayloads.JobProgressPayload.class);
        requireMonotonic(payload.executionId(), envelope.timestamp());
    }

    @Transactional
    public void recordResult(HubEnvelopeV1 envelope) {
        HubPayloads.TestResultPayload payload = objectMapper.convertValue(envelope.payload(), HubPayloads.TestResultPayload.class);
        requireMonotonic(payload.executionId(), envelope.timestamp());

        jdbcTemplate.update("""
                        INSERT INTO test_results (id, execution_id, test_case_id, attempt, status, duration_ms, error_category)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (execution_id, test_case_id, attempt)
                        DO UPDATE SET status = EXCLUDED.status, duration_ms = EXCLUDED.duration_ms, error_category = EXCLUDED.error_category
                        """, testResultId(payload.executionId(), payload.testCaseId(), payload.attempt()),
                payload.executionId(), payload.testCaseId(), payload.attempt(),
                payload.status(), payload.durationMs(), payload.errorCategory());

        completeIfEveryTestCaseReachedATerminalOutcome(payload.executionId());
    }

    /**
     * Deterministic {@code test_results.id} computed from {@code (executionId, testCaseId,
     * attempt)} rather than randomly generated, so the Hub can compute the same id locally
     * (see {@code opshub_hub.evidence.deterministic_test_result_id}) and upload evidence for
     * it without waiting for an acknowledgement carrying a server-generated id. Uses
     * {@link UUID#nameUUIDFromBytes(byte[])}, which implements MD5-based name-based UUID
     * generation (version 3) - identical, byte-for-byte, to the algorithm reimplemented on
     * the Hub side in Python (MD5 the UTF-8 bytes of the canonical string, then set the
     * version/variant bits).
     */
    private static UUID testResultId(UUID executionId, UUID testCaseId, int attempt) {
        String canonical = executionId + ":" + testCaseId + ":" + attempt;
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private void completeIfEveryTestCaseReachedATerminalOutcome(UUID executionId) {
        Integer pending = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM test_cases test_case
                        JOIN executions execution ON execution.plan_id = test_case.plan_id
                        WHERE execution.id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM test_results result
                              WHERE result.execution_id = execution.id
                                AND result.test_case_id = test_case.id
                                AND (
                                    result.status = 'PASSED'
                                    OR result.error_category IS DISTINCT FROM 'INFRASTRUCTURE'
                                    OR result.attempt >= 2
                                )
                          )
                        """, Integer.class, executionId);
        if (pending != null && pending == 0) {
            jdbcTemplate.update("""
                            UPDATE executions SET status = 'COMPLETED', finished_at = ? WHERE id = ?
                            """, Timestamp.from(Instant.now()), executionId);
            leaseService.release(executionId);
            // Bound the lifetime of the monotonic-delivery tracker to the execution: once every
            // test case has reached a terminal outcome there is nothing left to reorder-check, and
            // holding the entry forever would leak memory for every execution that ever ran.
            lastMessageTimestamps.remove(executionId);
        }
    }

    private void requireMonotonic(UUID executionId, Instant timestamp) {
        Instant previous = lastMessageTimestamps.get(executionId);
        if (previous != null && timestamp.isBefore(previous)) {
            // I5 fix: a dedicated exception (mapped to 409 by the controllers, not the bare
            // 500 an unmapped IllegalStateException produced) - a 5xx looks retryable to the
            // Hub's Outbox.flush, which would then retry a permanently-rejected envelope forever.
            throw new MonotonicOrderViolationException(executionId,
                    "Message received out of order for execution " + executionId);
        }
        lastMessageTimestamps.put(executionId, timestamp);
    }

    /**
     * Grace period past a lease's expiry before its execution is considered abandoned rather
     * than merely awaiting re-offer. {@link LeaseService#nextOfferableExecution} already makes a
     * RUNNING execution with an expired lease re-offerable immediately (so a Hub that's still
     * alive but slow can pick it back up); this sweep only steps in once that hasn't happened for
     * a while, on the assumption the Hub that held the lease is actually gone.
     */
    public static final java.time.Duration ABANDONED_EXECUTION_GRACE_PERIOD = java.time.Duration.ofMinutes(10);

    /**
     * I4 fix: previously nothing ever marked an execution FAILED if its lease expired with no
     * Hub reclaiming it - a dead Hub mid-run left the execution RUNNING forever, endlessly
     * re-offered from case 1 by {@link LeaseService#nextOfferableExecution}. Runs periodically
     * (Spring {@code @Scheduled}, matching how this codebase would otherwise introduce a
     * background task - there was no prior {@code @Scheduled} usage to follow, so this
     * establishes the pattern) and fails any RUNNING execution whose most recent lease expired
     * more than {@link #ABANDONED_EXECUTION_GRACE_PERIOD} ago with nothing renewing it since.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void sweepAbandonedExecutions() {
        Instant cutoff = Instant.now().minus(ABANDONED_EXECUTION_GRACE_PERIOD);
        List<UUID> abandoned = jdbcTemplate.query("""
                        SELECT execution.id
                        FROM executions execution
                        WHERE execution.status = 'RUNNING'
                          AND execution.finished_at IS NULL
                          AND EXISTS (
                              SELECT 1 FROM job_leases lease
                              WHERE lease.execution_id = execution.id AND lease.expires_at <= ?
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM job_leases lease
                              WHERE lease.execution_id = execution.id AND lease.expires_at > ?
                          )
                        """, (rs, rowNum) -> (UUID) rs.getObject("id"), Timestamp.from(cutoff),
                Timestamp.from(Instant.now()));

        for (UUID executionId : abandoned) {
            jdbcTemplate.update("""
                            UPDATE executions SET status = 'FAILED', finished_at = ? WHERE id = ?
                            """, Timestamp.from(Instant.now()), executionId);
            leaseService.release(executionId);
            lastMessageTimestamps.remove(executionId);
        }
    }

    private void requireHubOnline(UUID hubId) {
        String status = jdbcTemplate.query("SELECT connection_status FROM hubs WHERE id = ?",
                rs -> rs.next() ? rs.getString("connection_status") : null, hubId);
        if (!"ONLINE".equals(status)) {
            throw new HubNotOnlineException(hubId);
        }
    }

    private HubEnvelopeV1 buildJobOfferedEnvelope(UUID executionId, UUID leaseToken) {
        ExecutionRow execution = jdbcTemplate.query("""
                        SELECT id, plan_id, source_revision, idempotency_key FROM executions WHERE id = ?
                        """, rs -> rs.next()
                        ? new ExecutionRow((UUID) rs.getObject("plan_id"), rs.getInt("source_revision"), rs.getString("idempotency_key"))
                        : null,
                executionId);
        List<HubPayloads.TestCase> testCases = jdbcTemplate.query("""
                        SELECT test_case.id, test_case.oa_order, test_case.case_order, test_case.template_id,
                               test_case.template_version, test_case.parameters, oa.oa_name
                        FROM test_cases test_case
                        JOIN test_plans plan ON plan.id = test_case.plan_id
                        JOIN official_accounts oa ON oa.operation_id = plan.operation_id AND oa.oa_order = test_case.oa_order
                        WHERE test_case.plan_id = ?
                        ORDER BY test_case.oa_order, test_case.case_order
                        """, (rs, rowNum) -> {
                    try {
                        JsonNode parameters = objectMapper.readTree(rs.getString("parameters"));
                        return new HubPayloads.TestCase(
                                (UUID) rs.getObject("id"), rs.getInt("oa_order"), rs.getString("oa_name"),
                                rs.getInt("case_order"), rs.getString("template_id"),
                                rs.getInt("template_version"), objectMapper.convertValue(parameters, Object.class));
                    } catch (Exception exception) {
                        throw new IllegalStateException("Could not parse stored template parameters", exception);
                    }
                }, execution.planId());

        String platform = jdbcTemplate.queryForObject("""
                        SELECT DISTINCT oa.platform
                        FROM official_accounts oa
                        JOIN test_plans plan ON plan.operation_id = oa.operation_id
                        WHERE plan.id = ?
                        """, String.class, execution.planId());

        HubPayloads.JobOfferedPayload payload = new HubPayloads.JobOfferedPayload(
                executionId, execution.idempotencyKey(), execution.sourceRevision(), platform, testCases, leaseToken);
        return HubEnvelopeV1.of(HubEnvelopeV1.TYPE_JOB_OFFERED, payload);
    }

    private record OperationApprovalRow(int revision, String status, UUID approvedPlanId) {
    }

    private record ExecutionRow(UUID planId, int sourceRevision, String idempotencyKey) {
    }
}
