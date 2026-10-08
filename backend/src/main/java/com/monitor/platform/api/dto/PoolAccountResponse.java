package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 号池账号监控响应。
 *
 * @param externalAccountId      自建 Sub2API 的号池账号 ID
 * @param name                   账号名称
 * @param platform               上游平台标识
 * @param accountType            凭证类型
 * @param status                 账号状态
 * @param schedulable            是否可被调度
 * @param errorMessage           最近一次错误信息
 * @param rateLimitedAt          限流发生时间
 * @param rateLimitResetAt       限流恢复时间
 * @param tempUnschedulableUntil 临时不可调度截止时间
 * @param tempUnschedulableReason 临时不可调度原因
 * @param concurrency            并发上限
 * @param priority               优先级
 * @param rateMultiplier         倍率
 * @param lastUsedAt             最近使用时间
 * @param boundKeyId             绑定的本地密钥 ID
 * @param boundKeyName           绑定的本地密钥名称
 * @param boundKeyMasked         绑定的本地密钥脱敏值
 * @param lastSampleAt           明细采集水位
 * @param lastSyncError          最近一次明细采集错误
 * @param requests               请求数
 * @param inputTokens            输入 token
 * @param outputTokens           输出 token
 * @param cacheReadTokens        缓存读取 token
 * @param cacheCreationTokens    缓存写入 token
 * @param firstTokenSamples      首 token 有效样本数
 * @param avgFirstTokenMs        平均首 token 耗时（毫秒）
 * @param p95FirstTokenMs        首 token P95 耗时（毫秒）
 * @param avgDurationMs          平均总耗时（毫秒）
 * @param totalCost              原价成本
 * @param totalActualCost        实际成本
 * @param cacheHitRate           缓存命中率（0~1）
 */
public record PoolAccountResponse(
        Long externalAccountId,
        String name,
        String platform,
        String accountType,
        String status,
        Boolean schedulable,
        String errorMessage,
        OffsetDateTime rateLimitedAt,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime tempUnschedulableUntil,
        String tempUnschedulableReason,
        Integer concurrency,
        Integer priority,
        BigDecimal rateMultiplier,
        OffsetDateTime lastUsedAt,
        Long boundKeyId,
        String boundKeyName,
        String boundKeyMasked,
        OffsetDateTime lastSampleAt,
        String lastSyncError,
        Long requests,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long firstTokenSamples,
        Double avgFirstTokenMs,
        Double p95FirstTokenMs,
        Double avgDurationMs,
        BigDecimal totalCost,
        BigDecimal totalActualCost,
        Double cacheHitRate
) {
}