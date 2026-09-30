package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * 账号凭证 Mapper。
 */
public interface AccountCredentialMapper  {

    int insertCredential(AccountCredentialEntity entity);

    int updateCredential(AccountCredentialEntity entity);

    AccountCredentialEntity selectActiveCredential(@Param("accountId") Integer accountId,
                                                   @Param("credentialType") String credentialType);

    int deactivateActiveCredential(@Param("accountId") Integer accountId,
                                   @Param("credentialType") String credentialType,
                                   @Param("updatedAt") OffsetDateTime updatedAt);

    int deactivateAllActiveCredentials(@Param("accountId") Integer accountId,
                                       @Param("updatedAt") OffsetDateTime updatedAt);
}