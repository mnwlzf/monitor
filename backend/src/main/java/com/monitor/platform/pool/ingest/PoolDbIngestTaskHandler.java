package com.monitor.platform.pool.ingest;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 直连库增量采集任务处理器（默认每 30 秒一轮）。
 *
 * <p>未配置直连只读库时每轮直接返回，不影响其它任务。</p>
 */
@Component
public class PoolDbIngestTaskHandler implements ScheduledTaskHandler {

    private final PoolDbIngestService poolDbIngestService;

    public PoolDbIngestTaskHandler(PoolDbIngestService poolDbIngestService) {
        this.poolDbIngestService = poolDbIngestService;
    }

    @Override
    public String code() {
        return "pool-samples-db";
    }

    @Override
    public String name() {
        return "号池明细增量（直连库）";
    }

    @Override
    public String description() {
        return "直连自建 Sub2API 数据库，按 usage_logs.id 游标增量同步号池明细（秒级延迟）";
    }

    @Override
    public void execute() {
        poolDbIngestService.ingestOnce();
    }
}