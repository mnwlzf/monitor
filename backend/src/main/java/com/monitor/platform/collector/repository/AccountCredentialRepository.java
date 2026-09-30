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

    @Transactional
    public AccountCredentialEntity replaceActive(AccountCredentialEntity entity) {
        deactivateActive(entity.getAccountId(), entity.getCredentialType());
        entity.setId(null);
        entity.setIsActive(true);
        return save(entity);
    }

    public Optional<AccountCredentialEntity> findActive(Integer accountId, String credentialType) {
        return Optional.ofNullable(credentialMapper.selectActiveCredential(accountId, credentialType));
    }

    public void deactivateActive(Integer accountId, String credentialType) {
        credentialMapper.deactivateActiveCredential(accountId, credentialType, OffsetDateTime.now());
    }
}