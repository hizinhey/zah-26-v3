package com.opshub.execution;

import com.opshub.execution.application.LeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseServiceTest {

    @Test
    void bindsActiveLeaseCutoffAsJdbcTimestamp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        new LeaseService(jdbcTemplate).hasActiveLease(UUID.randomUUID(), "ANDROID");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), arguments.capture());
        assertThat(arguments.getValue()[1]).isEqualTo("ANDROID");
        assertThat(arguments.getValue()[2]).isInstanceOf(Timestamp.class);
    }

    @Test
    void bindsLeaseRenewalTimesAsJdbcTimestamps() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        new LeaseService(jdbcTemplate).renewActiveLease(UUID.randomUUID(), "WEB");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()[0]).isInstanceOf(Timestamp.class);
        assertThat(arguments.getValue()[2]).isEqualTo("WEB");
        assertThat(arguments.getValue()[3]).isInstanceOf(Timestamp.class);
    }
}
