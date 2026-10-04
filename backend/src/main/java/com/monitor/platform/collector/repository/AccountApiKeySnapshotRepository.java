package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountApiKeySnapshotEntity;
import com.monitor.platform.collector.repository.mapper.AccountApiKeySnapshotMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * API Key 用量快照持久化仓储。
 */
@Repository
public class AccountApiKeySnapshotRepository {

    private static final String EMPTY_OBJECT = "{}";

    private final AccountApiKeySnapshotMapper snapshotMapper;

    public AccountApiKeySnapshotRepository(AccountApiKeySnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 保存密钥用量快照，并为必填字段设置空值兜底。
     */
    public AccountApiKeySnapshotEntity save(AccountApiKeySnapshotEntity entity) {
        if (entity.getCollectedAt() == null) {
            entity.setCollectedAt(OffsetDateTime.now());
        }
        if (entity.getMetrics() == null) {
            entity.setMetrics(EMPTY_OBJECT);
        }
        if (entity.getRawData() == null) {
            entity.setRawData(EMPTY_OBJECT);
        }
        snapshotMapper.insertSnapshot(entity);
        return entity;
    }
}
