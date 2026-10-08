package com.monitor.platform.pool.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sub2API 管理员接口返回的号池账号。
 *
 * @param id                       号池账号 ID
 * @param name                     号池账号名称
 * @param platform                 上游平台标识
 * @param accountType              凭证类型
 * @param status                   账号状态
 * @param schedulable              是否可被调度
 * @param errorMessage             最近一次错误信息
 * @param rateLimitedAt            限流发生时间
 * @param rateLimitResetAt         限流恢复时间
 * @param overloadUntil            过载截止时间
 * @param tempUnschedulableUntil   临时不可调度截止时间
 * @param tempUnschedulableReason  临时不可调度原因
 * @param concurrency              并发上限
 * @param priority                 优先级
 * @param rateMultiplier           倍率
 * @param lastUsedAt               最近使用时间
 */
public record Sub2AdminAccount(
        Long id,
        String name,
        String platform,
        String accountType,
        String status,
        Boolean schedulable,
        String errorMessage,
        OffsetDateTime rateLimitedAt,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime overloadUntil,
        OffsetDateTime tempUnschedulableUntil,
        String tempUnschedulableReason,
        Integer concurrency,
        Integer priority,
        BigDecimal rateMultiplier,
        OffsetDateTime lastUsedAt
) {
}