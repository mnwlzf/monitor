package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGroupReplyPolicyTest {

    @Test
    void shouldApplyRandomProbabilityBoundary() {
        BotGroupReplyPolicy policy = new BotGroupReplyPolicy(
                "111", BotGroupReplyStrategy.RANDOM, 25, List.of());

        assertTrue(policy.shouldTrigger("任意消息", 0.249));
        assertFalse(policy.shouldTrigger("任意消息", 0.25));
    }

    @Test
    void shouldMatchKeywordsCaseInsensitively() {
        BotGroupReplyPolicy policy = new BotGroupReplyPolicy(
                "111", BotGroupReplyStrategy.KEYWORD, 10, List.of("API", "报错"));

        assertTrue(policy.shouldTrigger("接口 api 挂了", 0.99));
        assertTrue(policy.shouldTrigger("这里报错了", 0.99));
        assertFalse(policy.shouldTrigger("今天天气不错", 0.01));
    }
}
