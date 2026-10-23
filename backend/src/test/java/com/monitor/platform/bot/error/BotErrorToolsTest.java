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

class BotErrorToolsTest {

    private final BotErrorService service = mock(BotErrorService.class);
    private final BotInstanceErrorService instanceService = mock(BotInstanceErrorService.class);
    private final BotErrorQueryAuditService auditService = mock(BotErrorQueryAuditService.class);
    private final BotErrorQueryGuard guard = mock(BotErrorQueryGuard.class);
    private final BotErrorTools tools = new BotErrorTools(service, instanceService, auditService, guard);

    @Test
    void shouldAuditNaturalLanguageErrorQuery() {
        ToolContext context = new ToolContext(Map.of("qqUserId", 123L));
        when(guard.allow(123L)).thenReturn(true);
        when(service.diagnoseReport("1h")).thenReturn(BotReport.fromMarkdown("诊断结果"));

        String result = tools.diagnoseErrors("1h", context);

        assertThat(result).contains("诊断结果");
        verify(service).diagnoseReport("1h");
        verify(auditService).record(eq(123L), eq("ai-diagnose"), eq("1h"), eq(true), anyLong());
    }

    @Test
    void shouldRejectNaturalLanguageQueryWhenRateLimited() {
        ToolContext context = new ToolContext(Map.of("qqUserId", 123L));
        when(guard.allow(123L)).thenReturn(false);

        String result = tools.diagnoseErrors("1h", context);

        assertThat(result).contains("太频繁");
        verify(service, never()).diagnoseReport("1h");
        verify(auditService).record(123L, "ai-diagnose", "1h", false, 0);
    }
}
