package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号指标快照 Mapper。
 */
public interface AccountMetricSnapshotMapper  {

    int insertSnapshot(AccountMetricSnapshotEntity entity);

    AccountMetricSnapshotEntity selectLatestSnapshot(@Param("accountId") Integer accountId);

    List<AccountMetricSnapshotEntity> selectSnapshotsByAccountRange(
            @Param("accountId") Integer accountId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);
}