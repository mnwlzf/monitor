package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotForbiddenWordIntentTest {

    @Test
    void shouldRecognizeViewIntent() {
        assertEquals(Optional.of("违禁词"), BotForbiddenWordIntent.toCommandLine("查看违禁词"));
        assertEquals(Optional.of("违禁词"), BotForbiddenWordIntent.toCommandLine("当前违禁词有哪些？"));
        assertEquals(Optional.of("违禁词"), BotForbiddenWordIntent.toCommandLine("show forbidden words"));
    }

    @Test
    void shouldRecognizeAddIntent() {
        assertEquals(Optional.of("添加违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("添加违禁词 测试词"));
        assertEquals(Optional.of("添加违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("帮我新增违禁词：测试词。"));
        assertEquals(Optional.of("添加违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("把“测试词”加入违禁词"));
        assertEquals(Optional.of("添加违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("add forbidden word 测试词"));
    }

    @Test
    void shouldRecognizeDeleteIntent() {
        assertEquals(Optional.of("删除违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("删除违禁词 测试词"));
        assertEquals(Optional.of("删除违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("帮我移除违禁词：测试词。"));
        assertEquals(Optional.of("删除违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("把“测试词”从违禁词中删除"));
        assertEquals(Optional.of("删除违禁词 测试词"), BotForbiddenWordIntent.toCommandLine("delete banned word 测试词"));
    }

    @Test
    void shouldIgnoreAmbiguousSentences() {
        assertEquals(Optional.empty(), BotForbiddenWordIntent.toCommandLine("违禁词是怎么工作的？"));
        assertEquals(Optional.empty(), BotForbiddenWordIntent.toCommandLine("不要添加违禁词 测试词"));
        assertEquals(Optional.empty(), BotForbiddenWordIntent.toCommandLine("帮我看看最近的错误"));
    }
}
