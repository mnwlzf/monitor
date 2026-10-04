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

    /**
     * 新增或更新账号，并维护更新时间。
     */
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

    /**
     * 按主键查询未删除账号。
     */
    public Optional<AccountEntity> findById(Integer id) {
        return Optional.ofNullable(accountMapper.selectAccountById(id));
    }

    /**
     * 按平台和登录账号查询账号，用于创建时的重复校验。
     */
    public Optional<AccountEntity> findByPlatformIdAndEmail(Integer platformId, String email) {
        return Optional.ofNullable(accountMapper.selectAccountByPlatformIdAndEmail(platformId, email));
    }

    /**
     * 查询指定平台下的全部未删除账号。
     */
    public List<AccountEntity> findByPlatformId(Integer platformId) {
        return accountMapper.selectAccountsByPlatformId(platformId);
    }
    /**
     * 软删除账号，保留历史采集和变更数据。
     */
    public void softDelete(Integer id) {
        accountMapper.softDeleteAccount(id, OffsetDateTime.now());
    }

    /**
     * 查询指定平台下所有启用且未删除的账号。
     */
    public List<AccountEntity> findEnabledByPlatformId(Integer platformId) {
        return accountMapper.selectEnabledAccountsByPlatform(platformId);
    }

    /**
     * 查询指定平台下已到采集时间且启用的账号，limit 会限制在 1 到 1000。
     */
    public List<AccountEntity> findDueForCollection(Integer platformId, OffsetDateTime now, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return accountMapper.selectDueAccountsByPlatform(platformId, now, safeLimit);
    }
}