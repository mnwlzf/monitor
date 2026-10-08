package com.monitor.platform.pool.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub2API 管理员只读接口客户端。
 *
 * <p>认证方式为请求头 {@code x-api-key: <管理员密钥>}，与普通用户登录令牌不同，
 * 不需要经过登录接口或 Turnstile 校验。该客户端<strong>只调用只读接口</strong>，
 * 绝不触发任何写操作（如清除错误、修改可调度状态）。</p>
 */
@Component
public class Sub2AdminClient {

    private static final Logger log = LoggerFactory.getLogger(Sub2AdminClient.class);

    private static final String ADMIN_ACCOUNTS_PATH = "/api/v1/admin/accounts";
    private static final String ADMIN_ACCOUNTS_DATA_PATH = "/api/v1/admin/accounts/data";
    private static final String ADMIN_USAGE_PATH = "/api/v1/admin/usage";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String[] ARRAY_KEYS = {"items", "list", "records", "rows", "accounts", "results", "data"};

    private final RestClient restClient;

    public Sub2AdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 拉取号池账号列表。
     */
    public List<Sub2AdminAccount> fetchAccounts(String baseUrl, String adminKey, int page, int pageSize) {
        JsonNode root = restClient.get()
                .uri(baseUrl + ADMIN_ACCOUNTS_PATH + "?page={page}&page_size={size}", page, pageSize)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class);
        List<Sub2AdminAccount> result = new ArrayList<>();
        for (JsonNode node : extractArray(unwrapData(root))) {
            result.add(toAccount(node));
        }
        return result;
    }

    /**
     * 拉取号池账号的明文凭证（管理员导出接口）。
     *
     * <p>返回值仅在内存中用于与本地密钥哈希比对，调用方不得落库或写日志。</p>
     */
    public List<Sub2AdminAccountCredential> fetchAccountCredentials(String baseUrl, String adminKey) {
        JsonNode root = restClient.get()
                .uri(baseUrl + ADMIN_ACCOUNTS_DATA_PATH)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class);
        List<Sub2AdminAccountCredential> result = new ArrayList<>();
        for (JsonNode node : extractArray(unwrapData(root))) {
            String name = text(node, "name");
            String apiKey = extractApiKey(node);
            if (name != null && apiKey != null && !apiKey.isBlank()) {
                result.add(new Sub2AdminAccountCredential(name, apiKey));
            }
        }
        return result;
    }

    /**
     * 拉取指定号池账号在时间窗内的逐请求明细。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     */
    public List<Sub2UsageLog> fetchUsage(String baseUrl, String adminKey, long accountId,
                                         LocalDate start, LocalDate end, int page, int pageSize) {
        JsonNode root = restClient.get()
                .uri(baseUrl + ADMIN_USAGE_PATH
                                + "?account_id={accountId}&start_date={start}&end_date={end}"
                                + "&sort_by=created_at&sort_order=desc&page={page}&page_size={size}",
                        accountId, start.toString(), end.toString(), page, pageSize)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class);
        List<Sub2UsageLog> result = new ArrayList<>();
        for (JsonNode node : extractArray(unwrapData(root))) {
            Sub2UsageLog entry = toUsageLog(node);
            if (entry.requestId() != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 解析响应外层信封，返回业务数据节点。
     */
    private JsonNode unwrapData(JsonNode root) {
        if (root == null) {
            throw new IllegalStateException("Sub2API 管理员接口返回为空");
        }
        JsonNode codeNode = root.path("code");
        if (codeNode.isNumber() && codeNode.asInt() != 0) {
            throw new IllegalStateException("Sub2API 管理员接口返回错误，code=" + codeNode.asInt()
                    + "，message=" + root.path("message").asText(""));
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() ? root : data;
    }

    /**
     * 从数据节点中提取数组，兼容多种分页/列表结构。
     */
    private List<JsonNode> extractArray(JsonNode data) {
        if (data == null || data.isNull()) {
            return List.of();
        }
        if (data.isArray()) {
            List<JsonNode> list = new ArrayList<>(data.size());
            data.forEach(list::add);
            return list;
        }
        for (String key : ARRAY_KEYS) {
            JsonNode candidate = data.path(key);
            if (candidate.isArray()) {
                List<JsonNode> list = new ArrayList<>(candidate.size());
                candidate.forEach(list::add);
                return list;
            }
        }
        return List.of();
    }

    private Sub2AdminAccount toAccount(JsonNode node) {
        return new Sub2AdminAccount(
                longValue(node, "id"),
                text(node, "name"),
                text(node, "platform"),
                text(node, "account_type"),
                text(node, "status"),
                bool(node, "schedulable"),
                text(node, "error_message"),
                time(node, "rate_limited_at"),
                time(node, "rate_limit_reset_at"),
                time(node, "overload_until"),
                time(node, "temp_unschedulable_until"),
                text(node, "temp_unschedulable_reason"),
                intValue(node, "concurrency"),
                intValue(node, "priority"),
                decimal(node, "rate_multiplier"),
                time(node, "last_used_at")
        );
    }

    private Sub2UsageLog toUsageLog(JsonNode node) {
        return new Sub2UsageLog(
                text(node, "request_id"),
                longValue(node, "api_key_id"),
                text(node, "model"),
                longValue(node, "channel_id"),
                text(node, "endpoint"),
                bool(node, "stream"),
                time(node, "created_at"),
                intValue(node, "first_token_ms"),
                intValue(node, "duration_ms"),
                longValue(node, "input_tokens"),
                longValue(node, "output_tokens"),
                longValue(node, "cache_read_tokens"),
                longValue(node, "cache_creation_tokens"),
                decimal(node, "total_cost"),
                decimal(node, "actual_cost")
        );
    }

    /**
     * 从账号节点中提取明文 API Key，兼容 credentials 下多种字段名。
     */
    private String extractApiKey(JsonNode node) {
        JsonNode credentials = node.path("credentials");
        if (!credentials.isObject()) {
            credentials = node;
        }
        for (String key : new String[]{"api_key", "apiKey", "key", "apikey", "secret"}) {
            JsonNode value = credentials.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Long longValue(JsonNode node, String field) {
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

    private Integer intValue(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : value.intValue();
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析时间字段，兼容带偏移、无偏移（按东八区）和瞬时三种格式。
     */
    private OffsetDateTime time(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            log.debug("无法解析 Sub2API 时间字段 {}={}", field, text);
            return null;
        }
    }
}