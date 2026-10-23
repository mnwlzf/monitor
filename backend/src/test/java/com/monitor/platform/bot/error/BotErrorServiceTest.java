package com.monitor.platform.bot.error;

import com.monitor.platform.bot.error.Sub2ErrorReader.AccountGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorDetail;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorRow;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorSummary;
import com.monitor.platform.bot.error.Sub2ErrorReader.IngressRejectGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.SystemLogRow;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.user.BotUserService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotErrorServiceTest {

    private final Sub2ErrorReader reader = mock(Sub2ErrorReader.class);
    private final BotUserService userService = mock(BotUserService.class);
    private final BotErrorService service = new BotErrorService(reader, userService);

    @Test
    void shouldBuildAdminDiagnosisWithoutRequestId() {
        OffsetDateTime now = OffsetDateTime.now();
        when(reader.isAvailable()).thenReturn(true);
        when(reader.summary(any(), any(), isNull()))
                .thenReturn(new ErrorSummary(10, 2, 6, 4, 2, 1, 3));
        when(reader.topGroups(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(new ErrorGroup("upstream", "rate_limit_error", 429,
                        5, 0, now, "upstream overload")));
        when(reader.topAccounts(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(new AccountGroup(1L, "account-a", "openai",
                        5, 4, 0, 0, now)));
        when(reader.successCount(any(), any(), isNull())).thenReturn(100L);
        when(reader.recent(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(errorRow(now)));

        BotReport report = service.diagnoseReport("1h");

        assertThat(report.title()).contains("错误诊断");
        assertThat(report.toPlainText())
                .contains("429 / 529")
                .contains("account-a")
                .contains("上游状态")
                .contains("推断原因")
                .contains("上游 529 过载");
    }

    @Test
    void shouldScopeMyErrorsToBoundUser() {
        OffsetDateTime now = OffsetDateTime.now();
        when(userService.resolveUserId("user@example.com")).thenReturn(7L);
        when(reader.isAvailable()).thenReturn(true);
        when(reader.summary(any(), any(), any())).thenReturn(new ErrorSummary(1, 0, 1, 1, 0, 0, 0));
        when(reader.topGroups(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(new ErrorGroup("upstream", "rate_limit_error", 429,
                        1, 0, now, "rate limited")));
        when(reader.recent(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(errorRow(now)));
        when(reader.successCount(any(), any(), any())).thenReturn(20L);

        BotReport report = service.myReport("user@example.com", "24h");

        assertThat(report.title()).contains("我的错误");
        assertThat(report.toPlainText())
                .contains("#12")
                .contains("429")
                .doesNotContain("user@example.com")
                .doesNotContain("account-a");
    }

    @Test
    void shouldInferDisabledApiKeyFromCrossTableContext() {
        OffsetDateTime now = OffsetDateTime.now();
        ErrorRow row = errorRow(now, "disabled");
        when(reader.isAvailable()).thenReturn(true);
        when(reader.detail(12L, null)).thenReturn(new ErrorDetail(
                row,
                "",
                "invalid key",
                "",
                "",
                "",
                "/v1/responses",
                null, null, null, null, null));
        when(reader.systemLogs(any(), any(), anyInt())).thenReturn(List.of());

        BotReport report = service.recentReport("#12");

        assertThat(report.toPlainText())
                .contains("API Key 已禁用")
                .contains("高置信度")
                .contains("恢复 API Key");
    }

    @Test
    void shouldBuildIngressRejectReport() {
        OffsetDateTime now = OffsetDateTime.now();
        when(reader.isAvailable()).thenReturn(true);
        when(reader.ingressRejectCount(any(), any())).thenReturn(12L);
        when(reader.topIngressRejects(any(), any(), anyInt())).thenReturn(List.of(
                new IngressRejectGroup("invalid_api_key", "responses", "openai",
                        "1.2.3.4", null, null, 10, now, now)));

        BotReport report = service.ingressReport("1h");

        assertThat(report.title()).contains("入口拒绝");
        assertThat(report.toPlainText())
                .contains("无效 API Key")
                .contains("1.2.3.4")
                .contains("不会出现在普通错误列表");
    }

    @Test
    void shouldBuildSystemLogReportWithoutRequestId() {
        OffsetDateTime now = OffsetDateTime.now();
        when(reader.isAvailable()).thenReturn(true);
        when(reader.recentSystemLogs(any(), any(), eq("error"), eq("529"), anyInt()))
                .thenReturn(List.of(new SystemLogRow(now, "error", "gateway", "upstream 529")));

        BotReport report = service.systemLogReport("1h error 529");

        assertThat(report.title()).contains("错误日志");
        assertThat(report.toPlainText())
                .contains("gateway")
                .contains("upstream 529")
                .contains("不需要请求 ID");
    }

    @Test
    void shouldShowDetailByBotErrorNumber() {
        OffsetDateTime now = OffsetDateTime.now();
        ErrorRow row = errorRow(now);
        when(reader.isAvailable()).thenReturn(true);
        when(reader.detail(12L, null)).thenReturn(new ErrorDetail(
                row,
                "client body",
                "upstream overloaded",
                "detail",
                "[]",
                "sk-1234",
                "/v1/responses",
                null, null, null, null, null));
        when(reader.systemLogs(any(), any(), anyInt()))
                .thenReturn(List.of(new SystemLogRow(now, "warn", "gateway", "upstream 529")));
        when(reader.nearbySuccessCount(any(), any(), any(), any(), anyInt())).thenReturn(3L);

        BotReport report = service.recentReport("#12");

        assertThat(report.title()).contains("错误详情 #12");
        assertThat(report.toPlainText())
                .contains("upstream overloaded")
                .contains("关联系统日志")
                .contains("3 次成功请求")
                .doesNotContain("request_id");
    }

    private static ErrorRow errorRow(OffsetDateTime now) {
        return errorRow(now, "active");
    }

    private static ErrorRow errorRow(OffsetDateTime now, String apiKeyStatus) {
        return new ErrorRow(
                12L,
                now,
                "upstream",
                "rate_limit_error",
                "P1",
                529,
                529,
                "openai",
                "gpt-5",
                "gpt-5",
                "",
                "upstream overloaded",
                7L,
                "user@example.com",
                "active",
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                3L,
                "key-a",
                apiKeyStatus,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                1L,
                "account-a",
                "active",
                true,
                null,
                null,
                null,
                "",
                null,
                "",
                2L,
                "group-a",
                "active",
                false,
                "",
                null,
                true,
                "/v1/responses",
                "/v1/responses",
                "1.2.3.4",
                "test-agent",
                true,
                false,
                false,
                1200,
                300L,
                "internal-request-id",
                ""
        );
    }
}
