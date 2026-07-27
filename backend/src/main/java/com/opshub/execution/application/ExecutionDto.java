package com.opshub.execution.application;

import java.time.Instant;
import java.util.UUID;

public record ExecutionDto(
        UUID id,
        UUID operationId,
        UUID planId,
        int sourceRevision,
        String idempotencyKey,
        String status,
        Instant queuedAt
) {
}
