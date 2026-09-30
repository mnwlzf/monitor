package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.repository.mapper.UpstreamGroupMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 上游渠道/分组当前状态持久化仓储。
 */
@Repository
public class UpstreamGroupRepository {

    private final UpstreamGroupMapper groupMapper;

    public UpstreamGroupRepository(UpstreamGroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    public UpstreamGroupEntity save(UpstreamGroupEntity entity) {
        if (entity.getId() == null) {
            OffsetDateTime now = OffsetDateTime.now();
            if (entity.getFirstSeenAt() == null) {
                entity.setFirstSeenAt(now);
            }
            if (entity.getLastSeenAt() == null) {
                entity.setLastSeenAt(now);
            }
            if (entity.getIsActive() == null) {
                entity.setIsActive(true);
            }
            groupMapper.insertGroup(entity);
        } else {
            groupMapper.updateGroup(entity);
        }
        return entity;
    }

    public Optional<UpstreamGroupEntity> findByAccountAndExternalId(Integer accountId,
                                                                    String platformType,
                                                                    String externalGroupId) {
        return Optional.ofNullable(groupMapper.selectGroupByAccountAndExternalId(
                accountId, platformType, externalGroupId));
    }

    public List<UpstreamGroupEntity> findByAccount(Integer accountId, boolean activeOnly) {
        return groupMapper.selectGroupsByAccount(accountId, activeOnly);
    }

    public int deactivateMissing(Integer accountId, String platformType,
                                 Collection<String> activeExternalGroupIds) {
        return groupMapper.deactivateMissingGroups(
                accountId, platformType, activeExternalGroupIds, OffsetDateTime.now());
    }
}