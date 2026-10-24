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

    /**
     * 新增或更新渠道状态；新增时补齐首次发现和最后可见时间。
     */
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
            if (entity.getId() == null) {
                // ON CONFLICT 命中已有行时 JDBC 未必回填主键，再按唯一身份取一次。
                UpstreamGroupEntity persisted = groupMapper.selectGroupByAccountAndExternalId(
                        entity.getAccountId(), entity.getPlatformType(), entity.getExternalGroupId());
                if (persisted == null) {
                    throw new IllegalStateException("分组 upsert 后无法读取记录: accountId="
                            + entity.getAccountId() + ", platformType=" + entity.getPlatformType()
                            + ", externalGroupId=" + entity.getExternalGroupId());
                }
                entity.setId(persisted.getId());
                // 冲突时保留数据库里的首次发现时间，不能被本轮临时对象覆盖。
                entity.setFirstSeenAt(persisted.getFirstSeenAt());
            }
        } else {
            groupMapper.updateGroup(entity);
        }
        return entity;
    }

    /**
     * 按账号、平台类型和上游渠道 ID 查询渠道。
     */
    public Optional<UpstreamGroupEntity> findByAccountAndExternalId(Integer accountId,
                                                                    String platformType,
                                                                    String externalGroupId) {
        return Optional.ofNullable(groupMapper.selectGroupByAccountAndExternalId(
                accountId, platformType, externalGroupId));
    }

    /**
     * 查询账号下的渠道，可选择只返回有效渠道。
     */
    public List<UpstreamGroupEntity> findByAccount(Integer accountId, boolean activeOnly) {
        return groupMapper.selectGroupsByAccount(accountId, activeOnly);
    }

    /**
     * 查询账号指定平台类型下的渠道，可选择只返回有效渠道。
     */
    public List<UpstreamGroupEntity> findByAccountAndPlatformType(Integer accountId,
                                                                  String platformType,
                                                                  boolean activeOnly) {
        return groupMapper.selectGroupsByAccountAndPlatformType(accountId, platformType, activeOnly);
    }

    /**
     * 将本轮未出现在上游的渠道批量标记为下线。
     *
     * @return 被标记下线的渠道数量
     */
    public int deactivateMissing(Integer accountId, String platformType,
                                 Collection<String> activeExternalGroupIds) {
        return groupMapper.deactivateMissingGroups(
                accountId, platformType, activeExternalGroupIds, OffsetDateTime.now());
    }
}