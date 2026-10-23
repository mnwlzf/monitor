package com.monitor.platform.bot.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotErrorQueryGuardTest {

    @Test
    void shouldLimitEachUserToTwelveQueriesPerMinute() {
        BotErrorQueryGuard guard = new BotErrorQueryGuard();

        for (int i = 0; i < 12; i++) {
            assertThat(guard.allow(100L)).isTrue();
        }

        assertThat(guard.allow(100L)).isFalse();
        assertThat(guard.allow(200L)).isTrue();
    }
}
