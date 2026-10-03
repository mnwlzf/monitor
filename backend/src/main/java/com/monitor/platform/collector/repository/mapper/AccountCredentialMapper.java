package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * 账号凭证 Mapper。
 */
public interface AccountCredentialMapper  {

    /** 新增凭证。 */
    int insertCredential(AccountCredentialEntity entity);

    /** 更新凭证。 */
    int updateCredential(AccountCredentialEntity entity);

    /** 查询账号指定类型的有效凭证。 */
    AccountCredentialEntity selectActiveCredential(@Param("accountId") Integer accountId,
                                                   @Param("credentialType") String credentialType);

    /** 停用账号指定类型的有效凭证。 */
    int deactivateActiveCredential(@Param("accountId") Integer accountId,
                                   @Param("credentialType") String credentialType,
                                   @Param("updatedAt") OffsetDateTime updatedAt);

    /** 停用账号的全部有效凭证。 */
    int deactivateAllActiveCredentials(@Param("accountId") Integer accountId,
                                       @Param("updatedAt") OffsetDateTime updatedAt);
}