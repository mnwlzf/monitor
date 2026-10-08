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

    /**
     * 按密钥 SHA-256 查询有效的本地密钥主键。
     *
     * <p>号池账号绑定上游 Key 时使用：只传哈希，绝不传明文。</p>
     *
     * <p>这里是<strong>跨平台</strong>查找：用户自建 sub2api 的号池账号，往往是用
     * 其它上游平台采集到的 Key 建起来的，因此不能只在同一个平台内匹配。
     * {@code preferredPlatformId} 仅用于「同一个 Key 在多个平台都存在时优先取该平台」。</p>
     */
    Long selectActiveKeyIdByHash(@Param("preferredPlatformId") Integer preferredPlatformId,
                                 @Param("keyHash") String keyHash);

    /** 将未出现在本轮采集结果中的密钥标记为失效。 */
    int deactivateMissingKeys(@Param("accountId") Integer accountId,
                              @Param("platformType") String platformType,
                              @Param("externalKeyIds") Collection<String> externalKeyIds,
                              @Param("changedAt") OffsetDateTime changedAt);
}
