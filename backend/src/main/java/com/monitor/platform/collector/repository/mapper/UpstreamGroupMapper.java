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

    int insertGroup(UpstreamGroupEntity entity);

    int updateGroup(UpstreamGroupEntity entity);

    UpstreamGroupEntity selectGroupByAccountAndExternalId(
            @Param("accountId") Integer accountId,
            @Param("platformType") String platformType,
            @Param("externalGroupId") String externalGroupId);

    List<UpstreamGroupEntity> selectGroupsByAccount(@Param("accountId") Integer accountId,
                                                    @Param("activeOnly") boolean activeOnly);

    int deactivateMissingGroups(@Param("accountId") Integer accountId,
                                @Param("platformType") String platformType,
                                @Param("externalGroupIds") Collection<String> externalGroupIds,
                                @Param("changedAt") OffsetDateTime changedAt);
}