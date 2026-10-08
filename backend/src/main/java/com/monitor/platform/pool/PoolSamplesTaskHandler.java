package com.monitor.platform.pool;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 号池明细采集任务处理器。
 *
 * <p>增量拉取号池逐请求明细，用于本地计算缓存命中率、首 token 耗时等指标。</p>
 */
@Component
public class PoolSamplesTaskHandler implements ScheduledTaskHandler {

    private final PoolSyncService poolSyncService;

    public PoolSamplesTaskHandler(PoolSyncService poolSyncService) {
        this.poolSyncService = poolSyncService;
    }

    @Override
    public String code() {
        return "pool-samples";
    }

    @Override
    public String name() {
        return "号池明细采集";
    }

    @Override
    public String description() {
        return "增量采集号池逐请求明细（缓存命中率、首 token 耗时等）";
    }

    @Override
    public void execute() {
        poolSyncService.syncAllSamples();
    }
}