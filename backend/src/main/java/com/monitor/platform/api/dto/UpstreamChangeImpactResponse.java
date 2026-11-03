package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 单条变更事件的影响分析。
 *
 * @param eventId 变更事件主键
 * @param impacts 本平台受影响的账号 × 分组
 */
public record UpstreamChangeImpactResponse(
        Long eventId,
        List<UpstreamChangeEventResponse.ChangeImpact> impacts
) {
}