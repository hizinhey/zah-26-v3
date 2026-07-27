package com.opshub.hub.domain;

import java.util.List;
import java.util.UUID;

/**
 * Payload records for each {@link HubEnvelopeV1#type()}, matching contracts/schemas/hub-envelope-v1.json.
 */
public final class HubPayloads {
    private HubPayloads() {
    }

    public record TestCase(
            UUID testCaseId,
            int oaOrder,
            String oaName,
            int order,
            String templateId,
            int templateVersion,
            Object parameters
    ) {
    }

    public record JobOfferedPayload(
            UUID executionId,
            String idempotencyKey,
            int revision,
            String platform,
            List<TestCase> testCases,
            UUID leaseToken
    ) {
    }

    public record JobProgressPayload(
            UUID executionId,
            UUID testCaseId,
            String status,
            String message
    ) {
    }

    public record TestResultPayload(
            UUID executionId,
            UUID testCaseId,
            int attempt,
            String status,
            long durationMs,
            String errorCategory
    ) {
    }

    public record HeartbeatPayload(
            boolean deviceReady,
            boolean runnerReady
    ) {
    }
}
