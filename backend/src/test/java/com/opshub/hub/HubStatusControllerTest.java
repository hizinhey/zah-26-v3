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
    void returnsHubsInTheOrderTheQueryServiceProvidesThem() throws Exception {
        UUID hubId = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T13:47:10Z");
        Instant createdAt = Instant.parse("2026-07-27T15:21:09Z");
        when(hubQueryService.listHubs()).thenReturn(List.of(
                new HubQueryService.HubSummary(hubId, hubId.toString(), "ONLINE", "WEBSOCKET", "ANDROID",
                        true, true, heartbeatAt, createdAt)));

        mockMvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(hubId.toString()))
                .andExpect(jsonPath("$[0].name").value(hubId.toString()))
                .andExpect(jsonPath("$[0].connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$[0].transport").value("WEBSOCKET"))
                .andExpect(jsonPath("$[0].platform").value("ANDROID"))
                .andExpect(jsonPath("$[0].deviceReady").value(true))
                .andExpect(jsonPath("$[0].runnerReady").value(true))
                .andExpect(jsonPath("$[0].lastHeartbeatAt").value("2026-07-28T13:47:10Z"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-07-27T15:21:09Z"));
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
