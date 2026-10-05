package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokenKeyResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokensResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

/**
 * New API HTTP 客户端。
 *
 * <p>该客户端只负责组装请求、发送请求和反序列化响应。令牌缓存、业务状态判断
 * 和登录编排放在 {@link NewApiAdapter} 中。</p>
 */
@Component
public class NewApiClient {

    private static final Logger log = LoggerFactory.getLogger(NewApiClient.class);

    private static final String LOGIN_PATH = "/api/user/login";
    private static final String REFRESH_PATH = "/api/user/auth/refresh";
    private static final String SELF_PATH = "/api/user/self";
    private static final String GROUPS_PATH = "/api/user/self/groups";
    private static final String TOKENS_PATH = "/api/token/";
    private static final String TOKEN_KEY_PATH = "/api/token/{tokenId}/key";

    /** New API 用 HttpOnly Cookie 下发 refresh token，响应体里拿不到。 */
    private static final String REFRESH_COOKIE_NAME = "new_api_refresh";

    private final RestClient restClient;

    /**
     * @param restClient 全局统一配置的 HTTP 客户端
     */
    public NewApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 登录/续期的返回：响应体，以及需要调用方自行保管的 refresh token。
     *
     * <p>refresh token 不在响应体里，只能从 {@code Set-Cookie} 中提取。</p>
     *
     * @param response     登录或续期的响应体
     * @param refreshToken 新的 refresh token，上游未下发时为 null
     * @param setCookieHeaders 原始 Set-Cookie 响应头，仅用于排查上游未下发的情况
     */
    public record AuthSession(NewApiLoginResponse response, String refreshToken,
                              List<String> setCookieHeaders) {

        /** 便于测试构造不含原始响应头的会话。 */
        public AuthSession(NewApiLoginResponse response, String refreshToken) {
            this(response, refreshToken, null);
        }
    }

