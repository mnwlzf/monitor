package com.monitor.platform.adapter.sub2api;

import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Sub2ApiClient 可用分组请求契约测试。
 */
class Sub2ApiClientTest {

    private static final String BASE_URL = "https://codex.trovebox.online";

    @Test
    void shouldGetAvailableGroupsWithBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2ApiClient client = new Sub2ApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/groups/available?timezone=Asia%2FShanghai"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": [
                            {
                              "id": 12,
                              "name": "稳定分组(动态倍率)",
                              "description": "采用plus+pro混合池",
                              "platform": "openai",
                              "rate_multiplier": 0.16,
                              "is_exclusive": false,
                              "status": "active",
                              "subscription_type": "standard",
                              "allow_image_generation": true,
                              "fallback_group_id_on_invalid_request": null,
                              "reasoning_effort_mappings": [],
                              "created_at": "2026-05-29T15:06:48.859031+08:00",
                              "updated_at": "2026-09-05T19:55:32.951917+08:00"
                            },
                            {
                              "id": 19,
                              "name": "Claude Kiro",
                              "description": "",
                              "platform": "anthropic",
                              "rate_multiplier": 0.1,
                              "is_exclusive": false,
                              "status": "active",
                              "subscription_type": "standard",
                              "created_at": "2026-06-04T16:29:17.00894+08:00",
                              "updated_at": "2026-09-29T11:33:27.617217+08:00"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Sub2GroupsResponse response = client.fetchAvailableGroups(BASE_URL, "access-token");

        assertTrue(response.code() == 0);
        assertEquals("success", response.message());
        assertEquals(2, response.data().size());
        assertEquals(12L, response.data().get(0).id());
        assertEquals("稳定分组(动态倍率)", response.data().get(0).name());
        assertEquals(0.16, response.data().get(0).rateMultiplier(), 0.0001);
        assertEquals(19L, response.data().get(1).id());
        server.verify();
    }
    @Test
    void shouldGetUsageDashboardStatsWithBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2ApiClient client = new Sub2ApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/usage/dashboard/stats?timezone=Asia%2FShanghai"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "total_api_keys": 3,
                            "active_api_keys": 3,
                            "total_requests": 3303,
                            "total_tokens": 429465577,
                            "total_cost": 318.56258714,
                            "total_actual_cost": 54.6197386304,
                            "today_requests": 516,
                            "today_tokens": 186967585,
                            "today_actual_cost": 7.09391364,
                            "average_duration_ms": 27689.477141992127,
                            "rpm": 0,
                            "tpm": 244,
                            "by_platform": [
                              {
                                "platform": "openai",
                                "total_requests": 2718,
                                "total_tokens": 238935126,
                                "total_actual_cost": 47.2272613104,
                                "today_requests": 0,
                                "today_tokens": 0,
                                "today_actual_cost": 0
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchUsageDashboardStats(BASE_URL, "access-token");

        assertTrue(response.code() == 0);
        assertEquals(3, response.data().totalApiKeys());
        assertEquals(3303L, response.data().totalRequests());
        assertEquals(429465577L, response.data().totalTokens());
        assertEquals(1, response.data().byPlatform().size());
        server.verify();
    }

}
