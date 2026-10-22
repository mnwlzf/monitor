package com.monitor.platform.bot.user;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Locale;

/**
 * 管理员专用的余额调整工具，按请求构造并绑定当前管理员与当前消息。
 *
 * <p>流程分三步：模糊查询返回候选、管理员选定具体用户并生成预览、管理员明确确认后执行。
 * 确认工具会校验当前消息是否包含「确认充值 + 确认码」，模型无法提前绕过。</p>
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

    @Tool(description = "仅管理员可用：按用户 ID、邮箱或用户名模糊查询 Sub2API 平台用户。"
            + "无论是否看起来完全匹配，都只返回候选列表，不自动选定具体人员。")
    public String searchPlatformUsers(String query) {
        return service.search(query);
    }

    @Tool(description = "仅管理员可用：按用户 ID、邮箱或用户名模糊查询充值候选，不会修改余额。"
            + "amountUsd 必须大于 0，最多两位小数。工具返回候选列表和金额，"
            + "管理员确认具体人员后，再用候选 ID 调用 previewBalanceIncreaseForUser。")
    public String findBalanceRechargeCandidates(String account, Double amountUsd) {
        return service.candidatesForRecharge(account, amountUsd);
    }

    @Tool(description = "仅管理员可用：管理员已从候选列表确认具体人员后，用 userId 生成充值预览。"
            + "只允许增加余额；amountUsd 必须大于 0，最多两位小数。"
            + "工具返回当前余额、预计余额和一次性确认码，"
            + "只有管理员在下一条消息明确回复「确认充值 <确认码>」后，才能调用 confirmBalanceIncrease。")
    public String previewBalanceIncreaseForUser(long userId,
                                                Double amountUsd,
                                                @ToolParam(required = false, description = "可选备注，例如支付波动人工补款")
                                                String reason) {
        return service.previewForUser(userId, amountUsd, reason, operatorEmail);
    }

    @Tool(description = "仅管理员可用：执行已经预览过的余额增加。"
            + "只有在当前用户消息明确包含「确认充值 <确认码>」时才允许调用；"
            + "不能用于 set/subtract，也不能绕过候选确认和预览直接加余额。")
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
