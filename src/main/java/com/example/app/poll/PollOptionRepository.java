package com.example.app.poll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PollOptionRepository extends JpaRepository<PollOption, Long> {

    // Bulk updates return rows affected (0 = option missing or not in this poll).
    // They clear the persistence context: run them before loading entities you modify, or re-fetch after.

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PollOption o SET o.voteCount = o.voteCount + 1 WHERE o.id = :optionId AND o.poll.id = :pollId")
    int incrementVoteCount(@Param("pollId") Long pollId, @Param("optionId") Long optionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PollOption o SET o.voteCount = o.voteCount - 1"
            + " WHERE o.id = :optionId AND o.poll.id = :pollId AND o.voteCount > 0")
    int decrementVoteCount(@Param("pollId") Long pollId, @Param("optionId") Long optionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PollOption o WHERE o.id = :optionId AND o.poll.id = :pollId AND o.voteCount = 0")
    int deleteIfNoVotes(@Param("pollId") Long pollId, @Param("optionId") Long optionId);

    List<PollOption> findByPollIdOrderByPosition(Long pollId);
}
