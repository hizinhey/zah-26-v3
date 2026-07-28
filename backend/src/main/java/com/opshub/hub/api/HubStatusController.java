package com.opshub.hub.api;

import com.opshub.hub.application.HubQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator-facing read of Hub connectivity state (for the browser's Hub status indicator) - not
 * to be confused with the Hub-facing {@code /api/v1/hubs/{hubId}/...} endpoints in
 * {@link HubPollingController} and {@link HubWebSocketHandler}, which require a Hub token.
 */
@RestController
@RequestMapping("/api/v1/hubs")
public class HubStatusController {
    private final HubQueryService hubQueryService;

    public HubStatusController(HubQueryService hubQueryService) {
        this.hubQueryService = hubQueryService;
    }

    @GetMapping
    public List<HubResponse> list() {
        return hubQueryService.listHubs().stream().map(HubResponse::from).toList();
    }

    public record HubResponse(
            UUID id,
            String name,
            String connectionStatus,
            String transport,
            String platform,
            boolean deviceReady,
            boolean runnerReady,
            Instant lastHeartbeatAt,
            Instant createdAt) {
        static HubResponse from(HubQueryService.HubSummary hub) {
            return new HubResponse(hub.id(), hub.name(), hub.connectionStatus(), hub.transport(), hub.platform(),
                    hub.deviceReady(), hub.runnerReady(), hub.lastHeartbeatAt(), hub.createdAt());
        }
    }
}
