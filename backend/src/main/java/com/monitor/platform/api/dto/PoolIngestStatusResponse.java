package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 号池直连库增量采集的运行状态。
 *
 * <p>用来回答「分钟级缓存率为什么是空的」：直连库是否配置完整、游标推到哪、
 * 本地最新一条明细有多旧（滞后）。</p>
 *
 * @param enabled            配置里是否打开（SUB2API_DB_ENABLED）
 * @param configured         数据源是否配置完整（enabled + url + 用户名）
 * @param passwordConfigured 是否配置了密码；为空时若上游要求认证会连接失败
 * @param lastUsageLogId     已同步到的 usage_logs.id；0 表示游标尚未初始化
 * @param lastRunAt          游标最后一次推进时间；为空表示从未成功拉取过
 * @param latestSampleAt     本地明细里最新一条请求时间
 * @param lagSeconds         now - latestSampleAt，单位秒；没有明细时为 null
 */
public record PoolIngestStatusResponse(
        boolean enabled,
        boolean configured,
        boolean passwordConfigured,
        long lastUsageLogId,
        OffsetDateTime lastRunAt,
        OffsetDateTime latestSampleAt,
        Long lagSeconds
) {
}