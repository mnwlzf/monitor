package com.monitor.platform.pool.ingest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 自建 Sub2API 库 {@code usage_logs} 中的一条用量明细（只读）。
 *
 * @param id                  自增主键，用作增量游标
 * @param accountId           Sub2API 号池账号 ID（对应本项目 pool_accounts.external_account_id）
 * @param requestId           上游请求 ID，用于幂等去重
 * @param apiKeyId            调用的密钥 ID
 * @param model               模型
 * @param createdAt           请求时间
 * @param firstTokenMs        首 token 耗时（毫秒）
 * @param durationMs          总耗时（毫秒）
 * @param inputTokens         输入 token
 * @param outputTokens        输出 token
 * @param cacheReadTokens     缓存读取 token
 * @param cacheCreationTokens 缓存写入 token
 * @param totalCost           原价成本
 * @param actualCost          实际成本
 */
public record Sub2ApiUsageLogRow(
        long id,
        long accountId,
        String requestId,
        Long apiKeyId,
        String model,
        OffsetDateTime createdAt,
        Integer firstTokenMs,
        Integer durationMs,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        BigDecimal totalCost,
        BigDecimal actualCost
) {
}