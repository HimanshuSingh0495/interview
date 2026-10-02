package com.example.app.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByPollIdOrderByIdAsc(Long pollId);

    List<AuditEvent> findByActorIdOrderByIdAsc(Long actorId);
}
