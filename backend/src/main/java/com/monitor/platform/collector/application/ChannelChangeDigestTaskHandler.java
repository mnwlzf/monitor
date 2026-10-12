package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import com.monitor.platform.mail.ChannelChangeAlertService;
import org.springframework.stereotype.Component;

/**
 * 渠道变更每日汇总任务处理器。
 *
 * <p>只汇总<strong>非在用</strong>渠道的变更 —— 在用渠道的倍率变化走实时告警
 * （见 {@link CollectGroupsTaskHandler}），两类分开才不会互相淹没。</p>
 *
 * <p>建议每天上午跑一次；收件人在「系统设置 → 邮件通知」里单独配。</p>
 */
@Component
public class ChannelChangeDigestTaskHandler implements ScheduledTaskHandler {

    private final ChannelChangeAlertService channelChangeAlertService;

    public ChannelChangeDigestTaskHandler(ChannelChangeAlertService channelChangeAlertService) {
        this.channelChangeAlertService = channelChangeAlertService;
    }

    @Override
    public String code() {
        return "channel-change-digest";
    }

    @Override
    public String name() {
        return "渠道变更每日汇总";
    }

    @Override
    public String description() {
        return "汇总最近 24 小时非在用渠道的变更，正文由大模型总结后发邮件";
    }

    @Override
    public void execute() {
        channelChangeAlertService.sendDailyDigest();
    }
}
