package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号 Mapper。
 */
public interface AccountMapper  {

    int insertAccount(AccountEntity entity);

    int updateAccount(AccountEntity entity);

    AccountEntity selectAccountById(@Param("id") Integer id);

    AccountEntity selectAccountByPlatformIdAndEmail(@Param("platformId") Integer platformId,
                                                    @Param("email") String email);

    List<AccountEntity> selectDueAccounts(@Param("now") OffsetDateTime now,
                                          @Param("limit") int limit);
}