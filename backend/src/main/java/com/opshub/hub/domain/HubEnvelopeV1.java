package com.opshub.hub.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire-level envelope shared by the WebSocket transport and the HTTPS long-polling fallback.
 * Mirrors contracts/schemas/hub-envelope-v1.json exactly.
 */
public record HubEnvelopeV1(
        UUID messageId,
        int version,
        String type,
        Instant timestamp,
        Object payload
) {
    public static final String TYPE_JOB_OFFERED = "JOB_OFFERED";
    public static final String TYPE_JOB_PROGRESS = "JOB_PROGRESS";
    public static final String TYPE_TEST_RESULT = "TEST_RESULT";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";

    public static HubEnvelopeV1 of(String type, Object payload) {
        return new HubEnvelopeV1(UUID.randomUUID(), 1, type, Instant.now(), payload);
    }
}
