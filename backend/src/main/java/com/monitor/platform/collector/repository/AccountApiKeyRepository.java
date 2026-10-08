package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.mapper.AccountApiKeyMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 账号 API Key 当前状态持久化仓储。
 */
@Repository
public class AccountApiKeyRepository {

    private final AccountApiKeyMapper apiKeyMapper;

    public AccountApiKeyRepository(AccountApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    /**
     * 新增或更新密钥状态；新增时补齐首次发现和最后可见时间。
     */
    public AccountApiKeyEntity save(AccountApiKeyEntity entity) {
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
            apiKeyMapper.insertKey(entity);
        } else {
            apiKeyMapper.updateKey(entity);
        }
        return entity;
    }

    /** 按账号、平台类型和上游密钥 ID 查询密钥。 */
    public Optional<AccountApiKeyEntity> findByAccountAndExternalId(Integer accountId,
                                                                    String platformType,
                                                                    String externalKeyId) {
        return Optional.ofNullable(apiKeyMapper.selectKeyByAccountAndExternalId(
                accountId, platformType, externalKeyId));
    }

    /** 按主键查询密钥。 */
    public Optional<AccountApiKeyEntity> findById(Long id) {
        return Optional.ofNullable(apiKeyMapper.selectKeyById(id));
    }

    /** 查询账号下的密钥，可选择只返回有效密钥。 */
    public List<AccountApiKeyEntity> findByAccount(Integer accountId, boolean activeOnly) {
        return apiKeyMapper.selectKeysByAccount(accountId, activeOnly);
    }

    /**
     * 按平台与密钥 SHA-256 查询有效的本地密钥主键，供号池账号自动绑定使用。
     */
    public Optional<Long> findActiveKeyIdByHash(Integer platformId, String keyHash) {
        if (keyHash == null || keyHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(apiKeyMapper.selectActiveKeyIdByHash(platformId, keyHash));
    }

    /** 将本轮未出现在上游的密钥批量标记为失效。 */
    public int deactivateMissing(Integer accountId, String platformType,
                                 Collection<String> activeExternalKeyIds) {
        return apiKeyMapper.deactivateMissingKeys(
                accountId, platformType, activeExternalKeyIds, OffsetDateTime.now());
    }
}
