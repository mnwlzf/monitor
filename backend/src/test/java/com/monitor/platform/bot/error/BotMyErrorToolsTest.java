package com.monitor.platform.bot.error;

import com.monitor.platform.bot.report.BotReport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotMyErrorToolsTest {

    private final BotErrorService service = mock(BotErrorService.class);
    private final BotErrorQueryAuditService auditService = mock(BotErrorQueryAuditService.class);
    private final BotErrorQueryGuard guard = mock(BotErrorQueryGuard.class);
    private final BotMyErrorTools tools = new BotMyErrorTools(
            service, "user@example.com", guard, auditService);

    @Test
    void shouldAuditNaturalLanguageSelfQuery() {
        ToolContext context = new ToolContext(Map.of("qqUserId", 123L));
        when(guard.allow(123L)).thenReturn(true);
        when(service.myReport("user@example.com", "24h 429"))
                .thenReturn(BotReport.fromMarkdown("我的错误"));

        String result = tools.myRecentErrors("24h", "429", context);

        assertThat(result).contains("我的错误");
        verify(service).myReport("user@example.com", "24h 429");
        verify(auditService).record(eq(123L), eq("ai-my-errors"),
                eq("24h 429"), eq(true), anyLong());
    }

    @Test
    void shouldRejectNaturalLanguageSelfQueryWhenRateLimited() {
        ToolContext context = new ToolContext(Map.of("qqUserId", 123L));
        when(guard.allow(123L)).thenReturn(false);

        String result = tools.myRecentErrors("24h", "429", context);

        assertThat(result).contains("太频繁");
        verify(service, never()).myReport("user@example.com", "24h 429");
        verify(auditService).record(123L, "ai-my-errors", "24h 429", false, 0);
    }
}
