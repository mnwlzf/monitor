package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTask;
import com.monitor.platform.common.schedule.ScheduledTaskDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 到期账号采集定时任务。
 *
 * <p>本类只负责将既有采集入口接入统一定时任务框架；采集平台遍历、到期账号查询、
 * 适配器调用和落库等具体逻辑仍完全由 {@link CollectionService} 负责。</p>
 */
@Component
@ScheduledTaskDefinition(name = "collect-due-accounts", cron = "0 * * * * ?")
public class CollectAccountsTask implements ScheduledTask {

    private final CollectionService collectionService;

    /** 每个平台单轮最多采集的账号数量。 */
    private final int batchSize;

    public CollectAccountsTask(CollectionService collectionService,
                               @Value("${monitor.collection.batch-size:100}") int batchSize) {
        this.collectionService = collectionService;
        this.batchSize = batchSize;
    }

    @Override
    public void execute() {
        collectionService.collectDueAccounts(batchSize);
    }
}
