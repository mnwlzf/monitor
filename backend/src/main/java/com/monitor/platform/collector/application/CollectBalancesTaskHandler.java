package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
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

    public CollectBalancesTaskHandler(CollectionService collectionService) {
        this.collectionService = collectionService;
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
        collectionService.collectAllAccounts(CollectionScope.BALANCE);
    }
}