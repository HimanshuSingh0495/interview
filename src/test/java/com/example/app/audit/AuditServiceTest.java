package com.example.app.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditEventRepository events;
    @InjectMocks AuditService service;

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void record_savesAllFields() {
        service.record(3L, AuditAction.VOTE_CHANGED, 7L, "option 11 -> 12");

        AuditEvent e = saved();
        assertThat(e.getActorId()).isEqualTo(3L);
        assertThat(e.getAction()).isEqualTo(AuditAction.VOTE_CHANGED);
        assertThat(e.getPollId()).isEqualTo(7L);
        assertThat(e.getDetails()).isEqualTo("option 11 -> 12");
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void record_truncatesDetailsTo500Characters() {
        service.record(3L, AuditAction.POLL_UPDATED, 7L, "x".repeat(501));

        assertThat(saved().getDetails()).hasSize(500);
    }

    @Test
    void record_keepsExactly500Characters() {
        String details = "y".repeat(500);
        service.record(3L, AuditAction.POLL_UPDATED, 7L, details);

        assertThat(saved().getDetails()).isEqualTo(details);
    }

    @Test
    void record_allowsNullDetailsAndPoll() {
        service.record(3L, AuditAction.USER_REGISTERED, null, null);

        AuditEvent e = saved();
        assertThat(e.getPollId()).isNull();
        assertThat(e.getDetails()).isNull();
    }
}
