package com.monitor.platform.bot.user;

/**
 * Sub2API 管理端 {@code GET /api/v1/admin/usage/stats} 返回的用量统计。
 *
 * @param totalRequests            请求数
 * @param totalInputTokens         输入 Token
 * @param totalOutputTokens        输出 Token
 * @param totalCacheCreationTokens 缓存写入 Token
 * @param totalCacheReadTokens     缓存读取 Token
 * @param totalTokens              总 Token
 * @param totalCost                标准计费
 * @param totalActualCost          实际扣费
 * @param averageDurationMs        平均耗时（毫秒）
 */
public record Sub2AdminUsageStats(
        long totalRequests,
        long totalInputTokens,
        long totalOutputTokens,
        long totalCacheCreationTokens,
        long totalCacheReadTokens,
        long totalTokens,
        double totalCost,
        double totalActualCost,
        double averageDurationMs
) {
}