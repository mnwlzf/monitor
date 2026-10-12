package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 上游变更事件 Mapper。
 */
public interface UpstreamChangeEventMapper  {

    /** 新增变更事件。 */
    int insertEvent(UpstreamChangeEventEntity entity);

    /** 查询账号最近的变更事件。 */
    List<UpstreamChangeEventEntity> selectRecentEvents(@Param("accountId") Integer accountId,
                                                       @Param("limit") int limit);

    /** 查询指定时间之后、涉及正在使用密钥的变更事件，用于邮件提醒。 */
    List<UpstreamChangeEventEntity> selectInUseKeyEventsSince(@Param("since") java.time.OffsetDateTime since,
                                                             @Param("limit") int limit);

    /** 查询指定时间之后、<b>正在使用的渠道</b>发生的倍率变化，用于实时告警。 */
    List<UpstreamChangeEventEntity> selectInUseGroupRateEventsSince(
            @Param("since") java.time.OffsetDateTime since,
            @Param("limit") int limit);

    /** 查询指定时间窗内的渠道变更；inUse 决定取在用的还是非在用的（每日汇总取后者）。 */
    List<UpstreamChangeEventEntity> selectGroupEventsBetween(
            @Param("from") java.time.OffsetDateTime from,
            @Param("to") java.time.OffsetDateTime to,
            @Param("inUse") boolean inUse,
            @Param("limit") int limit);
}