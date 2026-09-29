package com.monitor.platform.collection.service.impl;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.collection.dto.Sub2KeysResponse;
import com.monitor.platform.collection.dto.Sub2LoginRequest;
import com.monitor.platform.collection.dto.Sub2LoginResponse;
import com.monitor.platform.collection.dto.Sub2ProfileResponse;
import com.monitor.platform.collection.service.Sub2CollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Service
public class Sub2CollectServiceImpl implements Sub2CollectService {

    private static final Logger log = LoggerFactory.getLogger(Sub2CollectServiceImpl.class);

    /**  */
    private static final int SUCCESS_CODE = 0;

    private static final Duration TOKEN_TTL = Duration.ofDays(1);


    private final RestClient restClient;
    private final StringRedisTemplate stringRedisTemplate;

    public Sub2CollectServiceImpl(RestClient restClient,
                                  StringRedisTemplate stringRedisTemplate) {
        this.restClient = restClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void login(String baseUrl, String email) {
        String cacheKey = tokenKey(baseUrl, email);

        // 1. 读缓存
        String token = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotEmpty(token)) {
            log.info("{} {} 缓存的身份信息仍在有效期内", baseUrl, email);
            return;
        }

        log.info("{} {} 缓存的身份信息已失效，重新登录", baseUrl, email);

        // 2. 发登录请求（网络层已由拦截器重试，这里只发一次）
        Sub2LoginRequest req = new Sub2LoginRequest(email, "test");
        Sub2LoginResponse response = restClient.post()
                .uri(baseUrl + "/api/v1/auth/login")
                .body(req)
                .retrieve()
                .body(Sub2LoginResponse.class);

        // 3. 业务判断
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

        // 4. 写缓存
        String accessToken = response.data().accessToken();
        stringRedisTemplate.opsForValue().set(cacheKey, accessToken, TOKEN_TTL);
        log.info("{} {} 登录成功，用户: {}", baseUrl, email, response.data().user().username());
    }

    @Override
    public Sub2ProfileResponse fetchProfile(String baseUrl, String email) {
        // 1. 取 token，没有就先登录
        String token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
        if (StrUtil.isEmpty(token)) {
            log.info("{} {} token 不存在，先执行登录", baseUrl, email);
            login(baseUrl, email);
            token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
            if (StrUtil.isEmpty(token)) {
                throw new IllegalStateException("登录后仍无法获取 token: " + baseUrl);
            }
        }

        // 2. 发请求（网络层由拦截器重试）
        String accessToken = token;
        Sub2ProfileResponse response = restClient.get()
                .uri(baseUrl + "/api/v1/auth/me?timezone={tz}", "Asia/Shanghai")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2ProfileResponse.class);

        // 3. 业务判断（自行决定怎么处理）
        if (response == null) {
            log.error("{} {} 获取个人信息失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取个人信息失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取个人信息失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());

            // token 可能过期，清掉缓存，下次调用会重新登录
            if (response.code() == 401 || response.code() == 403) {
                log.warn("{} {} token 可能已失效，清除缓存", baseUrl, email);
                stringRedisTemplate.delete(tokenKey(baseUrl, email));
            }
            throw new IllegalStateException("获取个人信息失败：" + response.message());
        }
        log.info("{} {} 登录成功: {}", baseUrl, email, response);
        return response;
    }


    @Override
    public Sub2KeysResponse fetchKeys(String baseUrl, String email) {
        // 1. 取 token，没有就先登录（复用 fetchProfile 的逻辑）
        String token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
        if (StrUtil.isEmpty(token)) {
            log.info("{} {} token 不存在，先执行登录", baseUrl, email);
            login(baseUrl, email);
            token = stringRedisTemplate.opsForValue().get(tokenKey(baseUrl, email));
            if (StrUtil.isEmpty(token)) {
                throw new IllegalStateException("登录后仍无法获取 token: " + baseUrl);
            }
        }

        // 2. 发请求（网络层由拦截器重试）
        String accessToken = token;
        Sub2KeysResponse response = restClient.get()
                .uri(baseUrl + "/api/v1/keys?page={page}&page_size={size}&sort_by={sortBy}&sort_order={order}&timezone={tz}",
                        1, 40, "created_at", "desc", "Asia/Shanghai")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Sub2KeysResponse.class);

        // 3. 业务判断
        if (response == null) {
            log.error("{} {} 获取密钥列表失败：响应为空", baseUrl, email);
            throw new IllegalStateException("获取密钥列表失败：响应为空");
        }

        if (response.code() != SUCCESS_CODE) {
            log.error("{} {} 获取密钥列表失败：code={}, message={}",
                    baseUrl, email, response.code(), response.message());

            if (response.code() == 401 || response.code() == 403) {
                log.warn("{} {} token 可能已失效，清除缓存", baseUrl, email);
                stringRedisTemplate.delete(tokenKey(baseUrl, email));
            }
            throw new IllegalStateException("获取密钥列表失败：" + response.message());
        }

        log.info("{} {} 获取密钥列表成功，共 {} 条", baseUrl, email,
                response.data() == null ? 0 : response.data().total());
        return response;
    }
    private String tokenKey(String baseUrl, String email) {
        return "sub2:token:" + baseUrl + ":" + email;
    }
}