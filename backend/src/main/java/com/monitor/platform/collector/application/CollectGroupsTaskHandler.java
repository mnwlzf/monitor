package com.monitor.platform.collector.application;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import com.monitor.platform.mail.ChannelChangeAlertService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 分组·渠道倍率采集任务处理器。
 *
 * <p>New API 走 {@code /api/user/self/groups}，Sub2API 走 {@code /api/v1/groups/available}，
 * 写入分组、倍率、状态与变更记录。</p>
 */
@Component
public class CollectGroupsTaskHandler implements ScheduledTaskHandler {

    private final CollectionService collectionService;
    private final ChannelChangeAlertService channelChangeAlertService;

    public CollectGroupsTaskHandler(CollectionService collectionService,
                                    ChannelChangeAlertService channelChangeAlertService) {
        this.collectionService = collectionService;
        this.channelChangeAlertService = channelChangeAlertService;
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
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            collectionService.collectAllAccounts(CollectionScope.GROUPS);
        } finally {
            // 采集完成（含部分失败）后，立刻对「正在使用渠道的倍率变化」发实时告警 ——
            // 涨价直接关系成本，不能等到人主动去看
            channelChangeAlertService.notifyInUseRateChanges(startedAt);
        }
    }
}