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

    public List<UpstreamChangeEventEntity> findRecent(Integer accountId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventMapper.selectRecentEvents(accountId, safeLimit);
    }
}