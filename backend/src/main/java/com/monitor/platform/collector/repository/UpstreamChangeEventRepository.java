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
}