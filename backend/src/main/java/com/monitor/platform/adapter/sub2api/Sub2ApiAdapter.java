package com.monitor.platform.adapter.sub2api;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginRequest;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Sub2API 采集适配器。
 *
 * <p>该适配器承载 Sub2API 的采集流程：读取/刷新登录令牌、调用 HTTP 客户端、
 * 校验 Sub2API 业务状态码，并在令牌失效时清理缓存。HTTP 请求细节放在
 * {@link Sub2ApiClient} 中，使适配器只关注采集编排和业务结果判断。</p>
 *
 * <p>当前类保留现有能力，不引入额外上游、数据库落库或标准化流程。</p>
 */
@Component
public class Sub2ApiAdapter {

    private static final Logger log = LoggerFactory.getLogger(Sub2ApiAdapter.class);

    /** Sub2API 业务响应中的成功状态码。 */
    private static final int SUCCESS_CODE = 0;

    /** 登录令牌在 Redis 中的缓存时长。 */
    private static final Duration TOKEN_TTL = Duration.ofDays(1);

    private final Sub2ApiClient sub2ApiClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @param sub2ApiClient       Sub2API HTTP 客户端
     * @param stringRedisTemplate 登录令牌缓存
     */
    public Sub2ApiAdapter(Sub2ApiClient sub2ApiClient,
                          StringRedisTemplate stringRedisTemplate) {
        this.sub2ApiClient = sub2ApiClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 登录并缓存访问令牌。
     *
     * <p>如果 Redis 中已有令牌则直接复用；否则调用 Sub2API 登录接口，
     * 校验响应后写入缓存。</p>
     *
     * @param baseUrl Sub2API 服务地址
     * @param email   账号邮箱
     */

    /**
     * 使用指定密码登录并缓存访问令牌。
     *
     * @param baseUrl  Sub2API 服务地址
     * @param email    账号邮箱
     * @param password 登录密码
     */
    public void login(String baseUrl, String email, String password) {
        String cacheKey = tokenKey(baseUrl, email);

        // 1. 优先复用缓存令牌，避免频繁登录。
        String cachedToken = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotEmpty(cachedToken)) {
            log.info("{} {} 缓存的身份信息仍在有效期内", baseUrl, email);
            return;
        }

        log.info("{} {} 缓存的身份信息已失效，重新登录", baseUrl, email);

        // 2. 网络层已由拦截器重试，这里只发起一次业务登录请求。
        Sub2LoginRequest request = new Sub2LoginRequest(email, password);
        Sub2LoginResponse response = sub2ApiClient.login(baseUrl, request);
        validateLoginResponse(baseUrl, email, response);

        // 3. 登录成功后统一写入缓存，供个人信息和密钥列表接口复用。
        String accessToken = response.data().accessToken();
        stringRedisTemplate.opsForValue().set(cacheKey, accessToken, TOKEN_TTL);

        String username = response.data().user() == null ? email : response.data().user().username();
        log.info("{} {} 登录成功，用户: {}", baseUrl, email, username);
    }

    /**
     * 获取个人信息。
     *
     * @param baseUrl Sub2API 服务地址
     * @param email    账号邮箱
     * @param password 登录密码
     * @return Sub2API 个人信息响应
     */

