package com.monitor.platform.bot.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.config.RestClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
 */
@Component
public class Sub2UserAdminClient {

    private static final Logger log = LoggerFactory.getLogger(Sub2UserAdminClient.class);

    private static final String USERS_PATH = "/api/v1/admin/users";
    private static final String USAGE_STATS_PATH = "/api/v1/admin/usage/stats";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final ZoneId ZONE = ZoneId.of(TIMEZONE);
    private static final int MAX_ERROR_LENGTH = 300;
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
        JsonNode data = execute(() -> restClient.get()
                .uri(baseUrl + USERS_PATH + "?search={search}&page=1&page_size={size}",
                        email, SEARCH_PAGE_SIZE)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询平台用户");

        for (JsonNode node : extractArray(data)) {
            String found = text(node, "email");
            if (found != null && found.equalsIgnoreCase(email)) {
                return Optional.of(toUser(node));
            }
        }
        return Optional.empty();
    }

    /** 拉取某用户的密钥列表（已打码）。 */
    public List<Sub2AdminApiKey> fetchApiKeys(String baseUrl, String adminKey, long userId,
                                              int page, int pageSize) {
        JsonNode data = execute(() -> restClient.get()
                .uri(baseUrl + USERS_PATH + "/{id}/api-keys?page={page}&page_size={size}",
                        userId, page, pageSize)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询用户密钥");

        List<Sub2AdminApiKey> result = new ArrayList<>();
        for (JsonNode node : extractArray(data)) {
            result.add(toApiKey(node));
        }
        return result;
    }

    /** 拉取某用户的用量统计；{@code period} 取 today / week / month。 */
    public Sub2AdminUsageStats fetchUsageStats(String baseUrl, String adminKey, long userId, String period) {
        JsonNode data = execute(() -> restClient.get()
                .uri(baseUrl + USAGE_STATS_PATH + "?user_id={id}&period={period}&timezone={tz}",
                        userId, period, TIMEZONE)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "查询用户用量");

        return new Sub2AdminUsageStats(
                longValue(data, "total_requests"),
                longValue(data, "total_input_tokens"),
                longValue(data, "total_output_tokens"),
                longValue(data, "total_cache_creation_tokens"),
                longValue(data, "total_cache_read_tokens"),
                longValue(data, "total_tokens"),
                doubleValue(data, "total_cost"),
                doubleValue(data, "total_actual_cost"),
                doubleValue(data, "average_duration_ms"));
    }

    // ---------------------------------------------------------------- 解析

    private Sub2AdminUser toUser(JsonNode node) {
        return new Sub2AdminUser(
                longOrNull(node, "id"),
                text(node, "email"),
                text(node, "username"),
                text(node, "role"),
                doubleOrNull(node, "balance"),
                doubleOrNull(node, "frozen_balance"),
                doubleOrNull(node, "total_recharged"),
                text(node, "status"),
                intOrNull(node, "concurrency"),
                time(node, "last_active_at"));
    }

    private Sub2AdminApiKey toApiKey(JsonNode node) {
        return new Sub2AdminApiKey(
                longOrNull(node, "id"),
                text(node, "name"),
                maskKey(text(node, "key")),
                text(node, "status"),
                doubleOrNull(node, "quota"),
                doubleOrNull(node, "quota_used"),
                time(node, "expires_at"),
                time(node, "last_used_at"),
                doubleOrNull(node, "usage_5h"),
                doubleOrNull(node, "usage_1d"),
                doubleOrNull(node, "usage_7d"));
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

    // ---------------------------------------------------------------- HTTP

    private JsonNode execute(AdminCall call, String action) {
        try {
            return unwrapData(call.invoke());
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            log.warn("Sub2API 用户接口调用失败: action={}, status={}", action, status.value());
            throw BusinessException.of(action + "失败：上游返回 " + status.value() + "，请检查管理员密钥是否有效");
        } catch (RestClientException ex) {
            String reason = rootMessage(ex);
            log.warn("Sub2API 用户接口调用失败: action={}, reason={}", action, reason);
            throw BusinessException.of(action + "失败：" + reason);
        }
    }

    private JsonNode unwrapData(JsonNode root) {
        if (root == null) {
            throw new IllegalStateException("Sub2API 用户接口返回为空");
        }
        JsonNode codeNode = root.path("code");
        if (codeNode.isNumber() && codeNode.asInt() != 0) {
            throw new IllegalStateException("Sub2API 用户接口返回错误，code=" + codeNode.asInt()
                    + "，message=" + root.path("message").asText(""));
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() ? root : data;
    }

    /** 分页响应是 {@code data.items}；兼容直接返回数组或其它列表字段。 */
    private List<JsonNode> extractArray(JsonNode data) {
        if (data == null || data.isNull()) {
            return List.of();
        }
        if (data.isArray()) {
            List<JsonNode> list = new ArrayList<>(data.size());
            data.forEach(list::add);
            return list;
        }
        for (String key : new String[]{"items", "list", "records", "rows", "results"}) {
            JsonNode candidate = data.path(key);
            if (candidate.isArray()) {
                List<JsonNode> list = new ArrayList<>(candidate.size());
                candidate.forEach(list::add);
                return list;
            }
        }
        return List.of();
    }

    @FunctionalInterface
    private interface AdminCall {
        JsonNode invoke();
    }

    // ---------------------------------------------------------------- 字段

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static long longValue(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? 0L : value;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? null : value.intValue();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double doubleValue(JsonNode node, String field) {
        Double value = doubleOrNull(node, field);
        return value == null ? 0d : value;
    }

    /** 兼容带偏移、瞬时、无偏移（按东八区）三种时间格式。 */
    private static OffsetDateTime time(JsonNode node, String field) {
        String text = text(node, field);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // 继续尝试
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}