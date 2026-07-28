package com.opshub.hub.api;

import com.opshub.execution.application.ExecutionService;
import com.opshub.execution.application.HubNotOnlineException;
import com.opshub.execution.application.MonotonicOrderViolationException;
import com.opshub.hub.application.HubConnectionService;
import com.opshub.hub.application.HubProperties;
import com.opshub.hub.application.HubTokenValidator;
import com.opshub.hub.domain.HubEnvelopeV1;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTPS long-polling fallback for Hubs that cannot maintain a WebSocket connection. Uses the
 * exact same {@link HubEnvelopeV1} payloads as {@link HubWebSocketHandler} so a Hub can switch
 * transports transparently.
 */
@RestController
@RequestMapping("/api/v1/hubs/{hubId}")
public class HubPollingController {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final ExecutionService executionService;
    private final HubConnectionService hubConnectionService;
    private final HubTokenValidator hubTokenValidator;
    private final HubProperties hubProperties;

    public HubPollingController(ExecutionService executionService, HubConnectionService hubConnectionService,
                                 HubTokenValidator hubTokenValidator, HubProperties hubProperties) {
        this.executionService = executionService;
        this.hubConnectionService = hubConnectionService;
        this.hubTokenValidator = hubTokenValidator;
        this.hubProperties = hubProperties;
    }

    @GetMapping("/jobs/next")
    public ResponseEntity<HubEnvelopeV1> next(@PathVariable UUID hubId,
                                               @RequestParam(defaultValue = "25") long waitSeconds,
                                               @RequestHeader("X-Hub-Token") String token,
                                               @RequestHeader(value = "X-Hub-Platform", defaultValue = "ANDROID") String platform)
            throws InterruptedException {
        requireValidToken(token);
        hubConnectionService.markOnline(hubId, "HTTPS_POLLING", platform);
        long capped = Math.min(waitSeconds, hubProperties.getPollWaitCapSeconds());
        Instant deadline = Instant.now().plusSeconds(capped);
        do {
            Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId, platform);
            if (offer.isPresent()) {
                return ResponseEntity.ok(offer.get());
            }
            Thread.sleep(Math.min(POLL_INTERVAL.toMillis(), Math.max(0, Duration.between(Instant.now(), deadline).toMillis())));
        } while (Instant.now().isBefore(deadline));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID hubId, @RequestHeader("X-Hub-Token") String token,
                                           @RequestHeader(value = "X-Hub-Platform", defaultValue = "ANDROID") String platform,
                                           @RequestBody HubEnvelopeV1 envelope) {
        requireValidToken(token);
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(envelope.payload(), com.opshub.hub.domain.HubPayloads.HeartbeatPayload.class);
        hubConnectionService.heartbeat(hubId, "HTTPS_POLLING", payload.deviceReady(), payload.runnerReady(), platform);
        // Same lease-renewal-on-heartbeat behavior as the WebSocket transport (see
        // HubWebSocketHandler#handleTextMessage) - looked up server-side by hub ID rather than
        // requiring the payload to carry the lease token.
        executionService.renewActiveLease(hubId, platform);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/leases/{leaseToken}/renew")
    public ResponseEntity<Void> renewLease(@PathVariable UUID hubId, @PathVariable UUID leaseToken,
                                            @RequestHeader("X-Hub-Token") String token) {
        requireValidToken(token);
        boolean renewed = executionService.renewLease(hubId, leaseToken);
        return renewed ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/progress")
    public ResponseEntity<Void> progress(@PathVariable UUID hubId, @RequestHeader("X-Hub-Token") String token,
                                          @RequestBody HubEnvelopeV1 envelope) {
        requireValidToken(token);
        executionService.recordProgress(envelope);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/results")
    public ResponseEntity<Void> results(@PathVariable UUID hubId, @RequestHeader("X-Hub-Token") String token,
                                         @RequestBody HubEnvelopeV1 envelope) {
        requireValidToken(token);
        executionService.recordResult(envelope);
        return ResponseEntity.ok().build();
    }

    private void requireValidToken(String token) {
        if (!hubTokenValidator.isValid(token)) {
            throw new InvalidHubTokenException();
        }
    }

    static class InvalidHubTokenException extends RuntimeException {
    }
}

@RestControllerAdvice
class HubPollingErrorHandler {
    @ExceptionHandler(HubPollingController.InvalidHubTokenException.class)
    ResponseEntity<Void> invalidToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(HubNotOnlineException.class)
    ResponseEntity<Void> hubOffline() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    // I5 fix: 409 with a distinct error code (not a bare 500) so the Hub's Outbox.flush can
    // recognize this as a permanent rejection of this specific envelope and drop it, rather
    // than retrying it forever the way it would for a genuine 5xx.
    @ExceptionHandler(MonotonicOrderViolationException.class)
    ResponseEntity<ErrorBody> outOfOrder(MonotonicOrderViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("MESSAGE_OUT_OF_ORDER", exception.getMessage()));
    }

    record ErrorBody(String code, String message) {
    }
}
