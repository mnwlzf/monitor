package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 登录后的初始化聚合响应。
 *
 * <p>平台、账号、渠道、变更、用量和密钥一次返回，避免前端按平台扇出大量请求；
 * 后端对应查询均为批量读取。</p>
 *
 * @param platforms       平台列表
 * @param accounts        账号列表
 * @param groups          渠道/分组列表
 * @param changes         最近变更事件
 * @param usageDashboards 最新用量看板
 * @param apiKeys         脱敏 API Key 列表
 */
public record UpstreamDashboardResponse(
        List<PlatformResponse> platforms,
        List<AccountResponse> accounts,
        List<UpstreamGroupResponse> groups,
        List<UpstreamChangeEventResponse> changes,
        List<AccountUsageDashboardResponse> usageDashboards,
        List<AccountApiKeyResponse> apiKeys
) {
}
