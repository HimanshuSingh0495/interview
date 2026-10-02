package com.example.app.poll;

import com.example.app.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "poll")
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_id", nullable = false, unique = true, length = 12)
    private String shareId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false, length = 300)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PollStatus status;

    // Optimistic lock: a save with a stale version throws ObjectOptimisticLockingFailureException.
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // No orphanRemoval: options are only deleted through PollOptionRepository.deleteIfNoVotes.
    @OneToMany(mappedBy = "poll", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("position")
    private List<PollOption> options = new ArrayList<>();

    protected Poll() {
    }

    public Poll(User creator, String shareId, String question) {
        this.creator = creator;
        this.shareId = shareId;
        this.question = question;
        this.status = PollStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public PollOption addOption(String text, int position) {
        PollOption option = new PollOption(this, text, position);
        options.add(option);
        return option;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getShareId() { return shareId; }
    public User getCreator() { return creator; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public PollStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<PollOption> getOptions() { return options; }
}
