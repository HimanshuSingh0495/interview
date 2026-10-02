package com.example.app.poll;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.CurrentUser;
import com.example.app.poll.dto.CreatePollDto;
import com.example.app.poll.dto.EditOptionDto;
import com.example.app.poll.dto.EditPollDto;
import com.example.app.poll.dto.OptionResponse;
import com.example.app.poll.dto.PollResponse;
import com.example.app.user.User;
import com.example.app.user.UserRepository;
import com.example.app.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

@ExtendWith(MockitoExtension.class)
class PollServiceTest {

    private static final long POLL_ID = 7L;
    private static final CurrentUser ALICE = new CurrentUser(1L, "alice");
    private static final CurrentUser BOB = new CurrentUser(2L, "bob");
    private static final String STALE = "Poll was changed by someone else, reload and try again";

    @Mock PollRepository polls;
    @Mock PollOptionRepository options;
    @Mock UserRepository users;
    @Mock ShareIdGenerator shareIds;
    @Mock AuditService audit;
    @Mock EntityManager em;

    PollService service;
    User alice;

    @BeforeEach
    void setUp() {
        service = new PollService(polls, options, users, shareIds, audit, em);
        alice = new User("alice", "hash");
        setField(alice, "id", ALICE.id());
    }

    /** Poll 7 by alice, version 3, options 11 Red / 12 Green / 13 Blue at positions 0..2. */
    private Poll existingPoll() {
        Poll poll = new Poll(alice, "share12345", "Best colour?");
        setField(poll, "id", POLL_ID);
        setField(poll, "version", 3L);
        String[] texts = {"Red", "Green", "Blue"};
        for (int i = 0; i < texts.length; i++) {
            setField(poll.addOption(texts[i], i), "id", 11L + i);
        }
        return poll;
    }

    private static void assertApi(Executable call, HttpStatus status, String message) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    // ================= create =================

    @Nested
    class Create {

        @BeforeEach
        void stubs() {
            lenient().when(users.getReferenceById(ALICE.id())).thenReturn(alice);
            lenient().when(shareIds.next()).thenReturn("share00001");
            lenient().when(polls.existsByShareId(anyString())).thenReturn(false);
            lenient().when(polls.saveAndFlush(any(Poll.class))).thenAnswer(inv -> {
                Poll p = inv.getArgument(0);
                setField(p, "id", POLL_ID);
                setField(p, "version", 0L);
                return p;
            });
        }

        private Poll savedPoll() {
            ArgumentCaptor<Poll> captor = ArgumentCaptor.forClass(Poll.class);
            verify(polls).saveAndFlush(captor.capture());
            return captor.getValue();
        }

        @Test
        void trimsQuestionAndOptions() {
            PollResponse res = service.create(new CreatePollDto("  Best colour?  ", List.of(" Red ", "\tGreen", "Blue  ")), ALICE);

            Poll saved = savedPoll();
            assertThat(saved.getQuestion()).isEqualTo("Best colour?");
            assertThat(saved.getOptions()).extracting(PollOption::getText).containsExactly("Red", "Green", "Blue");
            assertThat(res.question()).isEqualTo("Best colour?");
            assertThat(res.options()).extracting(OptionResponse::text).containsExactly("Red", "Green", "Blue");
            assertThat(res.creatorUsername()).isEqualTo("alice");
        }

        @Test
        void assignsPositionsZeroToNMinusOne() {
            service.create(new CreatePollDto("Q", List.of("a", "b", "c", "d")), ALICE);

            Poll saved = savedPoll();
            assertThat(saved.getOptions()).extracting(PollOption::getPosition).containsExactly(0, 1, 2, 3);
            assertThat(saved.getOptions()).allSatisfy(o -> assertThat(o.getPoll()).isSameAs(saved));
            assertThat(saved.getCreator()).isSameAs(alice);
            assertThat(saved.getStatus()).isEqualTo(PollStatus.OPEN);
        }

        @Test
        void duplicateOptionsIgnoringCase_is400() {
            assertApi(() -> service.create(new CreatePollDto("Q", List.of("Red", "Green", "rED")), ALICE),
                    HttpStatus.BAD_REQUEST, "Options must be unique (ignoring case)");
            verify(polls, never()).saveAndFlush(any());
            verifyNoInteractions(audit);
        }