    public Sub2ProfileResponse fetchProfile(String baseUrl, String email, String password) {
        String accessToken = resolveAccessToken(baseUrl, email, password);

        Sub2ProfileResponse response = sub2ApiClient.fetchProfile(baseUrl, accessToken);
        if (response == null) {
            log.error("{} {} 获取个人信息失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取个人信息失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取个人信息失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());
            clearTokenIfUnauthorized(baseUrl, email, response.code());
            throw new IllegalStateException("获取个人信息失败：" + response.message());
        }

        log.info("{} {} 获取个人信息成功", baseUrl, email);
        return response;
    }

    /**
     * 获取密钥列表。
     *
     * @param baseUrl Sub2API 服务地址
     * @param email    账号邮箱
     * @param password 登录密码
     * @return Sub2API 密钥列表响应
     */

    public Sub2KeysResponse fetchKeys(String baseUrl, String email, String password) {
        String accessToken = resolveAccessToken(baseUrl, email, password);

        Sub2KeysResponse response = sub2ApiClient.fetchKeys(baseUrl, accessToken);
        if (response == null) {
            log.error("{} {} 获取密钥列表失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取密钥列表失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取密钥列表失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());
            clearTokenIfUnauthorized(baseUrl, email, response.code());
            throw new IllegalStateException("获取密钥列表失败：" + response.message());
        }

        int total = response.data() == null ? 0 : response.data().total();
        log.info("{} {} 获取密钥列表成功，共 {} 条", baseUrl, email, total);
        return response;
    }

    /**
     * 获取当前账号可用分组。
     *
     * @param baseUrl Sub2API 服务地址
     * @param email    账号邮箱
     * @param password 登录密码
     * @return Sub2API 可用分组响应
     */

    public Sub2GroupsResponse fetchAvailableGroups(String baseUrl, String email, String password) {
        String accessToken = resolveAccessToken(baseUrl, email, password);

        Sub2GroupsResponse response = sub2ApiClient.fetchAvailableGroups(baseUrl, accessToken);
        if (response == null) {
            log.error("{} {} 获取可用分组失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取可用分组失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取可用分组失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());
            clearTokenIfUnauthorized(baseUrl, email, response.code());
            throw new IllegalStateException("获取可用分组失败：" + response.message());
        }

        int total = response.data() == null ? 0 : response.data().size();
        log.info("{} {} 获取可用分组成功，共 {} 个", baseUrl, email, total);
        return response;
    }
    /**
     * 获取当前账号用量看板统计。
     */
    public Sub2UsageDashboardResponse fetchUsageDashboardStats(String baseUrl, String email, String password) {
        String accessToken = resolveAccessToken(baseUrl, email, password);
        Sub2UsageDashboardResponse response = sub2ApiClient.fetchUsageDashboardStats(baseUrl, accessToken);
        if (response == null) {
            log.error("{} {} 获取用量看板失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取用量看板失败：响应为空");
        }
        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取用量看板失败：code={}, message={}", baseUrl, email, response.code(), response.message());
            clearTokenIfUnauthorized(baseUrl, email, response.code());
            throw new IllegalStateException("获取用量看板失败：" + response.message());
        }
        log.info("{} {} 获取用量看板成功", baseUrl, email);
        return response;
    }

    /**
     * 校验登录响应，保证调用方拿到的令牌可用。
     */
    private void validateLoginResponse(String baseUrl, String email, Sub2LoginResponse response) {
        if (response == null) {
            log.error("{} {} 登录失败：响应为空", baseUrl, email);
            throw new IllegalStateException("登录失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 登录失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());
            throw new IllegalStateException("登录失败：" + response.message());
        }

        if (response.data() == null || StrUtil.isEmpty(response.data().accessToken())) {
            log.error("{} {} 登录失败：未返回 access_token", baseUrl, email);
            throw new IllegalStateException("登录失败：未返回 access_token");
        }
    }

    /**
     * 获取访问令牌；缓存不存在时先登录，再重新读取缓存。
     */
    private String resolveAccessToken(String baseUrl, String email, String password) {
        String token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
        if (StrUtil.isNotEmpty(token)) {
            return token;
        }

        log.info("{} {} token 不存在，先执行登录", baseUrl, email);
        login(baseUrl, email, password);

        token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
        if (StrUtil.isEmpty(token)) {
            throw new IllegalStateException("登录后仍无法获取 token: " + baseUrl);
        }
        return token;
    }

    /**
     * 当 Sub2API 返回认证类业务码时，删除本地令牌，下一次调用会重新登录。
     */
    private void clearTokenIfUnauthorized(String baseUrl, String email, int code) {
        if (code == 401 || code == 403) {
            log.warn("{} {} token 可能已失效，清除缓存", baseUrl, email);
            stringRedisTemplate.delete(tokenKey(baseUrl, email));
        }
    }

    /**
     * 令牌缓存键按“服务地址 + 邮箱”隔离，避免多个账号或环境互相覆盖。
     */
    private String tokenKey(String baseUrl, String email) {
        return "sub2:token:" + baseUrl + ":" + email;
    }
}