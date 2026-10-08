package com.monitor.platform.pool.repository;

import com.monitor.platform.pool.PoolAccountEntity;
import com.monitor.platform.pool.repository.mapper.PoolAccountMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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

    /** 记录号池账号最近一次采集错误。 */
    public void updateSyncError(Integer platformId, Long externalAccountId, String error) {
        poolAccountMapper.updateSyncError(platformId, externalAccountId, error);
    }

    /** 查询存在号池账号的平台 ID。 */
    public List<Integer> findDistinctPlatformIds() {
        return poolAccountMapper.selectDistinctPlatformIds();
    }
}