package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群聊允许触发平台功能，但最终输出由 BotGroupRedactor 脱敏。
 */
class BotAccessPolicyTest {

    private static final BotIdentity ADMIN = new BotIdentity(true, true, "admin@example.com");
    private static final BotIdentity USER = new BotIdentity(true, false, "user@example.com");
    private static final BotIdentity GUEST = BotIdentity.guest();
    private static final BotSettings SETTINGS = new BotSettings(
            true, Set.of("100", "200"), Set.of(), Set.of("100"),
            true, "/", 900, 10, false, false, 15, 5, 1.5, 30);

    @Test
    void designatedGroupAllowsPlatformFeatureForPlatformIdentities() {
        assertThat(BotAccessPolicy.platformFeatureAllowed(ADMIN, BotChannel.GROUP, 100L, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.platformFeatureAllowed(USER, BotChannel.GROUP, 100L, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.platformFeatureAllowed(GUEST, BotChannel.GROUP, 100L, SETTINGS)).isFalse();
    }

    @Test
    void nonDesignatedGroupDoesNotAllowPlatformFeature() {
        assertThat(BotAccessPolicy.platformFeatureAllowed(ADMIN, BotChannel.GROUP, 200L, SETTINGS)).isFalse();
        assertThat(BotAccessPolicy.platformFeatureAllowed(USER, BotChannel.GROUP, 200L, SETTINGS)).isFalse();
    }

    @Test
    void designatedGroupTemporarySessionAllowsPlatformFeature() {
        assertThat(BotAccessPolicy.platformFeatureAllowed(
                ADMIN, BotChannel.TEMP_SESSION, 100L, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.platformFeatureAllowed(
                USER, BotChannel.TEMP_SESSION, 100L, SETTINGS)).isTrue();
    }

    @Test
    void onlyAdminFriendPrivateAllowsPlatformFeature() {
        assertThat(BotAccessPolicy.platformFeatureAllowed(
                ADMIN, BotChannel.PRIVATE, null, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.platformFeatureAllowed(
                USER, BotChannel.PRIVATE, null, SETTINGS)).isFalse();
        assertThat(BotAccessPolicy.platformFeatureAllowed(
                GUEST, BotChannel.PRIVATE, null, SETTINGS)).isFalse();
    }

    @Test
    void openAiStatusUsesTheSameDesignatedGroupScope() {
        assertThat(BotAccessPolicy.openAiStatusAllowed(
                ADMIN, BotChannel.GROUP, 100L, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.openAiStatusAllowed(
                USER, BotChannel.GROUP, 100L, SETTINGS)).isTrue();
        assertThat(BotAccessPolicy.openAiStatusAllowed(
                ADMIN, BotChannel.GROUP, 200L, SETTINGS)).isFalse();
        assertThat(BotAccessPolicy.openAiStatusAllowed(
                GUEST, BotChannel.GROUP, 100L, SETTINGS)).isFalse();
    }
}