    /**
     * 拼接上游地址，避免平台地址以斜杠结尾时产生 {@code //api/...} 双斜杠路径。
     */
    private String url(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * 打印拼接后的完整请求地址，便于确认实际请求的 URL。
     */
    private String logRequest(String method, String requestUrl) {
        log.info("New API 请求: {} {}", method, requestUrl);
        return requestUrl;
    }

    /**
     * 调用 New API 登录接口，不传 Turnstile 校验值。
     *
     * @param baseUrl New API 服务地址
     * @param request 登录请求体
     * @return 登录响应体与 refresh token
     */
    public AuthSession login(String baseUrl, NewApiLoginRequest request) {
        return login(baseUrl, request, "");
    }

    /**
     * 调用 New API 登录接口。
     *
     * @param baseUrl   New API 服务地址
     * @param request   登录请求体
     * @param turnstile Turnstile 校验值，可为空
     * @return 登录响应体与 refresh token
     */
    public AuthSession login(String baseUrl, NewApiLoginRequest request, String turnstile) {
        String requestUrl = logRequest("POST",
                url(baseUrl, LOGIN_PATH) + "?turnstile=" + (turnstile == null ? "" : turnstile));
        ResponseEntity<NewApiLoginResponse> entity = restClient.post()
                .uri(requestUrl)
                .header(HttpHeaders.ORIGIN, resolveOrigin(baseUrl))
                .header(HttpHeaders.REFERER, resolveOrigin(baseUrl) + "/")
                .body(request)
                .retrieve()
                .toEntity(NewApiLoginResponse.class);
        return new AuthSession(entity.getBody(), extractRefreshToken(entity.getHeaders()),
                entity.getHeaders().get(HttpHeaders.SET_COOKIE));
    }

    /**
     * 用 refresh token 换取新的 access token。
     *
     * <p>上游只从 Cookie {@code new_api_refresh} 读取 refresh token；开启安全
     * Cookie 时还会校验 Origin，因此这里同时带上 Origin 头。续期成功后上游会
     * 轮换 refresh token，必须用返回值里的新 token 覆盖旧的。</p>
     *
     * @param baseUrl      New API 服务地址
     * @param refreshToken 上次登录/续期拿到的 refresh token
     * @return 续期响应体与轮换后的 refresh token
     */
    public AuthSession refreshAuth(String baseUrl, String refreshToken) {
        String requestUrl = logRequest("POST", url(baseUrl, REFRESH_PATH));
        ResponseEntity<NewApiLoginResponse> entity = restClient.post()
                .uri(requestUrl)
                .header(HttpHeaders.COOKIE, REFRESH_COOKIE_NAME + "=" + refreshToken)
                .header(HttpHeaders.ORIGIN, resolveOrigin(baseUrl))
                .header(HttpHeaders.REFERER, resolveOrigin(baseUrl) + "/")
                .retrieve()
                .toEntity(NewApiLoginResponse.class);
        return new AuthSession(entity.getBody(), extractRefreshToken(entity.getHeaders()),
                entity.getHeaders().get(HttpHeaders.SET_COOKIE));
    }

    /**
     * 从响应头里取出 refresh token。
     *
     * <p>同一个响应里可能出现多条 {@code new_api_refresh}（例如先清空旧值再写入新值，
     * 或者同时下发 host-only 与 Domain 两个版本），因此遇到空值要继续往后找，
     * 不能直接判定为"上游未下发"。</p>
     */
    private String extractRefreshToken(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (String setCookie : setCookies) {
            for (String part : setCookie.split(";")) {
                String attribute = part.trim();
                if (attribute.startsWith(REFRESH_COOKIE_NAME + "=")) {
                    String value = attribute.substring(REFRESH_COOKIE_NAME.length() + 1);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 取服务地址的 Origin（scheme://host[:port]），用于通过上游的 Origin 校验。
     */
    private String resolveOrigin(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                return baseUrl;
            }
            return uri.getPort() > 0
                    ? scheme + "://" + host + ":" + uri.getPort()
                    : scheme + "://" + host;
        } catch (IllegalArgumentException ex) {
            return baseUrl;
        }
    }

    /**
     * 获取当前用户信息。
     *
     * @param baseUrl     New API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @return New API 原始用户信息响应
     */
    public NewApiSelfResponse fetchSelf(String baseUrl, String accessToken) {
        String requestUrl = logRequest("GET", url(baseUrl, SELF_PATH));
        return restClient.get()
                .uri(requestUrl)
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
        String requestUrl = logRequest("GET", url(baseUrl, GROUPS_PATH));
        return restClient.get()
                .uri(requestUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NewApiGroupsResponse.class);
    }

    /**
     * 分页获取当前用户的密钥列表。
     *
     * @param baseUrl     New API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @param page        页码，从 1 开始
     * @param size        每页记录数
     * @return New API 密钥列表响应
     */
    public NewApiTokensResponse fetchTokens(String baseUrl, String accessToken, int page, int size) {
        String requestUrl = logRequest("GET",
                url(baseUrl, TOKENS_PATH) + "?p=" + page + "&size=" + size);
        return restClient.get()
                .uri(requestUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NewApiTokensResponse.class);
    }

    /**
     * 获取指定密钥的完整明文。
     *
     * <p>上游该路由只注册了 POST，GET 会被 New API 的兜底路由当作未知地址返回 404。</p>
     *
     * @param baseUrl     New API 服务地址
     * @param accessToken 登录后获得的访问令牌
     * @param tokenId     密钥 ID
     * @return New API 完整密钥响应
     */
    public NewApiTokenKeyResponse fetchTokenKey(String baseUrl, String accessToken, Long tokenId) {
        String requestUrl = logRequest("POST",
                url(baseUrl, TOKEN_KEY_PATH.replace("{tokenId}", String.valueOf(tokenId))));
        return restClient.post()
                .uri(requestUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NewApiTokenKeyResponse.class);
    }
}
