package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.mapper.AccountMetricSnapshotMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账号指标快照持久化仓储。
 */
@Repository
public class AccountMetricSnapshotRepository {

    private final AccountMetricSnapshotMapper snapshotMapper;

    public AccountMetricSnapshotRepository(AccountMetricSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    public AccountMetricSnapshotEntity save(AccountMetricSnapshotEntity entity) {
        if (entity.getCollectedAt() == null) {
            entity.setCollectedAt(OffsetDateTime.now());
        }
        snapshotMapper.insertSnapshot(entity);
        return entity;
    }

    public Optional<AccountMetricSnapshotEntity> findLatest(Integer accountId) {
        return Optional.ofNullable(snapshotMapper.selectLatestSnapshot(accountId));
    }

    public List<AccountMetricSnapshotEntity> findByAccount(Integer accountId,
                                                           OffsetDateTime from,
                                                           OffsetDateTime to,
                                                           int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10000));
        return snapshotMapper.selectSnapshotsByAccountRange(accountId, from, to, safeLimit);
    }
}