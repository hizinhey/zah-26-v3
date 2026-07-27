package com.opshub.execution.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed leases granting a single Hub exclusive ownership of an execution for a bounded
 * period of time. Renewal is a compare-and-set update so a lease can never be silently extended
 * past its owner losing the race to a competing renewal or expiry.
 */
@Service
public class LeaseService {
    static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;

    public LeaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasActiveLease(UUID hubId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM job_leases WHERE hub_id = ? AND expires_at > ?
                        """, Integer.class, hubId, Instant.now());
        return count != null && count > 0;
    }

    /**
     * Grants a new lease for the Hub. Any of the Hub's previously expired leases are deleted first
     * so that at most one row per {@code hub_id} ever exists in {@code job_leases}, which lets a
     * database-level unique constraint on {@code hub_id} (see V3__job_leases_hub_id_unique.sql)
     * enforce "one active lease per Hub" even across multiple backend instances - the in-process
     * {@code ReentrantLock} in {@link ExecutionService#offerNextJob} only protects a single JVM.
     * If a competing instance wins the race, the INSERT below fails with a unique-violation, which
     * callers should treat the same as "nothing to offer right now".
     */
    public UUID acquire(UUID hubId, UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE hub_id = ? AND expires_at <= ?", hubId, Instant.now());
        UUID leaseToken = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO job_leases (id, hub_id, execution_id, lease_token, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), hubId, executionId, leaseToken, Instant.now().plus(LEASE_DURATION));
        return leaseToken;
    }

    /**
     * Compare-and-set renewal: only succeeds while the lease is still owned by the given Hub and
     * has not already expired.
     */
    public boolean renew(UUID hubId, UUID leaseToken) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE lease_token = ? AND hub_id = ? AND expires_at > ?
                        """, Instant.now().plus(LEASE_DURATION), leaseToken, hubId, Instant.now());
        return updated == 1;
    }

    /**
     * Renews whichever lease is currently active for the Hub, looked up by {@code hub_id} rather
     * than requiring the caller to supply the lease token. Used for heartbeat-driven renewal.
     */
    public boolean renewActiveLease(UUID hubId) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE hub_id = ? AND expires_at > ?
                        """, Instant.now().plus(LEASE_DURATION), hubId, Instant.now());
        return updated == 1;
    }

    public void release(UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE execution_id = ?", executionId);
    }

    /**
     * Executions that are queued, or whose previously granted lease has expired without a result,
     * are eligible to be re-offered. A Hub can only ever hold one active lease at a time.
     */
    public Optional<UUID> nextOfferableExecution() {
        return jdbcTemplate.queryForList("""
                        SELECT execution.id
                        FROM executions execution
                        WHERE execution.status IN ('QUEUED', 'RUNNING')
                          AND execution.finished_at IS NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM job_leases lease
                              WHERE lease.execution_id = execution.id AND lease.expires_at > ?
                          )
                        ORDER BY execution.queued_at ASC
                        LIMIT 1
                        """, UUID.class, Instant.now())
                .stream().findFirst();
    }
}
