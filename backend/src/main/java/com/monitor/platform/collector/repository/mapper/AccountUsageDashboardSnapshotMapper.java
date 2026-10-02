package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号用量看板快照 Mapper。
 */
public interface AccountUsageDashboardSnapshotMapper {

    int insertSnapshot(AccountUsageDashboardSnapshotEntity entity);

    AccountUsageDashboardSnapshotEntity selectLatestSnapshot(@Param("accountId") Integer accountId);

    List<AccountUsageDashboardSnapshotEntity> selectLatestSnapshotsByAccounts(
            @Param("accountIds") List<Integer> accountIds);

    List<AccountUsageDashboardSnapshotEntity> selectSnapshotsByAccountRange(
            @Param("accountId") Integer accountId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);
}