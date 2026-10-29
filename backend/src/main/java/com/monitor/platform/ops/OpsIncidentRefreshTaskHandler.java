package com.monitor.platform.ops;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/** 智能运维事故巡检定时任务。 */
@Component
public class OpsIncidentRefreshTaskHandler implements ScheduledTaskHandler {

    private final OpsIncidentService incidentService;

    public OpsIncidentRefreshTaskHandler(OpsIncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @Override
    public String code() {
        return "ops-incident-refresh";
    }

    @Override
    public String name() {
        return "智能运维事故巡检";
    }

    @Override
    public String description() {
        return "每 5 分钟从 Sub2API 错误指纹生成/更新事故，自动关闭已恢复事故";
    }

    @Override
    public void execute() {
        incidentService.refreshIncidents();
    }
}