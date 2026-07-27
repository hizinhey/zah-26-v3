package com.opshub.hub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.execution.application.ExecutionService;
import com.opshub.hub.application.HubConnectionService;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Primary transport for the Hub protocol. Exchanges the same {@link HubEnvelopeV1} payloads used
 * by {@link HubPollingController} - a Hub that drops its WebSocket connection can resume over
 * HTTPS long polling without any change in message shape.
 */
@Component
public class HubWebSocketHandler extends TextWebSocketHandler {
    static final String HUB_ID_ATTRIBUTE = "hubId";

    private final ExecutionService executionService;
    private final HubConnectionService hubConnectionService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, WebSocketSession> sessionsByHub = new ConcurrentHashMap<>();

    public HubWebSocketHandler(ExecutionService executionService, HubConnectionService hubConnectionService, ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.hubConnectionService = hubConnectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID hubId = hubId(session);
        sessionsByHub.put(hubId, session);
        hubConnectionService.markOnline(hubId, "WEBSOCKET");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        UUID hubId = hubId(session);
        sessionsByHub.remove(hubId, session);
        hubConnectionService.markOffline(hubId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID hubId = hubId(session);
        HubEnvelopeV1 envelope = objectMapper.readValue(message.getPayload(), HubEnvelopeV1.class);
        switch (envelope.type()) {
            case HubEnvelopeV1.TYPE_HEARTBEAT -> {
                HubPayloads.HeartbeatPayload payload = objectMapper.convertValue(envelope.payload(), HubPayloads.HeartbeatPayload.class);
                hubConnectionService.heartbeat(hubId, "WEBSOCKET", payload.deviceReady(), payload.runnerReady());
                // A heartbeat renews whichever lease the Hub currently holds, so a long-running job
                // survives past the fixed 60s lease window without the Hub separately tracking and
                // resending the lease token (looked up server-side by hub ID - see
                // ExecutionService#renewActiveLease). No-op when the Hub has no active lease.
                executionService.renewActiveLease(hubId);
                offerNextJobIfAny(hubId, session);
            }
            case HubEnvelopeV1.TYPE_JOB_PROGRESS -> executionService.recordProgress(envelope);
            case HubEnvelopeV1.TYPE_TEST_RESULT -> {
                executionService.recordResult(envelope);
                offerNextJobIfAny(hubId, session);
            }
            default -> throw new IllegalArgumentException("Unsupported message type: " + envelope.type());
        }
    }

    private void offerNextJobIfAny(UUID hubId, WebSocketSession session) throws IOException {
        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId);
        if (offer.isPresent() && session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(offer.get())));
        }
    }

    private UUID hubId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(HUB_ID_ATTRIBUTE);
    }
}
