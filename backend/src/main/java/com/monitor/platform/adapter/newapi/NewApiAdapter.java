package com.monitor.platform.adapter.newapi;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokenKeyResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokensResponse;
import com.monitor.platform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * New API 采集适配器。
 *
 * <p>该适配器负责 New API 登录编排、业务响应校验和登录凭证缓存。New API 后续接口
 * 依赖 {@code Authorization}，登录成功后缓存访问令牌和用户 ID。</p>
 */
@Component
public class NewApiAdapter {

    private static final Logger log = LoggerFactory.getLogger(NewApiAdapter.class);

    /** 上游未返回 access_expires_at 时使用的兜底缓存时长。 */
    private static final Duration FALLBACK_TOKEN_TTL = Duration.ofMinutes(15);

    /** 提前过期时间，避免 Redis 中的令牌刚取出就失效。 */
    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 30L;

    /** 上游未返回会话过期时间时，refresh token 的兜底缓存时长。 */
    private static final Duration FALLBACK_REFRESH_TTL = Duration.ofDays(7);

    /** 上游账号登录会话数超限时的业务错误码。 */
    private static final String CODE_AUTH_SESSION_LIMIT = "UPSTREAM_AUTH_SESSION_LIMIT";

    private final NewApiClient newApiClient;
    private final StringRedisTemplate stringRedisTemplate;

