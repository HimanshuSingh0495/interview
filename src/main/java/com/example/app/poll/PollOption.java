package com.example.app.poll;

import jakarta.persistence.*;

@Entity
@Table(name = "poll_option")
public class PollOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @Column(name = "option_text", nullable = false, length = 100)
    private String text;

    @Column(nullable = false)
    private int position;

    // Changed only by SQL arithmetic in PollOptionRepository, never read-modify-write.
    // updatable = false: saving an entity can never overwrite a counter another transaction just bumped.
    @Column(name = "vote_count", nullable = false, updatable = false)
    private int voteCount;

    protected PollOption() {
    }

    public PollOption(Poll poll, String text, int position) {
        this.poll = poll;
        this.text = text;
        this.position = position;
    }

    public Long getId() { return id; }
    public Poll getPoll() { return poll; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public int getVoteCount() { return voteCount; }
}
