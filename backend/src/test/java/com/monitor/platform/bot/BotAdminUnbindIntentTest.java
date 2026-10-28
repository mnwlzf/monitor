package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAdminUnbindIntentTest {

    @Test
    void shouldParseAdminNaturalLanguageRequest() {
        var intent = BotAdminUnbindIntent.parse("用户 123456 邮箱 user@example.com 解除绑定").orElseThrow();
        assertEquals(BotAdminUnbindIntent.Action.REQUEST, intent.action());
        assertEquals(123456L, intent.targetQq());
        assertEquals("user@example.com", intent.email());
    }

    @Test
    void shouldParseCommandRequestAndConfirmation() {
        var command = BotAdminUnbindIntent.parse("解绑 123456 user@example.com").orElseThrow();
        assertEquals(BotAdminUnbindIntent.Action.REQUEST, command.action());

        var confirm = BotAdminUnbindIntent.parse("确认解绑 ABC234").orElseThrow();
        assertEquals(BotAdminUnbindIntent.Action.CONFIRM, confirm.action());
        assertEquals("ABC234", confirm.code());
    }

    @Test
    void shouldDetectMalformedUnbindLanguage() {
        assertTrue(BotAdminUnbindIntent.looksLikeUnbind("解除绑定"));
        assertTrue(BotAdminUnbindIntent.looksLikeUnbind("用户 123456 解除绑定"));
        assertTrue(BotAdminUnbindIntent.parse("用户 123456 解除绑定").isEmpty());
    }
}
