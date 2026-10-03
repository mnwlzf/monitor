package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import com.monitor.platform.collector.repository.mapper.AccountCredentialMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 账号凭证持久化仓储。
 */
@Repository
public class AccountCredentialRepository {

    private final AccountCredentialMapper credentialMapper;

    public AccountCredentialRepository(AccountCredentialMapper credentialMapper) {
        this.credentialMapper = credentialMapper;
    }

    /**
     * 新增或更新凭证记录，并维护更新时间。
     */
    public AccountCredentialEntity save(AccountCredentialEntity entity) {
        OffsetDateTime now = OffsetDateTime.now();
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            credentialMapper.insertCredential(entity);
        } else {
            credentialMapper.updateCredential(entity);
        }
        return entity;
    }

    /**
     * 用新凭证替换同账号同类型的有效凭证。
     */
    @Transactional
    public AccountCredentialEntity replaceActive(AccountCredentialEntity entity) {
        deactivateActive(entity.getAccountId(), entity.getCredentialType());
        entity.setId(null);
        entity.setIsActive(true);
        return save(entity);
    }

    /**
     * 查询指定账号和类型的有效凭证。
     */
    public Optional<AccountCredentialEntity> findActive(Integer accountId, String credentialType) {
        return Optional.ofNullable(credentialMapper.selectActiveCredential(accountId, credentialType));
    }

    /**
     * 停用指定账号的全部有效凭证。
     */
    public void deactivateAllActive(Integer accountId) {
        credentialMapper.deactivateAllActiveCredentials(accountId, OffsetDateTime.now());
    }

    /**
     * 停用指定账号和类型的有效凭证。
     */
    public void deactivateActive(Integer accountId, String credentialType) {
        credentialMapper.deactivateActiveCredential(accountId, credentialType, OffsetDateTime.now());
    }
}