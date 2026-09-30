package com.monitor.platform.adapter.sub2api;

import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginRequest;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
    private static final String PROFILE_PATH = "/api/v1/auth/me";
    private static final String KEYS_PATH = "/api/v1/keys";
    private static final String AVAILABLE_GROUPS_PATH = "/api/v1/groups/available";
    private static final String TIME_ZONE = "Asia/Shanghai";

    private final RestClient restClient;

    /**
     * @param restClient 全局统一配置的 HTTP 客户端，已包含连接、读取超时和网络重试策略
     */
    public Sub2ApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 调用 Sub2API 登录接口。
     *
     * @param baseUrl Sub2API 服务地址
     * @param request 登录请求体
     * @return Sub2API 原始登录响应
     */
    public Sub2LoginResponse login(String baseUrl, Sub2LoginRequest request) {
        return restClient.post()
                .uri(baseUrl + LOGIN_PATH)
                .body(request)
                .retrieve()
                .body(Sub2LoginResponse.class);
    }

    /**
     * 获取当前账号的个人信息。
     *
     * @param baseUrl     Sub2API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return Sub2API 原始个人信息响应
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
     *
     * <p>查询参数与现有采集行为保持一致：第一页、每页 40 条、按创建时间倒序。</p>
     *
     * @param baseUrl     Sub2API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return Sub2API 原始密钥列表响应
     */
    public Sub2KeysResponse fetchKeys(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + KEYS_PATH
                                + "?page={page}&page_size={size}&sort_by={sortBy}&sort_order={order}&timezone={tz}",
                        1, 40, "created_at", "desc", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2KeysResponse.class);
    }

    /**
     * 获取当前账号可用分组。
     *
     * @param baseUrl     Sub2API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return Sub2API 原始分组列表响应
     */
    public Sub2GroupsResponse fetchAvailableGroups(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + AVAILABLE_GROUPS_PATH + "?timezone={tz}", TIME_ZONE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2GroupsResponse.class);
    }
}