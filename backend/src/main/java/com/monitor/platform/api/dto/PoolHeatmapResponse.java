package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 号池「色块矩阵」趋势响应。
 *
 * <p>行 = 平台 / 号池账号（本项目按平台与账号分类），列 = 等宽时间桶；
 * 每个格子是该账号在该时间段内的聚合指标，前端按选中的指标着色，
 * 悬停看明细、点击进详情。没有样本的格子 {@code requests=0}、各比率为 null，
 * 前端画成灰色「无流量」，不用 0 冒充。</p>
 *
 * @param from        窗口起点
 * @param to          窗口终点
 * @param granularity 聚合粒度：minute / hour / day
 * @param buckets     时间桶起点，顺序即列顺序
 * @param summary     全部行的汇总指标（顶部卡片）
 * @param rows        账号行
 */
public record PoolHeatmapResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String granularity,
        List<OffsetDateTime> buckets,
        Metrics summary,
        List<Row> rows
) {

    /**
     * 一个号池账号（表格的一行）。
     *
     * @param externalAccountId 号池账号 ID
     * @param name              账号名称
     * @param platform          账号的上游平台（openai / anthropic / grok ...）
     * @param boundKeyName      绑定的本地密钥名称
     * @param boundKeyMasked    绑定的本地密钥脱敏值
     * @param total             整行汇总指标（窗口内）
     * @param cells             各时间桶指标，顺序与 {@code buckets} 一致
     */
    public record Row(
            Long externalAccountId,
            String name,
            String platform,
            String boundKeyName,
            String boundKeyMasked,
            Metrics total,
            List<Metrics> cells
    ) {
    }

    /**
     * 一个时间桶（或整行汇总）的指标。
     *
     * @param requests        请求数（样本量）
     * @param cacheHitRate    缓存命中率（0~1），无样本时为 null
     * @param avgFirstTokenMs 平均首 token 耗时（毫秒），无有效样本时为 null
     * @param tokensPerSecond 每秒输出 token（SUM(output) / SUM(duration)）
     * @param rpm             每分钟请求数
     * @param actualCost      实际成本
     */
    public record Metrics(
            long requests,
            Double cacheHitRate,
            Double avgFirstTokenMs,
            Double tokensPerSecond,
            Double rpm,
            BigDecimal actualCost
    ) {
    }
}