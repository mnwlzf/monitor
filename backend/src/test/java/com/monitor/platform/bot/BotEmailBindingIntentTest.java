package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotEmailBindingIntentTest {

    @Test
    void shouldParseNaturalLanguageBind() {
        var intent = BotEmailBindingIntent.parse("帮我绑定邮箱 user@example.com").orElseThrow();
        assertEquals(BotEmailBindingIntent.Action.BIND, intent.action());
        assertEquals("user@example.com", intent.value());

        var prefix = BotEmailBindingIntent.parse("把 user@example.com 绑定到我的QQ").orElseThrow();
        assertEquals(BotEmailBindingIntent.Action.BIND, prefix.action());
        assertEquals("user@example.com", prefix.value());
    }

    @Test
    void shouldParseVerificationAndUnbind() {
        var verify = BotEmailBindingIntent.parse("验证码 123456").orElseThrow();
        assertEquals(BotEmailBindingIntent.Action.VERIFY, verify.action());
        assertEquals("123456", verify.value());

        assertEquals(BotEmailBindingIntent.Action.UNBIND,
                BotEmailBindingIntent.parse("解除绑定邮箱").orElseThrow().action());
    }

    @Test
    void shouldIgnoreQuestionsAndAmbiguousText() {
        assertTrue(BotEmailBindingIntent.parse("怎么绑定邮箱？").isEmpty());
        assertTrue(BotEmailBindingIntent.parse("绑定邮箱是做什么的").isEmpty());
    }
}
