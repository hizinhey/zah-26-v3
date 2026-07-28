package com.opshub.hub;

import com.opshub.hub.application.HubConnectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubConnectionServiceTest {

    @Test
    void bindsHeartbeatTimeAsJdbcTimestamp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        HubConnectionService service = new HubConnectionService(jdbcTemplate);

        service.heartbeat(UUID.randomUUID(), "HTTPS_POLLING", true, true, "ANDROID");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()[2]).isInstanceOf(Timestamp.class);
    }
}
