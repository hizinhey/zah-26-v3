package com.opshub.hub;

import com.opshub.hub.api.HubStatusController;
import com.opshub.hub.application.HubQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HubStatusController.class)
class HubStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HubQueryService hubQueryService;

    @Test
    void returnsOneHubWithBothPlatformsListed() throws Exception {
        UUID hubId = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T13:47:10Z");
        Instant createdAt = Instant.parse("2026-07-27T15:21:09Z");
        when(hubQueryService.listHubs()).thenReturn(List.of(
                new HubQueryService.HubSummary(hubId, hubId.toString(), createdAt, List.of(
                        new HubQueryService.PlatformStatus("ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt),
                        new HubQueryService.PlatformStatus("WEB", "OFFLINE", "HTTPS_POLLING", false, false, null)))));

        mockMvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(hubId.toString()))
                .andExpect(jsonPath("$[0].platforms[0].platform").value("ANDROID"))
                .andExpect(jsonPath("$[0].platforms[0].connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$[0].platforms[1].platform").value("WEB"))
                .andExpect(jsonPath("$[0].platforms[1].connectionStatus").value("OFFLINE"));
    }

    @Test
    void returnsAnEmptyArrayWhenNoHubHasEverConnected() throws Exception {
        when(hubQueryService.listHubs()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
