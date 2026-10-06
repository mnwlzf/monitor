package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import com.monitor.platform.mail.DailyBalanceReportService;
import org.springframework.stereotype.Component;

/**
 * 每日余额消耗报表任务处理器。
 *
 * <p>每天凌晨发送前一天各平台、各账号的余额消耗报表，收件人沿用页面维护的
 * 余额提醒收件人。</p>
 */
@Component
public class DailyBalanceReportTaskHandler implements ScheduledTaskHandler {

    private final DailyBalanceReportService dailyBalanceReportService;

    public DailyBalanceReportTaskHandler(DailyBalanceReportService dailyBalanceReportService) {
        this.dailyBalanceReportService = dailyBalanceReportService;
    }

    @Override
    public String code() {
        return "daily-balance-report";
    }

    @Override
    public String name() {
        return "每日余额消耗报表";
    }

    @Override
    public String description() {
        return "每天凌晨发送前一天各平台、各账号的余额消耗报表";
    }

    @Override
    public void execute() {
        dailyBalanceReportService.sendDailyReport();
    }
}