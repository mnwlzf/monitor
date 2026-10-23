package com.monitor.platform.bot.error;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * Sub2API 错误突增告警任务。
 */
@Component
public class BotErrorAlertTaskHandler implements ScheduledTaskHandler {

    private final BotErrorAlertService alertService;

    public BotErrorAlertTaskHandler(BotErrorAlertService alertService) {
        this.alertService = alertService;
    }

    @Override
    public String code() {
        return "bot-error-alert";
    }

    @Override
    public String name() {
        return "Sub2API 错误告警";
    }

    @Override
    public String description() {
        return "每 5 分钟检查错误、429/529 和入口拒绝是否突增，按页面配置推送到群或私聊";
    }

    @Override
    public void execute() {
        alertService.checkAndNotify();
    }
}
