package com.monitor.platform.adapter.newapi;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

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

    private final NewApiClient newApiClient;
    private final StringRedisTemplate stringRedisTemplate;

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
        clearCachedSession(tokenCacheKey, userIdCacheKey);
        log.info("{} {} 缓存的 New API 登录凭证已失效，重新登录", baseUrl, username);

        NewApiLoginRequest request = new NewApiLoginRequest(username, password);
        NewApiLoginResponse response = newApiClient.login(baseUrl, request, turnstile);
        validateLoginResponse(baseUrl, username, response);

        NewApiLoginResponse.LoginData loginData = response.data();
        String accessToken = loginData.accessToken();
        String userId = String.valueOf(loginData.user().id());
        Duration tokenTtl = resolveTokenTtl(loginData.accessExpiresAt());

        stringRedisTemplate.opsForValue().set(tokenCacheKey, accessToken, tokenTtl);
        stringRedisTemplate.opsForValue().set(userIdCacheKey, userId, tokenTtl);

        log.info("{} {} New API 登录成功，用户 ID: {}", baseUrl, username, userId);
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
     * 解析访问令牌；缓存不存在时先登录，再重新读取缓存。
     */
    private String resolveAccessToken(String baseUrl, String username, String password,
                                      String turnstile) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("New API 服务地址不能为空");
        }
        if (StrUtil.isBlank(username)) {
            throw new IllegalArgumentException("New API 用户名不能为空");
        }

        String cacheKey = tokenKey(baseUrl, username);
        String accessToken = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotEmpty(accessToken)) {
            return accessToken;
        }

        log.info("{} {} New API token 不存在，先执行登录", baseUrl, username);
        login(baseUrl, username, password, turnstile);

        accessToken = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isEmpty(accessToken)) {
            throw new IllegalStateException("New API 登录后仍无法获取 token: " + baseUrl);
        }
        return accessToken;
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
    private void clearCachedSession(String tokenCacheKey, String userIdCacheKey) {
        stringRedisTemplate.delete(tokenCacheKey);
        stringRedisTemplate.delete(userIdCacheKey);
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
}