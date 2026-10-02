package com.example.app.poll;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.CurrentUser;
import com.example.app.poll.dto.CreatePollDto;
import com.example.app.poll.dto.EditOptionDto;
import com.example.app.poll.dto.EditPollDto;
import com.example.app.poll.dto.PollResponse;
import com.example.app.user.UserRepository;
import com.example.app.web.ApiException;
import com.example.app.web.ApiExceptionHandler;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PollService {

    static final String POLL_NOT_FOUND = "Poll not found";
    static final String OPTION_NOT_FOUND = "Option not found in this poll";
    static final String NOT_CREATOR = "Only the creator of this poll can do that";
    static final String DUPLICATE_OPTIONS = "Options must be unique (ignoring case)";
    private static final int SHARE_ID_ATTEMPTS = 5;

    private final PollRepository polls;
    private final PollOptionRepository options;
    private final UserRepository users;
    private final ShareIdGenerator shareIds;
    private final AuditService audit;
    private final EntityManager em;

    public PollService(PollRepository polls, PollOptionRepository options, UserRepository users,
                       ShareIdGenerator shareIds, AuditService audit, EntityManager em) {
        this.polls = polls;
        this.options = options;
        this.users = users;
        this.shareIds = shareIds;
        this.audit = audit;
        this.em = em;
    }

    @Transactional
    public PollResponse create(CreatePollDto dto, CurrentUser me) {
        List<String> texts = dto.options().stream().map(String::trim).toList();
        requireUnique(texts);
        Poll poll = new Poll(users.getReferenceById(me.id()), newShareId(), dto.question().trim());
        for (int i = 0; i < texts.size(); i++) {
            poll.addOption(texts.get(i), i);
        }
        polls.saveAndFlush(poll); // cascades the options
        audit.record(me.id(), AuditAction.POLL_CREATED, poll.getId(), texts.size() + " options");
        return PollResponse.from(poll);
    }

    @Transactional(readOnly = true)
    public PollResponse getOwned(Long id, CurrentUser me) {
        return PollResponse.from(loadOwned(id, me));
    }

    @Transactional(readOnly = true)
    public PollResponse getByShareId(String shareId) {
        return PollResponse.from(polls.findByShareId(shareId).orElseThrow(() -> ApiException.notFound(POLL_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public List<PollResponse> listMine(CurrentUser me) {
        return polls.findByCreatorIdOrderByCreatedAtDesc(me.id()).stream().map(PollResponse::from).toList();
    }

    /**
     * Edit question and options. Two layers protect against lost updates:
     * the explicit version check (client saw an old poll) and @Version on flush (concurrent edit in flight).
     * Options with votes are never deleted: deleteIfNoVotes only matches rows with vote_count = 0.
     */
    @Transactional
    public PollResponse edit(Long id, EditPollDto dto, CurrentUser me) {
        Poll poll = loadOwned(id, me);
        requireVersion(poll, dto.version());

        String question = dto.question().trim();
        List<String> texts = dto.options().stream().map(o -> o.text().trim()).toList();
        requireUnique(texts);

        Map<Long, PollOption> existing = new HashMap<>();
        poll.getOptions().forEach(o -> existing.put(o.getId(), o));
        Map<Long, Integer> keptPosition = new HashMap<>();
        for (int i = 0; i < dto.options().size(); i++) {
            Long optionId = dto.options().get(i).id();
            if (optionId == null) {
                continue;
            }
            if (!existing.containsKey(optionId)) {
                throw ApiException.notFound(OPTION_NOT_FOUND);
            }
            if (keptPosition.put(optionId, i) != null) {
                throw ApiException.badRequest("The same option is listed twice");
            }
        }

        // Touch option rows in ascending id order (as VoteService does) so concurrent writers cannot deadlock.
        // Everything we need from the loaded entities is read here, before bulk statements clear the context.
        List<PollOption> byId = new ArrayList<>(existing.values());
        byId.sort(Comparator.comparing(PollOption::getId));
        int removed = 0;
        int renamed = 0;
        for (PollOption option : byId) {
            Integer position = keptPosition.get(option.getId());
            if (position == null) {
                if (options.deleteIfNoVotes(id, option.getId()) == 0) {
                    throw ApiException.conflict("Cannot remove option '" + option.getText() + "': it has votes");
                }
                removed++;
            } else {
                String text = texts.get(position);
                if (!text.equals(option.getText()) || position != option.getPosition()) {
                    renamed += text.equals(option.getText()) ? 0 : 1;
                    updateOptionText(option.getId(), text, position);
                }
            }
        }

        // The bulk statements left our entities stale (or detached): start again from the database.
        em.clear();
        poll = polls.findById(id).orElseThrow(() -> ApiException.notFound(POLL_NOT_FOUND));
        requireVersion(poll, dto.version());

        int added = 0;
        for (int i = 0; i < dto.options().size(); i++) {
            EditOptionDto option = dto.options().get(i);
            if (option.id() == null) {
                options.save(poll.addOption(texts.get(i), i));
                added++;
            }
        }
        boolean questionChanged = !question.equals(poll.getQuestion());
        poll.setQuestion(question);
        poll.touch(); // always dirty, so @Version increments even if only options changed
        polls.flush(); // a concurrent edit that committed first makes this throw -> 409

        audit.record(me.id(), AuditAction.POLL_UPDATED, id, String.format(
                "question %s; kept=%d, renamed=%d, added=%d, removed=%d",
                questionChanged ? "changed" : "unchanged", keptPosition.size(), renamed, added, removed));
        return PollResponse.from(poll);
    }

    /**
     * Writes only text and position. Saving a PollOption entity would also write back its vote_count,
     * which a concurrent vote may have incremented since we read it: that would lose the vote.
     */
    private void updateOptionText(Long optionId, String text, int position) {
        em.createQuery("UPDATE PollOption o SET o.text = :text, o.position = :position WHERE o.id = :id")
                .setParameter("text", text)
                .setParameter("position", position)
                .setParameter("id", optionId)
                .executeUpdate();
    }

    private Poll loadOwned(Long id, CurrentUser me) {
        Poll poll = polls.findById(id).orElseThrow(() -> ApiException.notFound(POLL_NOT_FOUND));
        if (!Objects.equals(poll.getCreator().getId(), me.id())) {
            throw ApiException.forbidden(NOT_CREATOR);
        }
        return poll;
    }

    private static void requireVersion(Poll poll, Long expected) {
        if (!Objects.equals(poll.getVersion(), expected)) {
            throw ApiException.conflict(ApiExceptionHandler.STALE_POLL_MESSAGE);
        }
    }

    private static void requireUnique(List<String> texts) {
        Set<String> seen = new HashSet<>();
        for (String text : texts) {
            if (text.isEmpty()) {
                throw ApiException.badRequest("Option text is required");
            }
            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest(DUPLICATE_OPTIONS);
            }
        }
    }

    private String newShareId() {
        for (int i = 0; i < SHARE_ID_ATTEMPTS; i++) {
            String candidate = shareIds.next();
            if (!polls.existsByShareId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique share id");
    }
}
