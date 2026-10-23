package com.monitor.platform.bot.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.adapter.sub2api.Sub2AdminApi;
import com.monitor.platform.config.RestClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;

/**
 * Sub2API 管理端 Ops 概览只读客户端。
 *
 * <p>用于多实例统一诊断，不要求每个实例额外配置数据库连接。</p>
 */
@Component
public class Sub2OpsAdminClient {

    private static final String OVERVIEW_PATH = "/api/v1/admin/ops/dashboard/overview";
    private static final String API_KEY_HEADER = "x-api-key";

    private final RestClient restClient;

    public Sub2OpsAdminClient(@Qualifier(RestClientConfig.SUB2_ADMIN_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    public Sub2OpsOverview fetchOverview(String baseUrl, String adminKey,
                                         OffsetDateTime from, OffsetDateTime to) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        JsonNode data = Sub2AdminApi.execute(() -> restClient.get()
                .uri(base + OVERVIEW_PATH + "?start_time={start}&end_time={end}",
                        from.toString(), to.toString())
                .header(API_KEY_HEADER, adminKey)
                .retrieve()
                .body(JsonNode.class), "获取实例错误概览");
        return new Sub2OpsOverview(
                Sub2AdminApi.intOr(data, "health_score", -1),
                Sub2AdminApi.longValue(data, "request_count_total"),
                Sub2AdminApi.longValue(data, "error_count_total"),
                Sub2AdminApi.longValue(data, "error_count_sla"),
                Sub2AdminApi.longValue(data, "business_limited_count"),
                Sub2AdminApi.longValue(data, "upstream_429_count"),
                Sub2AdminApi.longValue(data, "upstream_529_count"),
                Sub2AdminApi.longValue(data, "upstream_error_count_excl_429_529"),
                Sub2AdminApi.doubleValue(data, "error_rate"),
                Sub2AdminApi.doubleValue(data, "upstream_error_rate"),
                Sub2AdminApi.doubleValue(data, "sla")
        );
    }
}
