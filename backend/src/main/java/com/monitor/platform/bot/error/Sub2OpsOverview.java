package com.monitor.platform.bot.error;

/**
 * Sub2API Ops 概览接口的精简结果。
 */
public record Sub2OpsOverview(
        int healthScore,
        long requestCountTotal,
        long errorCountTotal,
        long errorCountSla,
        long businessLimitedCount,
        long upstream429Count,
        long upstream529Count,
        long upstreamErrorCountExcl429529,
        double errorRate,
        double upstreamErrorRate,
        double sla
) {
}
