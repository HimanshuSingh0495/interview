package com.example.app.vote;

import com.example.app.poll.Poll;
import com.example.app.poll.PollOption;
import com.example.app.user.User;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "vote")
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private PollOption option;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Vote() {
    }

    public Vote(Poll poll, User user, PollOption option) {
        this.poll = poll;
        this.user = user;
        this.option = option;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void changeOption(PollOption option) {
        this.option = option;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Poll getPoll() { return poll; }
    public User getUser() { return user; }
    public PollOption getOption() { return option; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
