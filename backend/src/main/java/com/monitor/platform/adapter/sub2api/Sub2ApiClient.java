package com.monitor.platform.adapter.sub2api;

import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ApiKeysUsageResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginRequest;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2RefreshTokenRequest;
import com.monitor.platform.adapter.sub2api.model.Sub2RefreshTokenResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sub2API HTTP 客户端。
 *
 * <p>该客户端只负责“组装请求、发送请求、反序列化响应”，不负责 Token 缓存、
 * 业务状态码判断和登录失效后的重试编排。这些跨请求的流程统一放在
 * {@link Sub2ApiAdapter} 中，避免 HTTP 细节与采集流程耦合。</p>
 *
 * <p>网络层异常的重试由全局 {@code RetryInterceptor} 处理，因此这里每次只发送一次请求。</p>
 */
@Component
public class Sub2ApiClient {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String PROFILE_PATH = "/api/v1/auth/me";
    private static final String KEYS_PATH = "/api/v1/keys";
    private static final String KEYS_USAGE_PATH = "/api/v1/usage/dashboard/api-keys-usage";
    private static final String AVAILABLE_GROUPS_PATH = "/api/v1/groups/available";
    private static final String USAGE_DASHBOARD_STATS_PATH = "/api/v1/usage/dashboard/stats";
    private static final String TIME_ZONE = "Asia/Shanghai";
    private static final int DEFAULT_KEY_PAGE_SIZE = 100;

    private final RestClient restClient;

    /**
     * @param restClient 全局统一配置的 HTTP 客户端，已包含连接、读取超时和网络重试策略
     */
    public Sub2ApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 调用 Sub2API 登录接口。
     */
    public Sub2LoginResponse login(String baseUrl, Sub2LoginRequest request) {
        return restClient.post()
                .uri(baseUrl + LOGIN_PATH)
                .body(request)
                .retrieve()
                .body(Sub2LoginResponse.class);
    }

    /**
     * 调用 Sub2API 刷新令牌接口，用 refresh_token 换取新的令牌对。
     */
    public Sub2RefreshTokenResponse refreshToken(String baseUrl, String refreshToken) {
        return restClient.post()
                .uri(baseUrl + REFRESH_PATH)
                .body(new Sub2RefreshTokenRequest(refreshToken))
                .retrieve()
                .body(Sub2RefreshTokenResponse.class);
    }

    /**
     * 获取当前账号的个人信息。
     */
    public Sub2ProfileResponse fetchProfile(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + PROFILE_PATH + "?timezone={tz}", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2ProfileResponse.class);
    }

    /**
     * 获取当前账号的密钥列表。
     */
    public Sub2KeysResponse fetchKeys(String baseUrl, String accessToken) {
        return fetchKeys(baseUrl, accessToken, 1, DEFAULT_KEY_PAGE_SIZE);
    }

    /**
     * 按页获取当前账号的密钥列表。
     *
     * @param baseUrl     Sub2API 服务地址
     * @param accessToken 登录令牌
     * @param page        页码，从 1 开始
     * @param pageSize    每页记录数
     * @return Sub2API 密钥列表响应
     */
    public Sub2KeysResponse fetchKeys(String baseUrl, String accessToken, int page, int pageSize) {
        return restClient.get()
                .uri(baseUrl + KEYS_PATH
                                + "?page={page}&page_size={size}&sort_by={sortBy}&sort_order={order}&timezone={tz}",
                        page, pageSize, "created_at", "desc", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2KeysResponse.class);
    }

    /**
     * 批量获取密钥用量。
     *
     * <p>密钥列表不返回真实用量，每密钥的今日/累计实际消耗只能通过该接口按 ID 批量查询。</p>
     *
     * @param baseUrl     Sub2API 服务地址
     * @param accessToken 登录令牌
     * @param apiKeyIds   密钥 ID 列表
     * @return Sub2API 密钥用量响应
     */
    public Sub2ApiKeysUsageResponse fetchKeysUsage(String baseUrl, String accessToken, List<Long> apiKeyIds) {
        return restClient.post()
                .uri(baseUrl + KEYS_USAGE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("api_key_ids", apiKeyIds))
                .retrieve()
                .body(Sub2ApiKeysUsageResponse.class);
    }

    /**
     * 获取当前账号可用分组。
     */
    public Sub2GroupsResponse fetchAvailableGroups(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + AVAILABLE_GROUPS_PATH + "?timezone={tz}", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2GroupsResponse.class);
    }

    /**
     * 获取当前账号用量看板统计。
     */
    public Sub2UsageDashboardResponse fetchUsageDashboardStats(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + USAGE_DASHBOARD_STATS_PATH + "?timezone={tz}", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2UsageDashboardResponse.class);
    }
}
