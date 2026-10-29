package com.monitor.platform.ops;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 管理员自然语言运维入口。
 *
 * <p>只提供只读监控与事故查询工具；不提供任何写操作工具。写操作仍由确定性命令和确认码控制。</p>
 */
@Component
public class OpsMonitoringTools {

    private final OpsMonitoringService monitoringService;
    private final OpsIncidentService incidentService;

    public OpsMonitoringTools(OpsMonitoringService monitoringService,
                              OpsIncidentService incidentService) {
        this.monitoringService = monitoringService;
        this.incidentService = incidentService;
    }

    @Tool(description = "查询智能运维监控总览：渠道异常、账号异常、代理异常、倍率风险、用户异常和 Provider 风险。")
    public String opsMonitoringOverview() {
        return monitoringService.overviewReport().toPlainText();
    }

    @Tool(description = "查询近 1 小时渠道健康：平台、分组、请求数、错误率、首 Token 和缓存率。")
    public String opsChannelHealth() {
        return monitoringService.channelReport().toPlainText();
    }

    @Tool(description = "查询账号健康：不可调度、限流、过载、临时不可调度和采集失败。")
    public String opsAccountHealth() {
        return monitoringService.accountReport().toPlainText();
    }

    @Tool(description = "查询代理健康：过期、即将过期和状态异常。")
    public String opsProxyHealth() {
        return monitoringService.proxyReport().toPlainText();
    }

    @Tool(description = "查询近 24 小时倍率倒挂风险：上游渠道倍率、分组倍率和受影响账号。")
    public String opsRateRisks() {
        return monitoringService.rateRiskReport().toPlainText();
    }

    @Tool(description = "查询近 1 小时高错误用户，用于发现异常消费或异常请求用户。")
    public String opsUserAnomalies() {
        return monitoringService.userAnomalyReport().toPlainText();
    }

    @Tool(description = "查询 Provider/分组归因：按平台汇总请求数、错误率、异常分组数和故障判断。")
    public String opsProviderAttribution() {
        return monitoringService.providerReport().toPlainText();
    }

    @Tool(description = "查询当前智能运维事故列表。")
    public String opsIncidents() {
        return incidentService.incidentListReport(20).toPlainText();
    }

    @Tool(description = "查询指定事故编号的详情、影响范围和时间线。")
    public String opsIncidentDetail(long incidentId) {
        try {
            return incidentService.incidentDetailReport(incidentId).toPlainText();
        } catch (RuntimeException ex) {
            return "查询事故失败：" + ex.getMessage();
        }
    }

    @Tool(description = "查询最近自愈计划和状态。只查询，不执行计划。")
    public String opsHealingPlans() {
        return incidentService.planListReport(20).toPlainText();
    }
}
