package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 消息存档概况（页面顶部展示）。
 *
 * @param enabled       存档是否开启
 * @param total         已存档条数
 * @param earliest      最早一条的时间，用于说明存档起点
 * @param retentionDays 保留天数；非正数表示永久保留
 */
public record BotMessageArchiveStats(boolean enabled, long total, OffsetDateTime earliest, int retentionDays) {
}