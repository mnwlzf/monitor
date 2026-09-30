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
}