package com.monitor.platform.bot.user;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 用户端工具：<strong>按请求构造</strong>，绑定到「当前发送者」。
 *
 * <p>刻意做成一次性对象而不是单例 Bean：工具方法不带任何「查谁」的参数，
 * 邮箱由服务端在构造时注入。这样大模型无论怎么问，都只能查到发送者本人的数据，
 * 不存在「传别人的 user_id 越权查询」的入口。</p>
 */
public class BotUserTools {

    private final BotUserService service;
    private final String email;

    public BotUserTools(BotUserService service, String email) {
        this.service = service;
        this.email = email;
    }

    @Tool(description = "查询当前用户自己的账户余额、冻结额度、累计充值、账号状态与最近活跃时间。"
            + "只能查发送者本人，不接受任何用户标识参数。")
    public String myBalance() {
        return service.balanceReport(email).toPlainText();
    }

    @Tool(description = "查询当前用户自己的用量：请求数、输入/输出/缓存 Token、实际消耗与平均耗时。"
            + "range 取值 today / week / month，默认 today。只能查发送者本人。")
    public String myUsage(String range) {
        return service.usageReport(email, range).toPlainText();
    }

    @Tool(description = "查询当前用户自己的 API Key 列表：名称、打码密钥、状态、额度与最近使用时间。"
            + "只能查发送者本人。")
    public String myApiKeys() {
        return service.apiKeysReport(email).toPlainText();
    }
}