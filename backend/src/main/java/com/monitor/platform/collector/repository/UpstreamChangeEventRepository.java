package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.mapper.UpstreamChangeEventMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 上游变更事件持久化仓储。
 */
@Repository
public class UpstreamChangeEventRepository {

    private final UpstreamChangeEventMapper eventMapper;

    public UpstreamChangeEventRepository(UpstreamChangeEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /**
     * 保存变更事件，并补齐发现时间、级别和元数据默认值。
     */
    public UpstreamChangeEventEntity save(UpstreamChangeEventEntity entity) {
        if (entity.getDetectedAt() == null) {
            entity.setDetectedAt(OffsetDateTime.now());
        }
        if (entity.getSeverity() == null) {
            entity.setSeverity("INFO");
        }
        if (entity.getMetadata() == null) {
            entity.setMetadata("{}");
        }
        if (entity.getInUse() == null) {
            entity.setInUse(false);
        }
        eventMapper.insertEvent(entity);
        return entity;
    }

    /**
     * 查询账号最近若干条变更事件。
     */
    public List<UpstreamChangeEventEntity> findRecent(Integer accountId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventMapper.selectRecentEvents(accountId, safeLimit);
    }

    /**
     * 查询指定时间之后、涉及正在使用密钥的变更事件。
     *
     * <p>供密钥变更邮件提醒使用：只返回 in_use 标记为真的 API_KEY 事件，
     * 按发现时间倒序，最多返回 limit 条。</p>
     */
    public List<UpstreamChangeEventEntity> findInUseKeyEventsSince(OffsetDateTime since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventMapper.selectInUseKeyEventsSince(since, safeLimit);
    }

    /**
     * 查询指定时间之后、<b>正在使用的渠道</b>发生的倍率变化。
     *
     * <p>供渠道实时告警使用：只返回 in_use 为真的 GROUP 倍率类事件。</p>
     */
    public List<UpstreamChangeEventEntity> findInUseGroupRateEventsSince(OffsetDateTime since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventMapper.selectInUseGroupRateEventsSince(since, safeLimit);
    }

    /**
     * 查询指定时间窗内的渠道变更，可按「是否在用」筛选。
     *
     * @param inUse true 取在用的、false 取非在用的（每日汇总用它取后者）
     */
    public List<UpstreamChangeEventEntity> findGroupEventsBetween(OffsetDateTime from, OffsetDateTime to,
                                                                   boolean inUse, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventMapper.selectGroupEventsBetween(from, to, inUse, safeLimit);
    }
}