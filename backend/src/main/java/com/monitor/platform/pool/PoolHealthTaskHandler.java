package com.monitor.platform.pool;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 号池健康度同步任务处理器。
 *
 * <p>同步自建 Sub2API 号池账号列表与健康状态（限流、报错、临时不可调度），
 * 并顺带按密钥哈希自动绑定本地账号。</p>
 */
@Component
public class PoolHealthTaskHandler implements ScheduledTaskHandler {

    private final PoolSyncService poolSyncService;

    public PoolHealthTaskHandler(PoolSyncService poolSyncService) {
        this.poolSyncService = poolSyncService;
    }

    @Override
    public String code() {
        return "pool-health";
    }

    @Override
    public String name() {
        return "号池健康度同步";
    }

    @Override
    public String description() {
        return "同步自建 Sub2API 号池账号列表与健康状态（限流/报错/不可调度）";
    }

    @Override
    public void execute() {
        poolSyncService.syncAllHealth();
    }
}