package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 登录初始化后的后台明细数据。
 *
 * @param groups  渠道/分组列表
 * @param changes 完整变更记录
 * @param apiKeys 脱敏 API Key 列表
 */
public record UpstreamDashboardDetailsResponse(
        List<UpstreamGroupResponse> groups,
        List<UpstreamChangeEventResponse> changes,
        List<AccountApiKeyResponse> apiKeys
) {
}