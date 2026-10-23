package com.monitor.platform.bot.error;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 管理员错误排障工具。只挂给管理员人设，普通用户拿不到。
 */
@Component
public class BotErrorTools {

    private static final String CONTEXT_USER_ID = "qqUserId";

    private final BotErrorService service;
    private final BotInstanceErrorService instanceService;
    private final BotErrorQueryAuditService auditService;
    private final BotErrorQueryGuard queryGuard;

    public BotErrorTools(BotErrorService service, BotInstanceErrorService instanceService,
                         BotErrorQueryAuditService auditService,
                         BotErrorQueryGuard queryGuard) {
        this.service = service;
        this.instanceService = instanceService;
        this.auditService = auditService;
        this.queryGuard = queryGuard;
    }

    @Tool(description = "仅管理员可用：诊断指定时间窗内的错误情况。"
            + "返回错误数、错误率、429/529、401/403、5xx、主要错误类型和问题账号，并给出建议。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 1h。")
    public String diagnoseErrors(String range, ToolContext context) {
        return guarded("ai-diagnose", range, context,
                () -> service.diagnoseReport(range).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询最近的错误列表。"
            + "不需要请求 ID，可以按时间窗、状态码、5xx、限流、配额或关键词筛选。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 1h。"
            + "filter 示例：429、401、5xx、限流、配额、账号名、模型名。")
    public String recentErrors(String range,
                               @ToolParam(required = false, description = "状态码/5xx/限流/配额/关键词") String filter,
                               ToolContext context) {
        String query = join(range, filter);
        return guarded("ai-errors", query, context,
                () -> service.recentReport(query).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询错误最多的账号排行。"
            + "range 默认 1h，limit 为 1-20。")
    public String topErrorAccounts(String range,
                                   @ToolParam(required = false, description = "返回条数 1-20，默认 10") Integer limit,
                                   ToolContext context) {
        String query = join(range, limit == null ? null : String.valueOf(limit));
        return guarded("ai-error-accounts", query, context,
                () -> service.accountReport(query).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询指定平台用户的最近错误。"
            + "user 可以是邮箱或用户 ID；range 默认 1h。")
    public String errorsByUser(String user,
                               @ToolParam(required = false, description = "时间窗，默认 1h") String range,
                               ToolContext context) {
        String query = join(user, range);
        return guarded("ai-error-user", query, context,
                () -> service.userReport(user, range).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询多个 Sub2API 实例的错误概览，比较请求数、错误率、429/529、上游错误和健康分。"
            + "range 默认 1h。")
    public String errorByInstances(String range, ToolContext context) {
        return guarded("ai-error-instances", range, context,
                () -> instanceService.report(range).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询恢复型上游错误和重试情况。"
            + "这些请求上游曾失败，但最终客户端成功；展示账号、模型、最大尝试次数和影响面。"
            + "range 默认 1h，limit 为 1-20。")
    public String recoveredUpstreamErrors(String range,
                                          @ToolParam(required = false, description = "返回条数 1-20，默认 10") Integer limit,
                                          ToolContext context) {
        String query = join(range, limit == null ? null : String.valueOf(limit));
        return guarded("ai-recovered-errors", query, context,
                () -> service.recoveredReport(query).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询错误时间窗内发生的上游变更，用来判断错误是否由分组、Key、账号或倍率变更引起。"
            + "range 默认 1h。")
    public String recentErrorChanges(String range, ToolContext context) {
        return guarded("ai-error-changes", range, context,
                () -> service.changeReport(range).toPlainText());
    }

    @Tool(description = "仅管理员可用：对比当前时间窗与上一等长时间窗的错误趋势。"
            + "展示错误数、错误率、429/529、401/403、5xx、入口拒绝和未解决数的变化。"
            + "range 默认 1h。")
    public String errorTrend(String range, ToolContext context) {
        return guarded("ai-error-trend", range, context,
                () -> service.trendReport(range).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询错误指纹排行。"
            + "按错误阶段、类型、状态码、provider_error_code 和归一化消息聚合，展示影响面和时间范围。"
            + "range 默认 1h，limit 为 1-20。")
    public String topErrorFingerprints(String range,
                                       @ToolParam(required = false, description = "返回条数 1-20，默认 10") Integer limit,
                                       ToolContext context) {
        String query = join(range, limit == null ? null : String.valueOf(limit));
        return guarded("ai-error-fingerprints", query, context,
                () -> service.fingerprintReport(query).toPlainText());
    }

    @Tool(description = "仅管理员可用：查询入口拒绝统计，包括无效 API Key、Key 禁用、IP 限制、用户禁用、分组禁用或未授权。"
            + "这些请求通常不会出现在普通错误列表里。range 默认 1h。")
    public String ingressRejections(String range, ToolContext context) {
        return guarded("ai-ingress", range, context,
                () -> service.ingressReport(range).toPlainText());
    }

    @Tool(description = "仅管理员可用：按时间窗、日志级别和关键词查询系统日志，不需要请求 ID。"
            + "level 可填 warn/error 等；keyword 可填 529、upstream、模型名或组件名。range 默认 1h。")
    public String recentSystemLogs(String range,
                                   @ToolParam(required = false, description = "日志级别，如 warn/error") String level,
                                   @ToolParam(required = false, description = "关键词，如 529、upstream、模型名") String keyword,
                                   ToolContext context) {
        String query = join(join(range, level), keyword);
        return guarded("ai-error-logs", query, context,
                () -> service.systemLogReport(query).toPlainText());
    }

    @Tool(description = "仅管理员可用：查看错误查询审计和限流拦截情况。"
            + "展示查询次数、被拦截次数、查询用户数、动作排行和最近查询。range 默认 24h，limit 为 1-50。")
    public String errorQueryAudit(String range,
                                  @ToolParam(required = false, description = "返回条数 1-50，默认 20") Integer limit,
                                  ToolContext context) {
        String query = join(range, limit == null ? null : String.valueOf(limit));
        return guarded("ai-error-audit", query, context,
                () -> auditService.report(query).toPlainText());
    }

    private String guarded(String action, String query, ToolContext context, Supplier<String> call) {
        Long userId = userId(context);
        if (userId == null) {
            return call.get();
        }
        if (!queryGuard.allow(userId)) {
            auditService.record(userId, action, query, false, 0);
            return "错误查询太频繁，请稍后再试。";
        }
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            auditService.record(userId, action, query, true, durationMs);
        }
    }

    private static Long userId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(CONTEXT_USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " " + second;
    }
}
