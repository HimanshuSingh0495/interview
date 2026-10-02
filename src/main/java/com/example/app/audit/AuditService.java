package com.example.app.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends to audit_event. MANDATORY: the row must commit or roll back with the action it describes. */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 500;

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, AuditAction action, Long pollId, String details) {
        String trimmed = details != null && details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) : details;
        events.save(new AuditEvent(actorId, action, pollId, trimmed));
    }
}
