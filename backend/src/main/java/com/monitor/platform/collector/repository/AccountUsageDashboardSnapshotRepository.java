package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.mapper.AccountUsageDashboardSnapshotMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账号用量看板快照持久化仓储。
 */
@Repository
public class AccountUsageDashboardSnapshotRepository {

    private static final String EMPTY_OBJECT = "{}";
    private static final String EMPTY_ARRAY = "[]";

    private final AccountUsageDashboardSnapshotMapper snapshotMapper;

    public AccountUsageDashboardSnapshotRepository(AccountUsageDashboardSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 保存用量看板快照，并为必填 JSON 字段设置空值兜底。
     */
    public AccountUsageDashboardSnapshotEntity save(AccountUsageDashboardSnapshotEntity entity) {
        if (entity.getCollectedAt() == null) {
            entity.setCollectedAt(OffsetDateTime.now());
        }
        // 表定义为 NOT NULL，上游未提供时兜底，避免采集因空值整体失败。
        if (entity.getMetrics() == null) {
            entity.setMetrics(EMPTY_OBJECT);
        }
        if (entity.getPlatformStats() == null) {
            entity.setPlatformStats(EMPTY_ARRAY);
        }
        if (entity.getRawData() == null) {
            entity.setRawData(EMPTY_OBJECT);
        }
        snapshotMapper.insertSnapshot(entity);
        return entity;
    }

    /**
     * 查询账号最新一条用量看板快照。
     */
    public Optional<AccountUsageDashboardSnapshotEntity> findLatest(Integer accountId) {
        return Optional.ofNullable(snapshotMapper.selectLatestSnapshot(accountId));
    }

    /**
     * 批量查询多个账号各自的最新快照，避免逐账号查库。
     */
    public List<AccountUsageDashboardSnapshotEntity> findLatestByAccounts(List<Integer> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return snapshotMapper.selectLatestSnapshotsByAccounts(accountIds);
    }

    /**
     * 查询账号在时间区间内的用量快照，并限制最大返回数量。
     */
    public List<AccountUsageDashboardSnapshotEntity> findByAccount(Integer accountId,
                                                                   OffsetDateTime from,
                                                                   OffsetDateTime to,
                                                                   int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10000));
        return snapshotMapper.selectSnapshotsByAccountRange(accountId, from, to, safeLimit);
    }

    /**
     * 批量查询账号在时间区间内的快照，按账号与采集时间升序返回。
     *
     * <p>用于按天计算累计指标差值（如 New API 今日消耗）。</p>
     */
    public List<AccountUsageDashboardSnapshotEntity> findByAccounts(List<Integer> accountIds,
                                                                    OffsetDateTime from,
                                                                    OffsetDateTime to) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return snapshotMapper.selectSnapshotsByAccountsRange(accountIds, from, to);
    }
}