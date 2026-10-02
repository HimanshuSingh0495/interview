package com.example.app.vote;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    Optional<Vote> findByUserIdAndPollId(Long userId, Long pollId);

    // SELECT ... FOR UPDATE: concurrent vote changes by the same user run one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vote v WHERE v.user.id = :userId AND v.poll.id = :pollId")
    Optional<Vote> findForUpdate(@Param("userId") Long userId, @Param("pollId") Long pollId);

    long countByOptionId(Long optionId);

    long countByPollId(Long pollId);
}
