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

    /**
     * 批量查询多个账号在时间区间内的快照，按账号与采集时间升序返回。
     *
     * <p>New API 的「今日消耗」由当天首次与最新一次累计消耗的差值推算，批量查询避免按账号循环查库。</p>
     */
    List<AccountUsageDashboardSnapshotEntity> selectSnapshotsByAccountsRange(
            @Param("accountIds") List<Integer> accountIds,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}