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

    /**
     * 保存账号指标快照；未指定采集时间时使用当前时间。
     */
    public AccountMetricSnapshotEntity save(AccountMetricSnapshotEntity entity) {
        if (entity.getCollectedAt() == null) {
            entity.setCollectedAt(OffsetDateTime.now());
        }
        snapshotMapper.insertSnapshot(entity);
        return entity;
    }

    /**
     * 查询账号最新一条指标快照。
     */
    public Optional<AccountMetricSnapshotEntity> findLatest(Integer accountId) {
        return Optional.ofNullable(snapshotMapper.selectLatestSnapshot(accountId));
    }

    /**
     * 查询账号在时间区间内的指标快照，并限制最大返回数量。
     */
    public List<AccountMetricSnapshotEntity> findByAccount(Integer accountId,
                                                           OffsetDateTime from,
                                                           OffsetDateTime to,
                                                           int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10000));
        return snapshotMapper.selectSnapshotsByAccountRange(accountId, from, to, safeLimit);
    }
}