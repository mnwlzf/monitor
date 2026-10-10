package com.monitor.platform.bot.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户端取数的小工具：时间窗归一化与密钥打码。
 */
class BotUserServiceTest {

    @Test
    void shouldNormalizePeriodAliases() {
        assertEquals("today", BotUserService.normalizePeriod(null));
        assertEquals("today", BotUserService.normalizePeriod("today"));
        assertEquals("today", BotUserService.normalizePeriod("乱写"));
        assertEquals("week", BotUserService.normalizePeriod("7d"));
        assertEquals("week", BotUserService.normalizePeriod("近7天"));
        assertEquals("month", BotUserService.normalizePeriod("30d"));
        assertEquals("month", BotUserService.normalizePeriod("本月"));
    }

    /** 上游会把明文密钥一起返回，机器人这边必须打码。 */
    @Test
    void shouldMaskApiKeys() {
        assertEquals("", Sub2UserAdminClient.maskKey(null));
        assertEquals("****", Sub2UserAdminClient.maskKey("short"));
        assertEquals("sk-abc****wxyz", Sub2UserAdminClient.maskKey("sk-abcdefghijklmnopwxyz"));
    }
}