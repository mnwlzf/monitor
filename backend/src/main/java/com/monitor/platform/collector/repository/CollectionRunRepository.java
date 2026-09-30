package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.mapper.CollectionRunMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 采集批次持久化仓储。
 */
@Repository
public class CollectionRunRepository {

    private final CollectionRunMapper collectionRunMapper;

    public CollectionRunRepository(CollectionRunMapper collectionRunMapper) {
        this.collectionRunMapper = collectionRunMapper;
    }

    public CollectionRunEntity start(Integer accountId, String platformType, String metadata) {
        CollectionRunEntity entity = new CollectionRunEntity();
        entity.setAccountId(accountId);
        entity.setPlatformType(platformType);
        entity.setStatus("RUNNING");
        entity.setStartedAt(OffsetDateTime.now());
        entity.setMetadata(metadata == null ? "{}" : metadata);
        collectionRunMapper.insertRun(entity);
        return entity;
    }

    public Optional<CollectionRunEntity> findById(Long id) {
        return Optional.ofNullable(collectionRunMapper.selectRunById(id));
    }

    public void finish(Long id, String status, OffsetDateTime finishedAt,
                       Long durationMs, String errorCode, String errorMessage) {
        collectionRunMapper.updateRunFinish(id, status, finishedAt, durationMs, errorCode, errorMessage);
    }

    public List<CollectionRunEntity> findRecent(Integer accountId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return collectionRunMapper.selectRecentRuns(accountId, safeLimit);
    }
}