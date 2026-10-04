package com.monitor.platform.collector.application;

import com.monitor.platform.scheduler.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 全量账号采集任务处理器。
 *
 * <p>页面创建任务时选择 {@code collect-all-accounts}，调度管理器会将任务路由到该处理器。</p>
 */
@Component
public class CollectAllAccountsTaskHandler implements ScheduledTaskHandler {

    private final CollectionService collectionService;

    public CollectAllAccountsTaskHandler(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public String code() {
        return "collect-all-accounts";
    }

    @Override
    public String name() {
        return "全量账号采集";
    }

    @Override
    public String description() {
        return "并发采集所有启用平台下的全部启用账号";
    }

    @Override
    public void execute() {
        collectionService.collectAllAccounts();
    }
}