        @Test
        void duplicateAfterTrimming_is400() {
            assertApi(() -> service.create(new CreatePollDto("Q", List.of("Red", "  red  ")), ALICE),
                    HttpStatus.BAD_REQUEST, "Options must be unique (ignoring case)");
        }

        @Test
        void blankOptionAfterTrimming_is400() {
            assertApi(() -> service.create(new CreatePollDto("Q", List.of("Red", "   ")), ALICE),
                    HttpStatus.BAD_REQUEST, "Option text is required");
        }

        @Test
        void shareIdRetriesOnCollision() {
            when(shareIds.next()).thenReturn("taken00001", "taken00002", "free000001");
            when(polls.existsByShareId("taken00001")).thenReturn(true);
            when(polls.existsByShareId("taken00002")).thenReturn(true);
            when(polls.existsByShareId("free000001")).thenReturn(false);

            PollResponse res = service.create(new CreatePollDto("Q", List.of("a", "b")), ALICE);

            assertThat(savedPoll().getShareId()).isEqualTo("free000001");
            assertThat(res.shareId()).isEqualTo("free000001");
            verify(shareIds, times(3)).next();
        }

        @Test
        void shareIdGivesUpAfterFiveCollisions() {
            when(polls.existsByShareId(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.create(new CreatePollDto("Q", List.of("a", "b")), ALICE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Could not generate a unique share id");
            verify(shareIds, times(5)).next();
            verify(polls, never()).saveAndFlush(any());
        }

        @Test
        void auditsPollCreated() {
            service.create(new CreatePollDto("Q", List.of("a", "b", "c")), ALICE);

            verify(audit).record(ALICE.id(), AuditAction.POLL_CREATED, POLL_ID, "3 options");
        }
    }

    // ================= option count (bean validation on the DTOs -> 400 via ApiExceptionHandler) =================

    @Nested
    class OptionCountValidation {

        static ValidatorFactory factory;
        static Validator validator;

        @BeforeAll
        static void init() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void close() {
            factory.close();
        }

        private List<String> texts(int n) {
            return IntStream.range(0, n).mapToObj(i -> "opt" + i).toList();
        }

        private List<EditOptionDto> editOptions(int n) {
            return IntStream.range(0, n).mapToObj(i -> new EditOptionDto(null, "opt" + i)).toList();
        }

        private List<String> messages(Set<? extends ConstraintViolation<?>> violations) {
            return violations.stream().map(ConstraintViolation::getMessage).toList();
        }

        @Test
        void createWithFewerThanTwoOrMoreThanTenOptions_isInvalid() {
            assertThat(messages(validator.validate(new CreatePollDto("Q", texts(1)))))
                    .containsExactly("A poll needs 2-10 options");
            assertThat(messages(validator.validate(new CreatePollDto("Q", texts(11)))))
                    .containsExactly("A poll needs 2-10 options");
            assertThat(validator.validate(new CreatePollDto("Q", texts(2)))).isEmpty();
            assertThat(validator.validate(new CreatePollDto("Q", texts(10)))).isEmpty();
        }

        @Test
        void editWithFewerThanTwoOrMoreThanTenOptions_isInvalid() {
            assertThat(messages(validator.validate(new EditPollDto("Q", 0L, editOptions(1)))))
                    .containsExactly("A poll needs 2-10 options");
            assertThat(messages(validator.validate(new EditPollDto("Q", 0L, editOptions(11)))))
                    .containsExactly("A poll needs 2-10 options");
            assertThat(validator.validate(new EditPollDto("Q", 0L, editOptions(2)))).isEmpty();
            assertThat(validator.validate(new EditPollDto("Q", 0L, editOptions(10)))).isEmpty();
        }
    }

    // ================= read =================

    @Nested
    class Read {

        @Test
        void getOwned_byCreator_returnsPoll() {
            when(polls.findById(POLL_ID)).thenReturn(Optional.of(existingPoll()));

            PollResponse res = service.getOwned(POLL_ID, ALICE);

            assertThat(res.id()).isEqualTo(POLL_ID);
            assertThat(res.options()).extracting(OptionResponse::optionId).containsExactly(11L, 12L, 13L);
        }

        @Test
        void getOwned_byNonCreator_is403() {
            when(polls.findById(POLL_ID)).thenReturn(Optional.of(existingPoll()));

            assertApi(() -> service.getOwned(POLL_ID, BOB),
                    HttpStatus.FORBIDDEN, "Only the creator of this poll can do that");
        }

        @Test
        void getOwned_missing_is404() {
            when(polls.findById(POLL_ID)).thenReturn(Optional.empty());

            assertApi(() -> service.getOwned(POLL_ID, ALICE), HttpStatus.NOT_FOUND, "Poll not found");
        }

        @Test
        void getByShareId_missing_is404() {
            when(polls.findByShareId("nope")).thenReturn(Optional.empty());

            assertApi(() -> service.getByShareId("nope"), HttpStatus.NOT_FOUND, "Poll not found");
        }

        @Test
        void getByShareId_isPublic() {
            when(polls.findByShareId("share12345")).thenReturn(Optional.of(existingPoll()));

            assertThat(service.getByShareId("share12345").shareId()).isEqualTo("share12345");
        }

        @Test
        void listMine_mapsEachPoll() {
            when(polls.findByCreatorIdOrderByCreatedAtDesc(ALICE.id())).thenReturn(List.of(existingPoll()));

            assertThat(service.listMine(ALICE)).extracting(PollResponse::id).containsExactly(POLL_ID);
        }
    }

    // ================= edit =================

    @Nested
    class Edit {

        Query query;
        Poll reloaded;

        @BeforeEach
        void stubs() {
            query = mock(Query.class, RETURNS_SELF); // setParameter chains; executeUpdate returns 0
            lenient().when(em.createQuery(anyString())).thenReturn(query);
            reloaded = existingPoll(); // what findById returns after em.clear()
            lenient().when(polls.findById(POLL_ID)).thenReturn(Optional.of(existingPoll()), Optional.of(reloaded));
            lenient().when(options.save(any(PollOption.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        private EditPollDto dto(String question, long version, EditOptionDto... opts) {
            return new EditPollDto(question, version, Arrays.asList(opts));
        }

        private EditOptionDto keep(long id, String text) {
            return new EditOptionDto(id, text);
        }

        private EditOptionDto add(String text) {
            return new EditOptionDto(null, text);
        }

        @Test
        void byNonCreator_is403_andWritesNothing() {
            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(12, "Green")), BOB),
                    HttpStatus.FORBIDDEN, "Only the creator of this poll can do that");
            verifyNoInteractions(options, em, audit);
        }

        @Test
        void missingPoll_is404() {
            when(polls.findById(99L)).thenReturn(Optional.empty());

            assertApi(() -> service.edit(99L, dto("Q", 3L, keep(11, "Red"), keep(12, "Green")), ALICE),
                    HttpStatus.NOT_FOUND, "Poll not found");
        }

        @Test
        void staleVersion_is409WithExactMessage() {
            assertApi(() -> service.edit(POLL_ID, dto("Q", 2L, keep(11, "Red"), keep(12, "Green"), keep(13, "Blue")), ALICE),
                    HttpStatus.CONFLICT, STALE);
            assertThat(STALE).isEqualTo(com.example.app.web.ApiExceptionHandler.STALE_POLL_MESSAGE);
            verifyNoInteractions(options, em, audit);
        }

        @Test
        void versionChangedBeforeReload_is409() {
            setField(reloaded, "version", 4L); // someone committed between our first read and the re-fetch

            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(12, "Green"), keep(13, "Blue")), ALICE),
                    HttpStatus.CONFLICT, STALE);
            verify(polls, never()).flush();
            verifyNoInteractions(audit);
        }

        @Test
        void removingOptionWithVotes_is409() {
            when(options.deleteIfNoVotes(POLL_ID, 13L)).thenReturn(0);

            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(12, "Green")), ALICE),
                    HttpStatus.CONFLICT, "Cannot remove option 'Blue': it has votes");
            verify(em, never()).clear();
            verify(polls, never()).flush();
            verifyNoInteractions(audit);
        }

        @Test
        void removingOptionWithoutVotes_deletesIt() {
            when(options.deleteIfNoVotes(POLL_ID, 13L)).thenReturn(1);

            service.edit(POLL_ID, dto("Best colour?", 3L, keep(11, "Red"), keep(12, "Green")), ALICE);

            verify(options).deleteIfNoVotes(POLL_ID, 13L);
            verify(options, never()).deleteIfNoVotes(POLL_ID, 11L);
            verify(options, never()).deleteIfNoVotes(POLL_ID, 12L);
            verify(audit).record(ALICE.id(), AuditAction.POLL_UPDATED, POLL_ID,
                    "question unchanged; kept=2, renamed=0, added=0, removed=1");
        }

        @Test
        void removesOptionsInAscendingIdOrder() {
            when(options.deleteIfNoVotes(eq(POLL_ID), anyLong())).thenReturn(1);

            service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), add("Pink")), ALICE);

            InOrder order = inOrder(options);
            order.verify(options).deleteIfNoVotes(POLL_ID, 12L);
            order.verify(options).deleteIfNoVotes(POLL_ID, 13L);
        }

        @Test
        void optionIdFromAnotherPoll_is404() {
            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(99, "Other")), ALICE),
                    HttpStatus.NOT_FOUND, "Option not found in this poll");
            verify(options, never()).deleteIfNoVotes(anyLong(), anyLong());
            verifyNoInteractions(em, audit);
        }

        @Test
        void sameOptionIdTwice_is400() {
            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(11, "Rouge")), ALICE),
                    HttpStatus.BAD_REQUEST, "The same option is listed twice");
        }

        @Test
        void duplicateTextsIgnoringCase_is400() {
            assertApi(() -> service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), add(" RED ")), ALICE),
                    HttpStatus.BAD_REQUEST, "Options must be unique (ignoring case)");
            verifyNoInteractions(options, em, audit);
        }

        @Test
        void addedOptionsGetTheirListPositions_andKeptOptionsAreRepositioned() {
            when(options.deleteIfNoVotes(POLL_ID, 13L)).thenReturn(1);

            service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), add(" Yellow "), keep(12, "Green"), add("Purple")), ALICE);

            ArgumentCaptor<PollOption> added = ArgumentCaptor.forClass(PollOption.class);
            verify(options, times(2)).save(added.capture());
            assertThat(added.getAllValues()).extracting(PollOption::getText).containsExactly("Yellow", "Purple");
            assertThat(added.getAllValues()).extracting(PollOption::getPosition).containsExactly(1, 3);
            assertThat(added.getAllValues()).allSatisfy(o -> assertThat(o.getPoll()).isSameAs(reloaded));

            // Green moved 1 -> 2 via a targeted UPDATE (never a save, which would write vote_count back).
            verify(em, times(1)).createQuery(anyString());
            verify(query).setParameter("id", 12L);
            verify(query).setParameter("position", 2);
            verify(query).setParameter("text", "Green");
            verify(query, never()).setParameter("id", 11L); // Red unchanged: no write
            verify(query).executeUpdate();
        }

        @Test
        void renameTrimsAndUpdatesInPlace() {
            service.edit(POLL_ID, dto("Q", 3L, keep(11, "Red"), keep(12, "  Lime "), keep(13, "Blue")), ALICE);

            verify(query).setParameter("id", 12L);
            verify(query).setParameter("text", "Lime");
            verify(query).setParameter("position", 1);
            verify(options, never()).deleteIfNoVotes(anyLong(), anyLong());
            verify(options, never()).save(any());
        }

        @Test
        void flushesAfterClearingAndReloading() {
            service.edit(POLL_ID, dto("New Q", 3L, keep(11, "Red"), keep(12, "Green"), keep(13, "Blue")), ALICE);

            InOrder order = inOrder(em, polls, audit);
            order.verify(em).clear();
            order.verify(polls).flush();
            order.verify(audit).record(anyLong(), any(), anyLong(), anyString());
        }

        @Test
        void auditsPollUpdated_andReturnsReloadedPoll() {
            when(options.deleteIfNoVotes(POLL_ID, 13L)).thenReturn(1);

            PollResponse res = service.edit(POLL_ID,
                    dto("  New question ", 3L, keep(11, "Red"), keep(12, "Lime"), add("Pink")), ALICE);

            verify(audit).record(ALICE.id(), AuditAction.POLL_UPDATED, POLL_ID,
                    "question changed; kept=2, renamed=1, added=1, removed=1");
            assertThat(reloaded.getQuestion()).isEqualTo("New question");
            assertThat(res.question()).isEqualTo("New question");
            assertThat(res.options()).extracting(OptionResponse::text).contains("Pink");
        }
    }
}
