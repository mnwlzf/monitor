package com.monitor.platform.pool.repository;

import com.monitor.platform.pool.PoolAccountEntity;
import com.monitor.platform.pool.repository.mapper.PoolAccountMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 号池账号持久化仓储。
 */
@Repository
public class PoolAccountRepository {

    private final PoolAccountMapper poolAccountMapper;

    public PoolAccountRepository(PoolAccountMapper poolAccountMapper) {
        this.poolAccountMapper = poolAccountMapper;
    }

    /** 新增或更新号池账号（按平台 + 外部账号 ID 幂等）。 */
    public PoolAccountEntity upsert(PoolAccountEntity entity) {
        poolAccountMapper.upsertAccount(entity);
        return entity;
    }

    /** 查询指定平台下的全部号池账号。 */
    public List<PoolAccountEntity> findByPlatform(Integer platformId) {
        return poolAccountMapper.selectByPlatform(platformId);
    }

    /** 按平台与外部账号 ID 查询号池账号。 */
    public Optional<PoolAccountEntity> findByPlatformAndExternalId(Integer platformId, Long externalAccountId) {
        return Optional.ofNullable(poolAccountMapper.selectByPlatformAndExternalId(platformId, externalAccountId));
    }

    /** 更新号池账号与本地密钥的绑定关系。 */
    public void updateBinding(Integer platformId, Long externalAccountId, Long boundKeyId) {
        poolAccountMapper.updateBinding(platformId, externalAccountId, boundKeyId);
    }

    /** 更新号池账号的明细采集水位。 */
    public void updateSampleWatermark(Integer platformId, Long externalAccountId, OffsetDateTime lastSampleAt) {
        poolAccountMapper.updateSampleWatermark(platformId, externalAccountId, lastSampleAt);
    }

    /** 设置/清除「往回补齐」游标。 */
    public void updateBackfillUntil(Integer platformId, Long externalAccountId, OffsetDateTime backfillUntil) {
        poolAccountMapper.updateBackfillUntil(platformId, externalAccountId, backfillUntil);
    }

    /** 记录号池账号最近一次采集错误。 */
    public void updateSyncError(Integer platformId, Long externalAccountId, String error) {
        poolAccountMapper.updateSyncError(platformId, externalAccountId, error);
    }

    /**
     * 按外部账号 ID 批量删除号池账号。
     *
     * @return 实际删除行数
     */
    public int deleteByExternalIds(Integer platformId, Collection<Long> externalAccountIds) {
        if (externalAccountIds == null || externalAccountIds.isEmpty()) {
            return 0;
        }
        return poolAccountMapper.deleteByExternalIds(platformId, externalAccountIds);
    }

    /** 查询存在号池账号的平台 ID。 */
    public List<Integer> findDistinctPlatformIds() {
        return poolAccountMapper.selectDistinctPlatformIds();
    }

    /**
     * 这批本地上游 Key 里，哪些正被号池账号绑定。
     *
     * <p>「我自己的平台在用它」的判定依据就是这条绑定关系 —— 密钥变更的
     * 「使用中」口径用它，而不是「上游把它标成 active」（那只是「活跃」）。</p>
     */
    public Set<Long> findBoundKeyIds(Collection<Long> keyIds) {
        if (keyIds == null || keyIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = keyIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Set.of() : new LinkedHashSet<>(poolAccountMapper.selectBoundKeyIds(ids));
    }
}