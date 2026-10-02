package com.example.app.poll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {

    Optional<Poll> findByShareId(String shareId);

    boolean existsByShareId(String shareId);

    List<Poll> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);
}
