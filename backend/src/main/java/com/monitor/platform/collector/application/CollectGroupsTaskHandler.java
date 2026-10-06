package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 分组·渠道倍率采集任务处理器。
 *
 * <p>New API 走 {@code /api/user/self/groups}，Sub2API 走 {@code /api/v1/groups/available}，
 * 写入分组、倍率、状态与变更记录。</p>
 */
@Component
public class CollectGroupsTaskHandler implements ScheduledTaskHandler {

    private final CollectionService collectionService;

    public CollectGroupsTaskHandler(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public String code() {
        return "collect-groups";
    }

    @Override
    public String name() {
        return "分组·渠道倍率采集";
    }

    @Override
    public String description() {
        return "并发采集所有启用账号的分组与渠道倍率";
    }

    @Override
    public void execute() {
        collectionService.collectAllAccounts(CollectionScope.GROUPS);
    }
}