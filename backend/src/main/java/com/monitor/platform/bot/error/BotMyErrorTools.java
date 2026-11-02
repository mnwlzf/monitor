package com.monitor.platform.bot.error;

import com.monitor.platform.bot.identity.Emails;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 普通用户自己的错误查询工具，按请求绑定发送者邮箱和 QQ。
 *
 * <p>工具允许模型传入用户消息里写出的邮箱或 QQ 号，但服务端会先校验它是否属于当前发送者；
 * 校验通过后仍只查询本人，不能借此查询别人的错误。</p>
 */
public class BotMyErrorTools {

    static final String ACCOUNT_NOT_SELF =
            "只能查询当前 QQ 本人的错误；如果这是你的账号，请确认邮箱或 QQ 号是否正确。";

    private final BotErrorService service;
    private final String email;
    private final Long qq;
    private final BotErrorQueryGuard queryGuard;
    private final BotErrorQueryAuditService auditService;

    public BotMyErrorTools(BotErrorService service, String email, Long qq,
                           BotErrorQueryGuard queryGuard,
                           BotErrorQueryAuditService auditService) {
        this.service = service;
        this.email = email;
        this.qq = qq;
        this.queryGuard = queryGuard;
        this.auditService = auditService;
    }

    @Tool(description = "查询当前用户自己的最近错误。不需要请求 ID，可以按时间窗、状态码或关键词筛选。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 24h。"
            + "filter 示例：429、401、5xx、限流、模型名。"
            + "account 可传用户消息里写出的邮箱或 QQ 号，服务端会校验是否属于当前发送者；"
            + "与本人一致就正常查询，不要预先拒绝。")
    public String myRecentErrors(String range,
                                 @ToolParam(required = false, description = "状态码/5xx/限流/模型名等筛选") String filter,
                                 @ToolParam(required = false, description = "用户消息里写出的邮箱或 QQ 号；不传表示本人") String account,
                                 ToolContext context) {
        if (!isSelfOrUnspecified(account)) {
            return ACCOUNT_NOT_SELF;
        }
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

    private boolean isSelfOrUnspecified(String account) {
        return account == null || account.isBlank()
                || Emails.matchesOwner(account, email, qq);
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
