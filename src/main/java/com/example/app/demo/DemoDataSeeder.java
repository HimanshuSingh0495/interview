package com.example.app.demo;

import com.example.app.auth.AuthService;
import com.example.app.auth.CurrentUser;
import com.example.app.auth.dto.AuthResponse;
import com.example.app.auth.dto.RegistrationDto;
import com.example.app.poll.PollService;
import com.example.app.poll.dto.CreatePollDto;
import com.example.app.poll.dto.PollResponse;
import com.example.app.user.UserRepository;
import com.example.app.vote.VoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the demo accounts and a sample poll on startup (hosted demo only: app.demo.seed=true).
 * The free host wipes its disk when it sleeps, so this keeps the README logins working after every restart.
 * Goes through the normal services, so passwords are hashed, counters updated and actions audited as usual.
 */
@Component
@ConditionalOnProperty(name = "app.demo.seed", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AuthService auth;
    private final PollService polls;
    private final VoteService votes;
    private final UserRepository users;

    DemoDataSeeder(AuthService auth, PollService polls, VoteService votes, UserRepository users) {
        this.auth = auth;
        this.polls = polls;
        this.votes = votes;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByUsername("demo")) {
            return;
        }
        CurrentUser demo = register("demo", "demo12345");
        CurrentUser friend = register("friend", "friend12345");
        CurrentUser sam = register("sam_votes", "sam12345678");

        PollResponse poll = polls.create(new CreatePollDto(
                "Which language should our next service be written in?",
                List.of("Java", "Go", "Kotlin", "TypeScript")), demo);
        Long java = poll.options().get(0).optionId();
        Long kotlin = poll.options().get(2).optionId();

        votes.cast(poll.id(), kotlin, demo);
        votes.cast(poll.id(), java, friend);
        votes.cast(poll.id(), java, sam);
        log.info("Seeded demo data: users demo/friend/sam_votes, sample poll /p/{}", poll.shareId());
    }

    private CurrentUser register(String username, String password) {
        AuthResponse r = auth.register(new RegistrationDto(username, password));
        return new CurrentUser(r.userId(), r.username());
    }
}
