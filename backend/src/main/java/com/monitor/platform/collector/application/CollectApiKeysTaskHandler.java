package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * API Key 采集任务处理器。
 *
 * <p>New API 走 {@code /api/token/}（分页）以及每个 Key 的明文接口；
 * Sub2API 走 {@code /api/v1/keys} + {@code /api/v1/usage/dashboard/api-keys-usage}。
 * 该任务请求量最大，建议用较低频率。</p>
 */
@Component
public class CollectApiKeysTaskHandler implements ScheduledTaskHandler {

    private final CollectionService collectionService;

    public CollectApiKeysTaskHandler(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public String code() {
        return "collect-api-keys";
    }

    @Override
    public String name() {
        return "API Key 采集";
    }

    @Override
    public String description() {
        return "并发采集所有启用账号的 API Key 与用量";
    }

    @Override
    public void execute() {
        collectionService.collectAllAccounts(CollectionScope.API_KEYS);
    }
}