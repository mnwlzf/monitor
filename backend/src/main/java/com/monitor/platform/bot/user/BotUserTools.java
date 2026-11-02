package com.monitor.platform.bot.user;

import com.monitor.platform.bot.identity.Emails;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 用户端工具：<strong>按请求构造</strong>，绑定到「当前发送者」。
 *
 * <p>工具允许模型传入用户消息里写出的邮箱或 QQ 号，但不会直接按这个标识取数：
 * 服务端先校验它是否属于当前 QQ（绑定邮箱、QQ 号或 {@code <QQ号>@qq.com}），
 * 匹配后仍只查询发送者自己的账号。这样用户明确写自己的邮箱时不会被误拒，
 * 同时也不存在「传别人的邮箱越权查询」的入口。</p>
 */
public class BotUserTools {

    static final String ACCOUNT_NOT_SELF =
            "只能查询当前 QQ 本人绑定的账号；如果这是你的账号，请确认邮箱或 QQ 号是否正确。";

    private final BotUserService service;
    private final BotKeyUsageService keyUsageService;
    private final String email;
    private final Long qq;

    public BotUserTools(BotUserService service, BotKeyUsageService keyUsageService, String email, Long qq) {
        this.keyUsageService = keyUsageService;
        this.service = service;
        this.email = email;
        this.qq = qq;
    }

    @Tool(description = "查询当前用户自己的账户余额、冻结额度、累计充值、账号状态与最近活跃时间。"
            + "account 可传用户消息里写出的邮箱或 QQ 号，服务端会校验是否属于当前发送者；"
            + "与本人一致就正常查询，不要预先拒绝。")
    public String myBalance(
            @ToolParam(required = false, description = "用户消息里写出的邮箱或 QQ 号；不传表示本人") String account) {
        if (!isSelfOrUnspecified(account)) {
            return ACCOUNT_NOT_SELF;
        }
        return service.balanceReport(email).toPlainText();
    }

    @Tool(description = "查询当前用户自己的用量：请求数、输入/输出/缓存 Token、实际消耗与平均耗时。"
            + "range 取值 today / week / month，默认 today。account 可传用户消息里写出的邮箱或 QQ 号；"
            + "服务端会校验是否属于当前发送者，与本人一致就正常查询，不要预先拒绝。")
    public String myUsage(
            @ToolParam(required = false, description = "时间范围：today / week / month，默认 today") String range,
            @ToolParam(required = false, description = "用户消息里写出的邮箱或 QQ 号；不传表示本人") String account) {
        if (!isSelfOrUnspecified(account)) {
            return ACCOUNT_NOT_SELF;
        }
        return service.usageReport(email, range).toPlainText();
    }

    @Tool(description = "查询当前用户自己的 API Key 用量明细，按 Key 和模型分组，并给出每个 Key 的总计。"
            + "fromDate/toDate 可选，格式 yyyy-MM-dd；不传默认今天 00:00 到当前，自选范围最多 90 天。"
            + "keyIndexes 为空时先列出自己的 Key（只显示序号）；选择时传序号，支持逗号分隔，最多 3 个。"
            + "account 可传用户消息里写出的邮箱或 QQ 号，服务端会校验是否属于当前发送者。")
    public String myKeyUsage(
            @ToolParam(required = false, description = "起始日期 yyyy-MM-dd") String fromDate,
            @ToolParam(required = false, description = "结束日期 yyyy-MM-dd") String toDate,
            @ToolParam(required = false, description = "Key 序号，逗号分隔，最多 3 个") String keyIndexes,
            @ToolParam(required = false, description = "用户消息里写出的邮箱或 QQ 号；不传表示本人") String account) {
        if (!isSelfOrUnspecified(account)) {
            return ACCOUNT_NOT_SELF;
        }
        return keyUsageService.selfReport(email, fromDate, toDate, keyIndexes).toPlainText();
    }

    @Tool(description = "查询当前用户自己的 API Key 列表：名称、打码密钥、状态、额度与最近使用时间。"
            + "account 可传用户消息里写出的邮箱或 QQ 号，服务端会校验是否属于当前发送者；"
            + "与本人一致就正常查询，不要预先拒绝。")
    public String myApiKeys(
            @ToolParam(required = false, description = "用户消息里写出的邮箱或 QQ 号；不传表示本人") String account) {
        if (!isSelfOrUnspecified(account)) {
            return ACCOUNT_NOT_SELF;
        }
        return service.apiKeysReport(email).toPlainText();
    }

    private boolean isSelfOrUnspecified(String account) {
        return account == null || account.isBlank()
                || Emails.matchesOwner(account, email, qq);
    }
}
