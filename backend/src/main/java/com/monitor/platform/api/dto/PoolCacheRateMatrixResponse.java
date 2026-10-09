package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 号池「多时间窗缓存率对比」响应。
 *
 * <p>行是号池账号，列是若干时间窗（近 1 小时 / 6 小时 / 24 小时 / 7 天 / 30 天 ...），
 * 每个格子是该账号在该时间窗内的缓存命中率与请求数。一次性返回，
 * 便于横向对比「同一个账号在短窗口和长窗口的缓存表现差异」。</p>
 *
 * @param windows  时间窗定义（列）
 * @param accounts 账号行
 */
public record PoolCacheRateMatrixResponse(
        List<Window> windows,
        List<AccountRow> accounts
) {

    /**
     * 一个时间窗（表格的一列）。
     *
     * @param key   窗口标识，如 1h / 24h / 7d
     * @param label 展示名称，如「近 1 小时」
     * @param from  窗口起点
     */
    public record Window(String key, String label, OffsetDateTime from) {
    }

    /**
     * 一个号池账号（表格的一行）。
     *
     * @param externalAccountId 号池账号 ID
     * @param name              号池账号名称
     * @param boundKeyName      绑定的本地密钥名称
     * @param boundKeyMasked    绑定的本地密钥脱敏值
     * @param cells             各时间窗的指标，顺序与 {@code windows} 一致
     */
    public record AccountRow(
            Long externalAccountId,
            String name,
            String boundKeyName,
            String boundKeyMasked,
            List<Cell> cells
    ) {
    }

    /**
     * 单元格指标。
     *
     * @param key                 窗口标识
     * @param requests            请求数（样本量，判断命中率是否可信）
     * @param cacheHitRate        缓存命中率（0~1），没有样本时为 null
     * @param inputTokens         输入 token
     * @param cacheReadTokens     缓存读取 token
     * @param cacheCreationTokens 缓存写入 token
     * @param firstTokenSamples   首 token 有效样本数
     * @param avgFirstTokenMs     平均首 token 耗时（毫秒）
     * @param avgDurationMs       平均总耗时（毫秒）
     */
    public record Cell(
            String key,
            long requests,
            Double cacheHitRate,
            Long inputTokens,
            Long cacheReadTokens,
            Long cacheCreationTokens,
            Long firstTokenSamples,
            Double avgFirstTokenMs,
            Double avgDurationMs
    ) {
    }
}