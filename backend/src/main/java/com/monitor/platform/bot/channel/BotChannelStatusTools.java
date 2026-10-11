package com.monitor.platform.bot.channel;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 渠道状态工具：<strong>按请求构造</strong>，绑定到「当前看这个的人」。
 *
 * <p>与 {@code BotUserTools} 同样的用意：可见范围由服务端在构造时注入
 * （{@code admin} 与 {@code viewerUserId}），工具方法本身不带任何「以谁的身份看」的参数，
 * 所以大模型无论怎么问，都不可能越权看到别的用户的渠道状态。</p>
 */
public class BotChannelStatusTools {

    private final BotChannelStatusService service;
    private final boolean admin;
    private final Long viewerUserId;

    public BotChannelStatusTools(BotChannelStatusService service, boolean admin, Long viewerUserId) {
        this.service = service;
        this.admin = admin;
        this.viewerUserId = viewerUserId;
    }

    @Tool(description = "查询渠道状态：按「平台 / 分组」聚合的请求数、成功率、首 Token 耗时与缓存率，"
            + "用来回答「现在服务正常吗」「哪些分组有问题」。"
            + "range 取值 90m / 1h / 6h / 12h / 24h / 7d / 30d / 90d，默认 1h。"
            + "普通用户只会看到自己有权限的分组。")
    public String channelStatus(String range) {
        return service.report(admin, viewerUserId, range).toPlainText();
    }
}
