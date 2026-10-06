package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import com.monitor.platform.mail.BalanceAlertService;
import org.springframework.stereotype.Component;

/**
 * 余额采集任务处理器。
 *
 * <p>New API 走 {@code /api/user/self}（余额与用量看板同源，一次请求写两张快照）；
 * Sub2API 走 {@code /api/v1/auth/me} + {@code /api/v1/usage/dashboard/stats}。
 * 该任务是唯一维护账号「最近采集时间 / 状态 / 连续失败次数」的任务。</p>
 */
@Component
public class CollectBalancesTaskHandler implements ScheduledTaskHandler {

    private final CollectionService collectionService;
    private final BalanceAlertService balanceAlertService;

    public CollectBalancesTaskHandler(CollectionService collectionService,
                                      BalanceAlertService balanceAlertService) {
        this.collectionService = collectionService;
        this.balanceAlertService = balanceAlertService;
    }

    @Override
    public String code() {
        return "collect-balances";
    }

    @Override
    public String name() {
        return "余额采集";
    }

    @Override
    public String description() {
        return "并发采集所有启用账号的余额、额度与用量看板";
    }

    @Override
    public void execute() {
        try {
            collectionService.collectAllAccounts(CollectionScope.BALANCE);
        } finally {
            // 采集完成（含部分失败）后检查平台余额，按需发送提醒邮件
            balanceAlertService.checkAndNotify();
        }
    }
}