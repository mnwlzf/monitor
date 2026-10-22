package com.monitor.platform.bot.user;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Locale;

/**
 * 管理员专用的余额调整工具，按请求构造并绑定当前管理员与当前消息。
 *
 * <p>资金操作必须两步完成：第一次只生成预览，第二次必须由当前消息明确包含
 * 「确认充值 + 确认码」才会执行。这样即使模型想提前调用确认工具，也会被服务端拦下。</p>
 */
public class BotBalanceTools {

    private final BotBalanceService service;
    private final String operatorEmail;
    private final String currentMessage;

    public BotBalanceTools(BotBalanceService service, String operatorEmail, String currentMessage) {
        this.service = service;
        this.operatorEmail = operatorEmail;
        this.currentMessage = currentMessage;
    }

    @Tool(description = "仅管理员可用：按用户 ID、邮箱或用户名查询 Sub2API 平台用户。"
            + "ID/邮箱/用户名精确匹配时返回用户详情；模糊匹配只返回候选，不自动选人。")
    public String searchPlatformUsers(String query) {
        return service.search(query);
    }

    @Tool(description = "仅管理员可用：发起给指定平台用户增加余额的预览，不会立即修改余额。"
            + "account 可填用户 ID、精确邮箱或精确用户名；amountUsd 必须大于 0，最多两位小数。"
            + "工具会返回当前余额、预计余额和一次性确认码。"
            + "只有管理员在下一条消息明确回复「确认充值 <确认码>」后，才能调用 confirmBalanceIncrease。")
    public String requestBalanceIncrease(String account,
                                         Double amountUsd,
                                         @ToolParam(required = false, description = "可选备注，例如支付波动人工补款")
                                         String reason) {
        return service.preview(account, amountUsd, reason, operatorEmail);
    }

    @Tool(description = "仅管理员可用：执行已经预览过的余额增加。"
            + "只有在当前用户消息明确包含「确认充值 <确认码>」时才允许调用；"
            + "不能用于 set/subtract，也不能绕过预览直接加余额。")
    public String confirmBalanceIncrease(String confirmationCode) {
        String code = confirmationCode == null ? "" : confirmationCode.trim().toUpperCase(Locale.ROOT);
        if (!explicitlyConfirmed(code)) {
            return "二次确认未通过。请先查看充值预览，然后回复：确认充值 " + code;
        }
        return service.confirm(code, operatorEmail);
    }

    private boolean explicitlyConfirmed(String code) {
        if (code.isBlank() || currentMessage == null || currentMessage.isBlank()) {
            return false;
        }
        String normalized = currentMessage.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.contains("确认充值") && normalized.contains(code);
    }
}
