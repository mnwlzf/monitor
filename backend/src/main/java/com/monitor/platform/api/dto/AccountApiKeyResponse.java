package com.monitor.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号 API Key 响应。
 *
 * <p>仅返回脱敏后的密钥，完整明文不通过该接口下发；平台特有明细以 JSON 对象
 * 放在 {@code metrics} 中，平台未提供的指标为 {@code null}。</p>
 *
 * @param id                 密钥主键
 * @param accountId          所属账号主键
 * @param platformId         平台主键
 * @param accountName        账号展示名称
 * @param platformType       平台类型
 * @param externalKeyId      上游密钥 ID
 * @param keyName            密钥名称
 * @param keyMasked          脱敏后的密钥
 * @param status             归一化状态
 * @param upstreamStatus     上游原始状态
 * @param groupName          所属分组名称
 * @param groupPlatform      所属分组的平台标识
 * @param unlimitedQuota     是否不限额度
 * @param remainQuota        剩余额度，USD
 * @param usedQuota          累计已用额度，USD
 * @param quotaUnit          额度单位
 * @param modelLimitsEnabled 是否启用模型限制
 * @param modelLimits        模型限制内容
 * @param allowIps           IP 白名单
 * @param expiresAt          过期时间
 * @param upstreamCreatedAt  上游创建时间
 * @param lastUsedAt         最近使用时间
 * @param active             本地是否有效
 * @param firstSeenAt        首次发现时间
 * @param lastSeenAt         最后可见时间
 * @param lastChangedAt      最后变更时间
 * @param metrics            平台特有明细 JSON
 */
public record AccountApiKeyResponse(
        Long id,
        Integer accountId,
        Integer platformId,
        String accountName,
        String platformType,
        String externalKeyId,
        String keyName,
        String keyMasked,
        String status,
        String upstreamStatus,
        String groupName,
        String groupPlatform,
        Boolean unlimitedQuota,
        BigDecimal remainQuota,
        BigDecimal usedQuota,
        String quotaUnit,
        Boolean modelLimitsEnabled,
        String modelLimits,
        String allowIps,
        OffsetDateTime expiresAt,
        OffsetDateTime upstreamCreatedAt,
        OffsetDateTime lastUsedAt,
        Boolean active,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastChangedAt,
        JsonNode metrics
) {
}
