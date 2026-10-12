package com.monitor.platform.bot.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.adapter.sub2api.Sub2AdminApi;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.config.RestClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sub2API 管理端「平台用户」只读接口客户端。
 *
 * <p>认证方式与号池客户端一致：请求头 {@code x-api-key: <管理员密钥>}，
 * 不需要登录接口或 Turnstile。全部是只读查询。</p>
 *
 * <p>对应 Sub2API 源码里的路由（见 {@code internal/server/routes/admin.go}）：</p>
 * <ul>
 *     <li>{@code GET /api/v1/admin/users?search=} —— 按邮箱/用户名搜索用户；</li>
 *     <li>{@code GET /api/v1/admin/users/:id/api-keys} —— 某用户的密钥列表；</li>
 *     <li>{@code GET /api/v1/admin/usage/stats?user_id=&period=} —— 某用户的用量统计。</li>
 * </ul>
 *
 * <p><strong>刻意不用</strong> {@code GET /admin/users/:id/usage}：Sub2API 里它是 mock，
 * 永远返回全 0（见 {@code admin_user.go} 的 GetUserUsageStats）。</p>
 *
 * <p>响应信封与字段解析统一走 {@link Sub2AdminApi}。</p>
 */
@Component
public class Sub2UserAdminClient {

    private static final String USERS_PATH = "/api/v1/admin/users";
    private static final String USAGE_STATS_PATH = "/api/v1/admin/usage/stats";
    private static final String API_KEY_HEADER = "x-api-key";
    /** 搜索用户时返回的候选条数上限。 */
    private static final int SEARCH_PAGE_SIZE = 20;

    private final RestClient restClient;

    public Sub2UserAdminClient(@Qualifier(RestClientConfig.SUB2_ADMIN_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 按邮箱精确查找平台用户。
     *
     * <p>上游只支持关键字模糊搜索，这里再按邮箱精确比对一次，避免搜到相似邮箱。</p>
     */
    public Optional<Sub2AdminUser> findUserByEmail(String baseUrl, String adminKey, String email) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + USERS_PATH + "?search={search}&page=1&page_size={size}",
                        email, SEARCH_PAGE_SIZE)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询平台用户");

        for (JsonNode node : Sub2AdminApi.extractArray(data)) {
            String found = Sub2AdminApi.text(node, "email");
            if (found != null && found.equalsIgnoreCase(email)) {
                return Optional.of(toUser(node));
            }
        }
        return Optional.empty();
    }

    /** 拉取某用户的密钥列表（已打码）。 */
    public List<Sub2AdminApiKey> fetchApiKeys(String baseUrl, String adminKey, long userId,
                                              int page, int pageSize) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + USERS_PATH + "/{id}/api-keys?page={page}&page_size={size}",
                        userId, page, pageSize)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询用户密钥");

        List<Sub2AdminApiKey> result = new ArrayList<>();
        for (JsonNode node : Sub2AdminApi.extractArray(data)) {
            result.add(toApiKey(node));
        }
        return result;
    }

    /** 拉取某用户的用量统计；{@code period} 取 today / week / month。 */
    public Sub2AdminUsageStats fetchUsageStats(String baseUrl, String adminKey, long userId, String period) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + USAGE_STATS_PATH + "?user_id={id}&period={period}&timezone={tz}",
                        userId, period, BusinessTime.ZONE_ID)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询用户用量");

        return new Sub2AdminUsageStats(
                Sub2AdminApi.longValue(data, "total_requests"),
                Sub2AdminApi.longValue(data, "total_input_tokens"),
                Sub2AdminApi.longValue(data, "total_output_tokens"),
                Sub2AdminApi.longValue(data, "total_cache_creation_tokens"),
                Sub2AdminApi.longValue(data, "total_cache_read_tokens"),
                Sub2AdminApi.longValue(data, "total_tokens"),
                Sub2AdminApi.doubleValue(data, "total_cost"),
                Sub2AdminApi.doubleValue(data, "total_actual_cost"),
                Sub2AdminApi.doubleValue(data, "average_duration_ms"));
    }

    // ---------------------------------------------------------------- 解析

    private Sub2AdminUser toUser(JsonNode node) {
        return new Sub2AdminUser(
                Sub2AdminApi.longOrNull(node, "id"),
                Sub2AdminApi.text(node, "email"),
                Sub2AdminApi.text(node, "username"),
                Sub2AdminApi.text(node, "role"),
                Sub2AdminApi.doubleOrNull(node, "balance"),
                Sub2AdminApi.doubleOrNull(node, "frozen_balance"),
                Sub2AdminApi.doubleOrNull(node, "total_recharged"),
                Sub2AdminApi.text(node, "status"),
                Sub2AdminApi.intOrNull(node, "concurrency"),
                Sub2AdminApi.time(node, "last_active_at"));
    }

    private Sub2AdminApiKey toApiKey(JsonNode node) {
        return new Sub2AdminApiKey(
                Sub2AdminApi.longOrNull(node, "id"),
                Sub2AdminApi.text(node, "name"),
                maskKey(Sub2AdminApi.text(node, "key")),
                Sub2AdminApi.text(node, "status"),
                Sub2AdminApi.doubleOrNull(node, "quota"),
                Sub2AdminApi.doubleOrNull(node, "quota_used"),
                Sub2AdminApi.time(node, "expires_at"),
                Sub2AdminApi.time(node, "last_used_at"),
                Sub2AdminApi.doubleOrNull(node, "usage_5h"),
                Sub2AdminApi.doubleOrNull(node, "usage_1d"),
                Sub2AdminApi.doubleOrNull(node, "usage_7d"));
    }

    /**
     * 打码密钥：只留前 6 位与后 4 位。
     *
     * <p>上游会把明文密钥一起返回，机器人这边<strong>绝不能</strong>把明文发出去 ——
     * 用户在网页上能自己看，QQ 里只用于辨认是哪一把。</p>
     */
    static String maskKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        if (value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }
}
