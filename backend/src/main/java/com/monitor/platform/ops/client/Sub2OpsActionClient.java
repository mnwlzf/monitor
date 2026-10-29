package com.monitor.platform.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.adapter.sub2api.Sub2AdminApi;
import com.monitor.platform.config.RestClientConfig;
import com.monitor.platform.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Sub2API 运维动作客户端。
 *
 * <p>与只读的 {@code Sub2AdminClient} 分离，所有方法都必须由事故动作计划器调用，
 * 不直接暴露给普通查询工具。</p>
 */
@Component
public class Sub2OpsActionClient {

    private static final String ACCOUNT_PATH = "/api/v1/admin/accounts/";
    private static final String API_KEY_HEADER = "x-api-key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public Sub2OpsActionClient(@Qualifier(RestClientConfig.SUB2_ADMIN_REST_CLIENT) RestClient restClient,
                               ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public AccountState fetchAccount(String baseUrl, String adminKey, long accountId) {
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(baseUrl + ACCOUNT_PATH + accountId)
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "读取账号状态");
        return toState(data);
    }

    public AccountState clearRateLimit(String baseUrl, String adminKey, long accountId) {
        postNoBody(baseUrl, adminKey, accountId, "clear-rate-limit", "清理账号限流");
        return fetchAccount(baseUrl, adminKey, accountId);
    }

    public AccountState clearError(String baseUrl, String adminKey, long accountId) {
        postNoBody(baseUrl, adminKey, accountId, "clear-error", "清理账号错误");
        return fetchAccount(baseUrl, adminKey, accountId);
    }

    public AccountState recoverState(String baseUrl, String adminKey, long accountId) {
        postNoBody(baseUrl, adminKey, accountId, "recover-state", "恢复账号状态");
        return fetchAccount(baseUrl, adminKey, accountId);
    }

    public AccountState refresh(String baseUrl, String adminKey, long accountId) {
        postNoBody(baseUrl, adminKey, accountId, "refresh", "刷新账号凭证");
        return fetchAccount(baseUrl, adminKey, accountId);
    }

    public AccountState setSchedulable(String baseUrl, String adminKey, long accountId, boolean schedulable) {
        Sub2AdminApi.execute(() -> restClient.post()
                .uri(baseUrl + ACCOUNT_PATH + accountId + "/schedulable")
                .header(API_KEY_HEADER, adminKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("schedulable", schedulable))
                .retrieve()
                .body(JsonNode.class), schedulable ? "恢复账号调度" : "暂停账号调度");
        return fetchAccount(baseUrl, adminKey, accountId);
    }

    private void postNoBody(String baseUrl, String adminKey, long accountId, String action, String label) {
        Sub2AdminApi.execute(() -> {
            restClient.post()
                    .uri(baseUrl + ACCOUNT_PATH + accountId + "/" + action)
                    .header(API_KEY_HEADER, adminKey)
                    .retrieve()
                    .toBodilessEntity();
            return objectMapper.createObjectNode();
        }, label);
    }

    private AccountState toState(JsonNode data) {
        if (data == null || data.isNull()) {
            throw BusinessException.of("Sub2API 未返回账号状态");
        }
        JsonNode node = data.path("account");
        if (!node.isObject()) {
            node = data;
        }
        return new AccountState(
                Sub2AdminApi.longOrNull(node, "id"),
                Sub2AdminApi.text(node, "name"),
                Sub2AdminApi.text(node, "platform"),
                Sub2AdminApi.text(node, "status"),
                Sub2AdminApi.bool(node, "schedulable"),
                Sub2AdminApi.text(node, "error_message"),
                Sub2AdminApi.time(node, "rate_limit_reset_at"),
                Sub2AdminApi.time(node, "overload_until"),
                Sub2AdminApi.time(node, "temp_unschedulable_until"),
                Sub2AdminApi.text(node, "temp_unschedulable_reason")
        );
    }

    public record AccountState(
            Long id,
            String name,
            String platform,
            String status,
            Boolean schedulable,
            String errorMessage,
            OffsetDateTime rateLimitResetAt,
            OffsetDateTime overloadUntil,
            OffsetDateTime tempUnschedulableUntil,
            String tempUnschedulableReason
    ) {
    }
}