package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.mapper.AccountMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账号持久化仓储。
 */
@Repository
public class AccountRepository {

    private final AccountMapper accountMapper;

    public AccountRepository(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public AccountEntity save(AccountEntity entity) {
        OffsetDateTime now = OffsetDateTime.now();
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            accountMapper.insertAccount(entity);
        } else {
            accountMapper.updateAccount(entity);
        }
        return entity;
    }

    public Optional<AccountEntity> findById(Integer id) {
        return Optional.ofNullable(accountMapper.selectAccountById(id));
    }

    public Optional<AccountEntity> findByPlatformIdAndEmail(Integer platformId, String email) {
        return Optional.ofNullable(accountMapper.selectAccountByPlatformIdAndEmail(platformId, email));
    }

    public List<AccountEntity> findByPlatformId(Integer platformId) {
        return accountMapper.selectAccountsByPlatformId(platformId);
    }
    public void softDelete(Integer id) {
        accountMapper.softDeleteAccount(id, OffsetDateTime.now());
    }

    public List<AccountEntity> findDueForCollection(Integer platformId, OffsetDateTime now, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return accountMapper.selectDueAccountsByPlatform(platformId, now, safeLimit);
    }
}