package com.monitor.platform.bot.user;

import com.monitor.platform.bot.report.BotReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BotUserToolsTest {

    private final BotUserService service = mock(BotUserService.class);
    private final BotKeyUsageService keyUsageService = mock(BotKeyUsageService.class);
    private final BotUserTools tools = new BotUserTools(
            service, keyUsageService, "bound@example.com", 2027142766L);

    @Test
    void shouldAllowOwnQqForUsageQuery() {
        when(service.usageReport("bound@example.com", "today"))
                .thenReturn(BotReport.fromMarkdown("今日使用详情"));

        String result = tools.myUsage("today", "2027142766");

        assertThat(result).contains("今日使用详情");
        verify(service).usageReport("bound@example.com", "today");
    }

    @Test
    void shouldAllowOwnQqEmailForUsageQuery() {
        when(service.usageReport("bound@example.com", "today"))
                .thenReturn(BotReport.fromMarkdown("今日使用详情"));

        String result = tools.myUsage("today", "2027142766@qq.com");

        assertThat(result).contains("今日使用详情");
        verify(service).usageReport("bound@example.com", "today");
    }

    @Test
    void shouldAllowBoundEmailForUsageQuery() {
        when(service.usageReport("bound@example.com", "today"))
                .thenReturn(BotReport.fromMarkdown("今日使用详情"));

        String result = tools.myUsage("today", "BOUND@example.com");

        assertThat(result).contains("今日使用详情");
        verify(service).usageReport("bound@example.com", "today");
    }

    @Test
    void shouldRejectAnotherAccountBeforeQuerying() {
        String result = tools.myUsage("today", "999999999@qq.com");

        assertThat(result).contains("只能查询当前 QQ 本人");
        verifyNoInteractions(service, keyUsageService);
    }
}
