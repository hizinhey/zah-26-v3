package com.opshub.execution.application;

import java.util.UUID;

/**
 * Thrown when an envelope for an execution is received out of order (its timestamp is before
 * the last accepted message for that execution). This is a permanent rejection of that specific
 * envelope, not a transient failure - the Hub's {@code Outbox.flush} must not retry it forever.
 * See {@link ExecutionService#requireMonotonic} and I5 in the final-review fix report.
 */
public class MonotonicOrderViolationException extends RuntimeException {
    private final UUID executionId;

    public MonotonicOrderViolationException(UUID executionId, String message) {
        super(message);
        this.executionId = executionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }
}
