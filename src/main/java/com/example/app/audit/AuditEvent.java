package com.example.app.audit;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuditAction action;

    @Column(name = "poll_id")
    private Long pollId;

    @Column(length = 500)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(Long actorId, AuditAction action, Long pollId, String details) {
        this.actorId = actorId;
        this.action = action;
        this.pollId = pollId;
        this.details = details;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public AuditAction getAction() { return action; }
    public Long getPollId() { return pollId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
