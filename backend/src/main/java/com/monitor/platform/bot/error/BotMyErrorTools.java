package com.monitor.platform.bot.error;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Supplier;

/**
 * 普通用户自己的错误查询工具，按请求绑定发送者邮箱。
 *
 * <p>构造时注入邮箱，工具本身不接受用户标识，因此大模型无法借此查询别人。</p>
 */
public class BotMyErrorTools {

    private final BotErrorService service;
    private final String email;
    private final BotErrorQueryGuard queryGuard;
    private final BotErrorQueryAuditService auditService;

    public BotMyErrorTools(BotErrorService service, String email,
                           BotErrorQueryGuard queryGuard,
                           BotErrorQueryAuditService auditService) {
        this.service = service;
        this.email = email;
        this.queryGuard = queryGuard;
        this.auditService = auditService;
    }

    @Tool(description = "查询当前用户自己的最近错误。不需要请求 ID，可以按时间窗、状态码或关键词筛选。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 24h。"
            + "filter 示例：429、401、5xx、限流、模型名。")
    public String myRecentErrors(String range,
                                 @ToolParam(required = false, description = "状态码/5xx/限流/模型名等筛选") String filter,
                                 ToolContext context) {
        String query = join(range, filter);
        Long userId = userId(context);
        if (userId == null) {
            return service.myReport(email, query).toPlainText();
        }
        if (!queryGuard.allow(userId)) {
            auditService.record(userId, "ai-my-errors", query, false, 0);
            return "错误查询太频繁，请稍后再试。";
        }
        long start = System.nanoTime();
        try {
            return service.myReport(email, query).toPlainText();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            auditService.record(userId, "ai-my-errors", query, true, durationMs);
        }
    }

    private static Long userId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get("qqUserId");
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
