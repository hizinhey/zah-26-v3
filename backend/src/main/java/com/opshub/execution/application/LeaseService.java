package com.opshub.execution.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed leases granting a single Hub exclusive ownership of an execution, scoped per
 * (hub, platform) - a Hub can hold one active ANDROID lease and one active WEB lease at once,
 * but never two leases for the same platform.
 */
@Service
public class LeaseService {
    static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;

    public LeaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasActiveLease(UUID hubId, String platform) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM job_leases WHERE hub_id = ? AND platform = ? AND expires_at > ?
                        """, Integer.class, hubId, platform, now());
        return count != null && count > 0;
    }

    /**
     * Grants a new lease for the (hub, platform) pair. Any previously expired lease for that
     * same pair is deleted first so at most one row per (hub_id, platform) ever exists, letting
     * the database-level unique constraint (job_leases_hub_id_platform_unique) enforce "one
     * active lease per platform per Hub" even across multiple backend instances.
     */
    public UUID acquire(UUID hubId, String platform, UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE hub_id = ? AND platform = ? AND expires_at <= ?",
                hubId, platform, now());
        UUID leaseToken = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO job_leases (id, hub_id, platform, execution_id, lease_token, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), hubId, platform, executionId, leaseToken, expiresAt());
        return leaseToken;
    }

    public boolean renew(UUID hubId, UUID leaseToken) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE lease_token = ? AND hub_id = ? AND expires_at > ?
                        """, expiresAt(), leaseToken, hubId, now());
        return updated == 1;
    }

    public boolean renewActiveLease(UUID hubId, String platform) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE hub_id = ? AND platform = ? AND expires_at > ?
                        """, expiresAt(), hubId, platform, now());
        return updated == 1;
    }

    public void release(UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE execution_id = ?", executionId);
    }

    public Optional<UUID> nextOfferableExecution(String platform) {
        return jdbcTemplate.queryForList("""
                        SELECT execution.id
                        FROM executions execution
                        WHERE execution.status IN ('QUEUED', 'RUNNING')
                          AND execution.finished_at IS NULL
                          AND EXISTS (
                              SELECT 1 FROM official_accounts oa
                              WHERE oa.operation_id = execution.operation_id AND oa.platform = ?
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM job_leases lease
                              WHERE lease.execution_id = execution.id AND lease.expires_at > ?
                          )
                        ORDER BY execution.queued_at ASC
                        LIMIT 1
                        """, UUID.class, platform, now())
                .stream().findFirst();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static Timestamp expiresAt() {
        return Timestamp.from(Instant.now().plus(LEASE_DURATION));
    }
}
