package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号 Mapper。
 */
public interface AccountMapper {

    /** 新增账号。 */
    int insertAccount(AccountEntity entity);

    /** 更新账号。 */
    int updateAccount(AccountEntity entity);

    /** 按主键查询账号。 */
    AccountEntity selectAccountById(@Param("id") Integer id);

    /** 按平台和登录账号查询账号。 */
    AccountEntity selectAccountByPlatformIdAndEmail(@Param("platformId") Integer platformId,
                                                    @Param("email") String email);

    /** 查询平台下的账号列表。 */
    List<AccountEntity> selectAccountsByPlatformId(@Param("platformId") Integer platformId);

    /** 软删除账号。 */
    int softDeleteAccount(@Param("id") Integer id, @Param("deletedAt") OffsetDateTime deletedAt);

    /** 查询平台下已到采集时间的账号。 */
    List<AccountEntity> selectDueAccountsByPlatform(@Param("platformId") Integer platformId,
                                                    @Param("now") OffsetDateTime now,
                                                    @Param("limit") int limit);
}