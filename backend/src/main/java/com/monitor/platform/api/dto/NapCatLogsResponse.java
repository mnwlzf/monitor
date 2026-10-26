package com.monitor.platform.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * NapCat 实时日志快照。
 *
 * @param configured 是否读到 WebUI token 配置
 * @param connected  当前是否连上 NapCat SSE 日志流
 * @param lines      最近的日志行
 * @param updatedAt  日志缓冲区最近更新时间
 * @param error      最近一次连接错误
 */
public record NapCatLogsResponse(
        boolean configured,
        boolean connected,
        List<NapCatLogLineResponse> lines,
        Instant updatedAt,
        String error
) {
}