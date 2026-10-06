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

    /**
     * 创建 RUNNING 状态的采集批次（默认全量）。
     */
    public CollectionRunEntity start(Integer accountId, String platformType, String metadata) {
        return start(accountId, platformType, "FULL", metadata);
    }

    /**
     * 创建 RUNNING 状态的采集批次，并记录采集范围。
     *
     * @param scope 采集范围：FULL、BALANCE、GROUPS、API_KEYS
     */
    public CollectionRunEntity start(Integer accountId, String platformType, String scope, String metadata) {
        CollectionRunEntity entity = new CollectionRunEntity();
        entity.setAccountId(accountId);
        entity.setPlatformType(platformType);
        entity.setScope(scope == null ? "FULL" : scope);
        entity.setStatus("RUNNING");
        entity.setStartedAt(OffsetDateTime.now());
        entity.setMetadata(metadata == null ? "{}" : metadata);
        collectionRunMapper.insertRun(entity);
        return entity;
    }

    /**
     * 按主键查询采集批次。
     */
    public Optional<CollectionRunEntity> findById(Long id) {
        return Optional.ofNullable(collectionRunMapper.selectRunById(id));
    }

    /**
     * 写入采集批次的结束状态、耗时和错误信息。
     */
    public void finish(Long id, String status, OffsetDateTime finishedAt,
                       Long durationMs, String errorCode, String errorMessage) {
        collectionRunMapper.updateRunFinish(id, status, finishedAt, durationMs, errorCode, errorMessage);
    }

    /**
     * 查询账号最近若干次采集批次。
     */
    public List<CollectionRunEntity> findRecent(Integer accountId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return collectionRunMapper.selectRecentRuns(accountId, safeLimit);
    }
}