    /** 同一账号的登录与续期必须串行，避免 refresh token 轮换被并发使用导致会话被吊销。 */
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * @param newApiClient        New API HTTP 客户端
     * @param stringRedisTemplate 登录凭证缓存
     */
    public NewApiAdapter(NewApiClient newApiClient,
                         StringRedisTemplate stringRedisTemplate) {
        this.newApiClient = newApiClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 登录并缓存访问令牌与用户 ID。
     *
     * @param baseUrl  New API 服务地址
     * @param username 登录用户名
     * @param password 登录密码
     */
    public void login(String baseUrl, String username, String password) {
        login(baseUrl, username, password, "");
    }

    /**
     * 登录并缓存访问令牌与用户 ID。
     *
     * @param baseUrl   New API 服务地址
     * @param username  登录用户名
     * @param password  登录密码
     * @param turnstile Turnstile 校验值，可为空
     */
    public void login(String baseUrl, String username, String password, String turnstile) {
        validateLoginArguments(baseUrl, username, password);

        String tokenCacheKey = tokenKey(baseUrl, username);
        String userIdCacheKey = userIdKey(baseUrl, username);
        if (hasCachedSession(tokenCacheKey, userIdCacheKey)) {
            log.info("{} {} 缓存的 New API 登录凭证仍在有效期内", baseUrl, username);
            return;
        }

        // 两个缓存键必须同时存在，避免只缓存了部分登录上下文。
        // 注意不要顺带清掉 refresh token：登录失败时它可能仍然有效，可留作下次续期。
        clearCachedSession(tokenCacheKey, userIdCacheKey);
        log.info("{} {} 缓存的 New API 登录凭证已失效，重新登录", baseUrl, username);

        NewApiLoginRequest request = new NewApiLoginRequest(username, password);
        NewApiClient.AuthSession session;
        try {
            session = newApiClient.login(baseUrl, request, turnstile);
        } catch (RestClientResponseException ex) {
            throw translateLoginError(baseUrl, username, ex);
        }
        validateLoginResponse(baseUrl, username, session.response());
        cacheSession(baseUrl, username, session);

        log.info("{} {} New API 登录成功，用户 ID: {}",
                baseUrl, username, session.response().data().user().id());
    }

    /**
     * 获取当前用户信息。
     *
     * @param baseUrl  New API 服务地址
     * @param username 登录用户名
     * @param password 登录密码，仅当访问令牌不存在时用于重新登录
     * @return New API 当前用户信息响应
     */
    public NewApiSelfResponse fetchSelf(String baseUrl, String username, String password) {
        return fetchSelf(baseUrl, username, password, "");
    }

    /**
     * 获取当前用户信息。
     *
     * @param baseUrl   New API 服务地址
     * @param username  登录用户名
     * @param password  登录密码，仅当访问令牌不存在时用于重新登录
     * @param turnstile Turnstile 校验值，可为空
     * @return New API 当前用户信息响应
     */
    public NewApiSelfResponse fetchSelf(String baseUrl, String username, String password,
                                        String turnstile) {
        String accessToken = resolveAccessToken(baseUrl, username, password, turnstile);
        NewApiSelfResponse response = newApiClient.fetchSelf(baseUrl, accessToken);
        validateSelfResponse(baseUrl, username, response);

        log.info("{} {} 获取 New API 当前用户信息成功", baseUrl, username);
        return response;
    }

    /**
     * 获取当前用户可用分组。
     *
     * @param baseUrl  New API 服务地址
     * @param username 登录用户名
     * @param password 登录密码，仅当访问令牌不存在时用于重新登录
     * @return New API 分组响应
     */
    public NewApiGroupsResponse fetchGroups(String baseUrl, String username, String password) {
        return fetchGroups(baseUrl, username, password, "");
    }

    /**
     * 获取当前用户可用分组。
     *
     * @param baseUrl   New API 服务地址
     * @param username  登录用户名
     * @param password  登录密码，仅当访问令牌不存在时用于重新登录
     * @param turnstile Turnstile 校验值，可为空
     * @return New API 分组响应
     */
    public NewApiGroupsResponse fetchGroups(String baseUrl, String username, String password,
                                            String turnstile) {
        String accessToken = resolveAccessToken(baseUrl, username, password, turnstile);
        NewApiGroupsResponse response = newApiClient.fetchGroups(baseUrl, accessToken);
        validateGroupsResponse(baseUrl, username, response);

        int groupCount = response.data().size();
        log.info("{} {} 获取 New API 分组成功，共 {} 个", baseUrl, username, groupCount);
        return response;
    }

    /**
     * 分页获取当前用户的密钥列表。
     *
     * @param baseUrl  New API 服务地址
     * @param username 登录用户名
     * @param password 登录密码，仅当访问令牌不存在时用于重新登录
     * @param page     页码，从 1 开始
     * @param size     每页记录数
     * @return New API 密钥列表响应
     */
    public NewApiTokensResponse fetchTokens(String baseUrl, String username, String password,
                                            int page, int size) {
        String accessToken = resolveAccessToken(baseUrl, username, password, "");
        NewApiTokensResponse response = newApiClient.fetchTokens(baseUrl, accessToken, page, size);
        validateTokensResponse(baseUrl, username, response);

        int count = response.data().items() == null ? 0 : response.data().items().size();
        log.info("{} {} 获取 New API 密钥列表成功，page={}, size={}, count={}",
                baseUrl, username, page, size, count);
        return response;
    }

    /**
     * 获取指定密钥的完整明文，用于加密落库与轮换检测。
     *
     * @param baseUrl  New API 服务地址
     * @param username 登录用户名
     * @param password 登录密码，仅当访问令牌不存在时用于重新登录
     * @param tokenId  密钥 ID
     * @return 完整明文密钥
     */
    public String fetchTokenKey(String baseUrl, String username, String password, Long tokenId) {
        String accessToken = resolveAccessToken(baseUrl, username, password, "");
        NewApiTokenKeyResponse response = newApiClient.fetchTokenKey(baseUrl, accessToken, tokenId);
        if (response == null || !response.success() || response.data() == null
                || StrUtil.isEmpty(response.data().key())) {
            throw new IllegalStateException("获取 New API 完整密钥失败: tokenId=" + tokenId);
        }
        return response.data().key();
    }

    /**
     * 把上游登录的 HTTP 错误翻译成可读的业务异常。
     *
     * <p>New API 限制同一账号的并发登录会话数，超限时返回 409 与
     * {@code AUTH_SESSION_LIMIT}。不处理会一路冒泡成 500「服务器内部错误」，
     * 这里转成带处理建议的业务异常，便于直接在界面上提示。</p>
     */
    private BusinessException translateLoginError(String baseUrl, String username,
                                                  RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();
        if (status == 409 && body != null && body.contains("AUTH_SESSION_LIMIT")) {
            log.error("{} {} New API 登录失败：登录会话数已达上游上限", baseUrl, username);
            return new BusinessException(CODE_AUTH_SESSION_LIMIT,
                    "上游账号的登录会话数已达上限，无法建立新的登录会话。请先登录 " + baseUrl
                            + " 退出其他登录会话（或联系上游管理员调整会话上限）后重试。");
        }
        log.error("{} {} New API 登录失败：HTTP {} {}", baseUrl, username, status, body);
        return new BusinessException("UPSTREAM_LOGIN_FAILED",
                "上游 New API 登录失败（HTTP " + status + "），请检查账号密码与平台地址是否正确");
    }

    /**
     * 校验登录参数，避免向上游发送明显无效的请求。
     */
    private void validateLoginArguments(String baseUrl, String username, String password) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("New API 服务地址不能为空");
        }
        if (StrUtil.isBlank(username)) {
            throw new IllegalArgumentException("New API 用户名不能为空");
        }
        if (StrUtil.isBlank(password)) {
            throw new IllegalArgumentException("New API 密码不能为空");
        }
    }

    /**
     * 校验登录响应，保证后续采集可同时取得令牌和用户 ID。
     */
    private void validateLoginResponse(String baseUrl, String username,
                                       NewApiLoginResponse response) {
        if (response == null) {
            log.error("{} {} New API 登录失败：响应为空", baseUrl, username);
            throw new IllegalStateException("New API 登录失败：响应为空");
        }

        if (!response.success()) {
            log.error("{} {} New API 登录失败：message={}", baseUrl, username, response.message());
            throw new IllegalStateException("New API 登录失败：" + response.message());
        }

        if (response.data() == null || StrUtil.isEmpty(response.data().accessToken())) {
            log.error("{} {} New API 登录失败：未返回 access_token", baseUrl, username);
            throw new IllegalStateException("New API 登录失败：未返回 access_token");
        }

        if (response.data().user() == null || response.data().user().id() == null) {
            log.error("{} {} New API 登录失败：未返回 user.id", baseUrl, username);
            throw new IllegalStateException("New API 登录失败：未返回 user.id");
        }
    }

    /**
     * 校验当前用户信息响应。
     */
    private void validateSelfResponse(String baseUrl, String username,
                                      NewApiSelfResponse response) {
        if (response == null) {
            log.error("{} {} 获取 New API 当前用户信息失败：响应为空", baseUrl, username);
            throw new IllegalStateException("获取 New API 当前用户信息失败：响应为空");
        }

        if (!response.success()) {
            log.error("{} {} 获取 New API 当前用户信息失败：message={}",
                    baseUrl, username, response.message());
            throw new IllegalStateException("获取 New API 当前用户信息失败：" + response.message());
        }

        if (response.data() == null) {
            log.error("{} {} 获取 New API 当前用户信息失败：未返回 data", baseUrl, username);
            throw new IllegalStateException("获取 New API 当前用户信息失败：未返回 data");
        }
    }

    /**
     * 校验分组响应。
     */
    private void validateGroupsResponse(String baseUrl, String username,
                                        NewApiGroupsResponse response) {
        if (response == null) {
            log.error("{} {} 获取 New API 分组失败：响应为空", baseUrl, username);
            throw new IllegalStateException("获取 New API 分组失败：响应为空");
        }

        if (!response.success()) {
            log.error("{} {} 获取 New API 分组失败：message={}",
                    baseUrl, username, response.message());
            throw new IllegalStateException("获取 New API 分组失败：" + response.message());
        }

        if (response.data() == null) {
            log.error("{} {} 获取 New API 分组失败：未返回 data", baseUrl, username);
            throw new IllegalStateException("获取 New API 分组失败：未返回 data");
        }
    }

    /**
     * 校验密钥列表响应。
     */
    private void validateTokensResponse(String baseUrl, String username,
                                        NewApiTokensResponse response) {
        if (response == null) {
            log.error("{} {} 获取 New API 密钥列表失败：响应为空", baseUrl, username);
            throw new IllegalStateException("获取 New API 密钥列表失败：响应为空");
        }

        if (!response.success()) {
            log.error("{} {} 获取 New API 密钥列表失败：message={}",
                    baseUrl, username, response.message());
            throw new IllegalStateException("获取 New API 密钥列表失败：" + response.message());
        }

        if (response.data() == null) {
            log.error("{} {} 获取 New API 密钥列表失败：未返回 data", baseUrl, username);
            throw new IllegalStateException("获取 New API 密钥列表失败：未返回 data");
        }
    }

    /**
     * 解析访问令牌：命中缓存直接返回；access token 缺失时优先用 refresh token
     * 续期，续期不可用或失败才重新登录。
     *
     * <p>重新登录会在上游新建一个登录会话，受会话数上限约束；续期只是轮换当前
     * 会话的令牌，不占用新的会话配额。</p>
     */
    private String resolveAccessToken(String baseUrl, String username, String password,
                                      String turnstile) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("New API 服务地址不能为空");
        }
        if (StrUtil.isBlank(username)) {
            throw new IllegalArgumentException("New API 用户名不能为空");
        }

        synchronized (sessionLock(baseUrl, username)) {
            String cacheKey = tokenKey(baseUrl, username);
            String accessToken = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotEmpty(accessToken)) {
                return accessToken;
            }

            if (refreshSession(baseUrl, username)) {
                accessToken = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotEmpty(accessToken)) {
                    return accessToken;
                }
            }

            log.info("{} {} New API token 不存在，先执行登录", baseUrl, username);
            login(baseUrl, username, password, turnstile);

            accessToken = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isEmpty(accessToken)) {
                throw new IllegalStateException("New API 登录后仍无法获取 token: " + baseUrl);
            }
            return accessToken;
        }
    }

    /**
     * 用缓存的 refresh token 续期，避免重新登录占用上游会话配额。
     *
     * @return 是否续期成功；refresh token 不存在、已失效或上游不支持时返回 false
     */
    private boolean refreshSession(String baseUrl, String username) {
        String refreshCacheKey = refreshKey(baseUrl, username);
        String refreshToken = stringRedisTemplate.opsForValue().get(refreshCacheKey);
        if (StrUtil.isEmpty(refreshToken)) {
            return false;
        }

        log.info("{} {} New API access token 已过期，尝试用 refresh token 续期", baseUrl, username);
        try {
            NewApiClient.AuthSession session = newApiClient.refreshAuth(baseUrl, refreshToken);
            validateLoginResponse(baseUrl, username, session.response());
            cacheSession(baseUrl, username, session);
            log.info("{} {} New API access token 续期成功", baseUrl, username);
            return true;
        } catch (RestClientResponseException ex) {
            // 上游明确拒绝（token 失效、被吊销、接口不存在）才丢弃 refresh token；
            // 网络类错误保留凭证，避免把仍然有效的 refresh token 误删。
            log.warn("{} {} New API access token 续期失败：HTTP {}，回退到重新登录",
                    baseUrl, username, ex.getStatusCode().value());
            if (ex.getStatusCode().is4xxClientError()) {
                clearCachedSession(refreshCacheKey);
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("{} {} New API access token 续期失败，回退到重新登录: {}",
                    baseUrl, username, ex.getMessage());
            return false;
        }
    }

    /**
     * 缓存一次登录/续期的结果。refresh token 只在响应带 Set-Cookie 时才会更新。
     */
    private void cacheSession(String baseUrl, String username, NewApiClient.AuthSession session) {
        NewApiLoginResponse.LoginData data = session.response().data();
        Duration accessTtl = resolveTokenTtl(data.accessExpiresAt());
        stringRedisTemplate.opsForValue().set(tokenKey(baseUrl, username), data.accessToken(), accessTtl);
        stringRedisTemplate.opsForValue().set(userIdKey(baseUrl, username),
                String.valueOf(data.user().id()), accessTtl);

        String refreshCacheKey = refreshKey(baseUrl, username);
        if (StrUtil.isNotEmpty(session.refreshToken())) {
            stringRedisTemplate.opsForValue().set(refreshCacheKey, session.refreshToken(),
                    resolveRefreshTtl(data));
        } else {
            // 上游未下发 refresh token（旧版本），清掉可能残留的旧值，避免误用。
            stringRedisTemplate.delete(refreshCacheKey);
        }
    }

    /**
     * refresh token 的缓存时长跟随上游登录会话，取不到时使用兜底值。
     */
    private Duration resolveRefreshTtl(NewApiLoginResponse.LoginData data) {
        Long sessionExpiresAt = data.session() == null ? null : data.session().expiresAt();
        if (sessionExpiresAt == null) {
            return FALLBACK_REFRESH_TTL;
        }
        long expiresInSeconds = sessionExpiresAt - Instant.now().getEpochSecond();
        return Duration.ofSeconds(Math.max(1L, expiresInSeconds - TOKEN_EXPIRY_SKEW_SECONDS));
    }

    /**
     * 同一账号的登录/续期互斥锁。
     */
    private Object sessionLock(String baseUrl, String username) {
        return sessionLocks.computeIfAbsent(baseUrl + "|" + username, key -> new Object());
    }

    /**
     * 根据 access_expires_at 计算缓存时长；未返回过期时间时使用兜底值。
     */
    private Duration resolveTokenTtl(Long accessExpiresAt) {
        if (accessExpiresAt == null) {
            return FALLBACK_TOKEN_TTL;
        }

        long expiresInSeconds = accessExpiresAt - Instant.now().getEpochSecond();
        if (expiresInSeconds <= 0) {
            throw new IllegalStateException("New API 登录失败：access_token 已过期");
        }

        return Duration.ofSeconds(Math.max(1L, expiresInSeconds - TOKEN_EXPIRY_SKEW_SECONDS));
    }

    /**
     * 判断访问令牌和用户 ID 是否同时存在。
     */
    private boolean hasCachedSession(String tokenCacheKey, String userIdCacheKey) {
        String token = stringRedisTemplate.opsForValue().get(tokenCacheKey);
        String userId = stringRedisTemplate.opsForValue().get(userIdCacheKey);
        return StrUtil.isNotEmpty(token) && StrUtil.isNotEmpty(userId);
    }

    /**
     * 清理不完整的登录凭证。
     */
    private void clearCachedSession(String... cacheKeys) {
        for (String cacheKey : cacheKeys) {
            stringRedisTemplate.delete(cacheKey);
        }
    }

    /**
     * 访问令牌缓存键按服务地址和用户名隔离。
     */
    private String tokenKey(String baseUrl, String username) {
        return "newapi:token:" + baseUrl + ":" + username;
    }

    /**
     * 用户 ID 缓存键按服务地址和用户名隔离。
     */
    private String userIdKey(String baseUrl, String username) {
        return "newapi:user-id:" + baseUrl + ":" + username;
    }

    /**
     * refresh token 缓存键按服务地址和用户名隔离。
     */
    private String refreshKey(String baseUrl, String username) {
        return "newapi:refresh-token:" + baseUrl + ":" + username;
    }
}
