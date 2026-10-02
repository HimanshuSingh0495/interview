package com.example.app.poll;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShareIdGeneratorTest {

    private final ShareIdGenerator generator = new ShareIdGenerator();

    @Test
    void next_isTenBase62Characters() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.next()).matches("[0-9A-Za-z]{10}");
        }
    }

    @Test
    void next_fitsTheShareIdColumn() {
        assertThat(generator.next().length()).isLessThanOrEqualTo(12); // poll.share_id VARCHAR(12)
    }

    @Test
    void next_isRandom() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.next());
        }
        assertThat(ids).hasSize(10_000);
    }
}
