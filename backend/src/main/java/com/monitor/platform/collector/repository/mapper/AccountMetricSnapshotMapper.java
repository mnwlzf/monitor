package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号指标快照 Mapper。
 */
public interface AccountMetricSnapshotMapper  {

    /** 新增指标快照。 */
    int insertSnapshot(AccountMetricSnapshotEntity entity);

    /** 查询账号最新指标快照。 */
    AccountMetricSnapshotEntity selectLatestSnapshot(@Param("accountId") Integer accountId);

    /** 查询账号在时间区间内的指标快照。 */
    List<AccountMetricSnapshotEntity> selectSnapshotsByAccountRange(
            @Param("accountId") Integer accountId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);
}