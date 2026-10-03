package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 上游渠道/分组 Mapper。
 */
public interface UpstreamGroupMapper  {

    /** 新增渠道。 */
    int insertGroup(UpstreamGroupEntity entity);

    /** 更新渠道。 */
    int updateGroup(UpstreamGroupEntity entity);

    /** 按账号、平台类型和上游渠道 ID 查询渠道。 */
    UpstreamGroupEntity selectGroupByAccountAndExternalId(
            @Param("accountId") Integer accountId,
            @Param("platformType") String platformType,
            @Param("externalGroupId") String externalGroupId);

    /** 查询账号下的渠道，可选择只返回有效渠道。 */
    List<UpstreamGroupEntity> selectGroupsByAccount(@Param("accountId") Integer accountId,
                                                    @Param("activeOnly") boolean activeOnly);

    /** 将未出现在本轮采集结果中的渠道标记为下线。 */
    int deactivateMissingGroups(@Param("accountId") Integer accountId,
                                @Param("platformType") String platformType,
                                @Param("externalGroupIds") Collection<String> externalGroupIds,
                                @Param("changedAt") OffsetDateTime changedAt);
}