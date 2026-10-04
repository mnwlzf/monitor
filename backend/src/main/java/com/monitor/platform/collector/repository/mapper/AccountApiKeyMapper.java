package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 账号 API Key 当前状态 Mapper。
 */
public interface AccountApiKeyMapper {

    /** 新增密钥。 */
    int insertKey(AccountApiKeyEntity entity);

    /** 更新密钥。 */
    int updateKey(AccountApiKeyEntity entity);

    /** 按账号、平台类型和上游密钥 ID 查询密钥。 */
    AccountApiKeyEntity selectKeyByAccountAndExternalId(
            @Param("accountId") Integer accountId,
            @Param("platformType") String platformType,
            @Param("externalKeyId") String externalKeyId);

    /** 按主键查询密钥。 */
    AccountApiKeyEntity selectKeyById(@Param("id") Long id);

    /** 查询账号下的密钥，可选择只返回有效密钥。 */
    List<AccountApiKeyEntity> selectKeysByAccount(@Param("accountId") Integer accountId,
                                                  @Param("activeOnly") boolean activeOnly);

    /** 将未出现在本轮采集结果中的密钥标记为失效。 */
    int deactivateMissingKeys(@Param("accountId") Integer accountId,
                              @Param("platformType") String platformType,
                              @Param("externalKeyIds") Collection<String> externalKeyIds,
                              @Param("changedAt") OffsetDateTime changedAt);
}
