package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上游渠道/分组响应。
 *
 * @param id                 渠道主键
 * @param accountId          所属账号主键
 * @param externalGroupId    上游渠道 ID
 * @param groupName          渠道名称
 * @param description        渠道描述
 * @param platform           渠道所属平台
 * @param currentRatio       当前倍率
 * @param currentBaseRatio   当前基础倍率
 * @param status             上游状态
 * @param active             本地是否有效
 * @param firstSeenAt        首次发现时间
 * @param lastSeenAt         最后可见时间
 * @param lastChangedAt      最后变更时间
 */
public record UpstreamGroupResponse(
        Long id,
        Integer accountId,
        String externalGroupId,
        String groupName,
        String description,
        String platform,
        BigDecimal currentRatio,
        BigDecimal currentBaseRatio,
        String status,
        Boolean active,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastChangedAt
) {
}