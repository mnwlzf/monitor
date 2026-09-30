package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * New API HTTP 客户端。
 *
 * <p>该客户端只负责组装请求、发送请求和反序列化响应。令牌缓存、业务状态判断
 * 和登录编排放在 {@link NewApiAdapter} 中。</p>
 */
@Component
public class NewApiClient {

    private static final String LOGIN_PATH = "/api/user/login";
    private static final String SELF_PATH = "/api/user/self";
    private static final String GROUPS_PATH = "/api/user/self/groups";

    private final RestClient restClient;

    /**
     * @param restClient 全局统一配置的 HTTP 客户端
     */
    public NewApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 调用 New API 登录接口，不传 Turnstile 校验值。
     *
     * @param baseUrl New API 服务地址
     * @param request 登录请求体
     * @return New API 原始登录响应
     */
    public NewApiLoginResponse login(String baseUrl, NewApiLoginRequest request) {
        return login(baseUrl, request, "");
    }

    /**
     * 调用 New API 登录接口。
     *
     * @param baseUrl   New API 服务地址
     * @param request   登录请求体
     * @param turnstile Turnstile 校验值，可为空
     * @return New API 原始登录响应
     */
    public NewApiLoginResponse login(String baseUrl, NewApiLoginRequest request, String turnstile) {
        return restClient.post()
                .uri(baseUrl + LOGIN_PATH + "?turnstile={turnstile}",
                        turnstile == null ? "" : turnstile)
                .body(request)
                .retrieve()
                .body(NewApiLoginResponse.class);
    }

    /**
     * 获取当前用户信息。
     *
     * @param baseUrl     New API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return New API 原始用户信息响应
     */
    public NewApiSelfResponse fetchSelf(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + SELF_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NewApiSelfResponse.class);
    }

    /**
     * 获取当前用户可用分组。
     *
     * @param baseUrl     New API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return New API 原始分组响应
     */
    public NewApiGroupsResponse fetchGroups(String baseUrl, String accessToken) {
        return restClient.get()
                .uri(baseUrl + GROUPS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NewApiGroupsResponse.class);
    }
}