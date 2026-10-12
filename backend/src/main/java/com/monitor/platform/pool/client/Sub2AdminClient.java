package com.monitor.platform.pool.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.adapter.sub2api.Sub2AdminApi;
import com.monitor.platform.config.RestClientConfig;
import com.monitor.platform.pool.PoolAccountTypes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub2API 管理员只读接口客户端。
 *
 * <p>认证方式为请求头 {@code x-api-key: <管理员密钥>}，与普通用户登录令牌不同，
 * 不需要经过登录接口或 Turnstile 校验。该客户端<strong>只调用只读接口</strong>，
 * 绝不触发任何写操作（如清除错误、修改可调度状态）。</p>
 *
 * <p>管理员接口响应体大、耗时长（全量号池账号一次可达数百 KB、数十秒），
 * 因此使用专用的 {@code sub2AdminRestClient}（更长的读取超时）。</p>
 *
 * <p>响应信封与字段解析统一走 {@link Sub2AdminApi}。</p>
 */
@Component
public class Sub2AdminClient {

    private static final String ADMIN_ACCOUNTS_PATH = "/api/v1/admin/accounts";
    private static final String ADMIN_ACCOUNTS_DATA_PATH = "/api/v1/admin/accounts/data";
    private static final String ADMIN_USAGE_PATH = "/api/v1/admin/usage";
    private static final String API_KEY_HEADER = "x-api-key";
    /** 只拉取 API Key 类型的号池账号，其他类型（oauth 等）不参与监控。 */
    private static final String ACCOUNT_TYPE_FILTER = PoolAccountTypes.API_KEY;

    private final RestClient restClient;

    /**
     * @param restClient Sub2API 管理员接口专用客户端（读取超时更长）
     */
    public Sub2AdminClient(@Qualifier(RestClientConfig.SUB2_ADMIN_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 拉取一页 API Key 类型的号池账号。
     *
     * <p>上游支持 {@code type=apikey} 过滤，加了之后只需 1 页就能拿全，
     * 不必再翻完整个号池（含大量无关的 oauth 账号）。</p>
     */
    public Sub2AdminAccountPage fetchAccounts(String baseUrl, String adminKey, int page, int pageSize) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + ADMIN_ACCOUNTS_PATH + "?page={page}&page_size={size}&type={type}",
                        page, pageSize, ACCOUNT_TYPE_FILTER)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "获取号池账号列表");

        List<Sub2AdminAccount> items = new ArrayList<>();
        for (JsonNode node : Sub2AdminApi.extractArray(data)) {
            items.add(toAccount(node));
        }

        int currentPage = Sub2AdminApi.intOr(data, "page", page);
        int size = Sub2AdminApi.intOr(data, "page_size", pageSize);
        int total = Sub2AdminApi.intOr(data, "total", items.size());
        int pages = Sub2AdminApi.intOr(data, "pages", items.isEmpty() ? 0 : 1);
        return new Sub2AdminAccountPage(items, currentPage, size, total, pages);
    }

    /**
     * 拉取号池账号的明文凭证（管理员导出接口）。
     *
     * <p>返回值仅在内存中用于与本地密钥哈希比对，调用方不得落库或写日志。
     * 同样带 {@code type=apikey} 过滤：不加过滤时上游要把全量账号（含 oauth）
     * 的凭证都解出来，耗时可达数分钟甚至超时。</p>
     */
    public List<Sub2AdminAccountCredential> fetchAccountCredentials(String baseUrl, String adminKey) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + ADMIN_ACCOUNTS_DATA_PATH + "?type={type}", ACCOUNT_TYPE_FILTER)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "获取号池账号凭证");

        List<Sub2AdminAccountCredential> result = new ArrayList<>();
        for (JsonNode node : Sub2AdminApi.extractArray(data)) {
            String name = Sub2AdminApi.text(node, "name");
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
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + ADMIN_USAGE_PATH
                                + "?account_id={accountId}&start_date={start}&end_date={end}"
                                + "&sort_by=created_at&sort_order=desc&page={page}&page_size={size}",
                        accountId, start.toString(), end.toString(), page, pageSize)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "获取号池用量明细");

        List<Sub2UsageLog> result = new ArrayList<>();
        for (JsonNode node : Sub2AdminApi.extractArray(data)) {
            Sub2UsageLog entry = toUsageLog(node);
            if (entry.requestId() != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private Sub2AdminAccount toAccount(JsonNode node) {
        return new Sub2AdminAccount(
                Sub2AdminApi.longOrNull(node, "id"),
                Sub2AdminApi.text(node, "name"),
                Sub2AdminApi.text(node, "platform"),
                // 上游账号类型字段名是 type（如 apikey / oauth），兼容 account_type
                Sub2AdminApi.firstNonBlank(Sub2AdminApi.text(node, "type"), Sub2AdminApi.text(node, "account_type")),
                Sub2AdminApi.text(node, "status"),
                Sub2AdminApi.bool(node, "schedulable"),
                Sub2AdminApi.text(node, "error_message"),
                Sub2AdminApi.time(node, "rate_limited_at"),
                Sub2AdminApi.time(node, "rate_limit_reset_at"),
                Sub2AdminApi.time(node, "overload_until"),
                Sub2AdminApi.time(node, "temp_unschedulable_until"),
                Sub2AdminApi.text(node, "temp_unschedulable_reason"),
                Sub2AdminApi.intOrNull(node, "concurrency"),
                Sub2AdminApi.intOrNull(node, "priority"),
                Sub2AdminApi.decimal(node, "rate_multiplier"),
                Sub2AdminApi.time(node, "last_used_at")
        );
    }

    private Sub2UsageLog toUsageLog(JsonNode node) {
        return new Sub2UsageLog(
                Sub2AdminApi.text(node, "request_id"),
                Sub2AdminApi.longOrNull(node, "api_key_id"),
                Sub2AdminApi.text(node, "model"),
                Sub2AdminApi.longOrNull(node, "channel_id"),
                Sub2AdminApi.text(node, "endpoint"),
                Sub2AdminApi.bool(node, "stream"),
                Sub2AdminApi.time(node, "created_at"),
                Sub2AdminApi.intOrNull(node, "first_token_ms"),
                Sub2AdminApi.intOrNull(node, "duration_ms"),
                Sub2AdminApi.longOrNull(node, "input_tokens"),
                Sub2AdminApi.longOrNull(node, "output_tokens"),
                Sub2AdminApi.longOrNull(node, "cache_read_tokens"),
                Sub2AdminApi.longOrNull(node, "cache_creation_tokens"),
                Sub2AdminApi.decimal(node, "total_cost"),
                Sub2AdminApi.decimal(node, "actual_cost")
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
}
