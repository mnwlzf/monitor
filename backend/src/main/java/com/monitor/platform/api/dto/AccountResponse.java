package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 采集账号响应。
 *
 * @param id              账号主键
 * @param platformId      所属平台主键
 * @param displayName     前端展示名称
 * @param loginName       登录账号
 * @param platformType    平台类型，newapi 或 sub2api
 * @param authType        认证类型：PASSWORD 或 TOKEN
 * @param authStatus      凭证认证状态
 * @param status          账号是否启用
 * @param balance         最新余额
 * @param frozenBalance   最新冻结余额
 * @param quota           最新剩余额度
 * @param usedQuota       最新累计已用额度
 * @param quotaUnit       额度单位
 * @param requestCount    最新累计请求数
 * @param lastCollectStatus 最近一次采集状态
 * @param lastCollectedAt 最近一次采集时间
 * @param nextCollectAt   下一次计划采集时间
 */
public record AccountResponse(
        Integer id,
        Integer platformId,
        String displayName,
        String loginName,
        String platformType,
        String authType,
        String authStatus,
        Boolean status,
        BigDecimal balance,
        BigDecimal frozenBalance,
        BigDecimal quota,
        BigDecimal usedQuota,
        String quotaUnit,
        Long requestCount,
        String lastCollectStatus,
        OffsetDateTime lastCollectedAt,
        OffsetDateTime nextCollectAt
) {
}