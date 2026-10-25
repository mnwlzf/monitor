package com.monitor.platform.status;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * OpenAI 官方服务状态监控任务。
 */
@Component
public class OpenAiStatusTaskHandler implements ScheduledTaskHandler {

    private final OpenAiStatusService statusService;

    public OpenAiStatusTaskHandler(OpenAiStatusService statusService) {
        this.statusService = statusService;
    }

    @Override
    public String code() {
        return "openai-status";
    }

    @Override
    public String name() {
        return "OpenAI 服务状态监控";
    }

    @Override
    public String description() {
        return "每 10 分钟读取 OpenAI 官方状态页；连续异常只向「指定群（平台功能）」发送一次，恢复后再次异常会重新提醒";
    }

    @Override
    public void execute() {
        statusService.checkAndNotify();
    }
}