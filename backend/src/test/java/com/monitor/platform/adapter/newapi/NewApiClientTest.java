package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * NewApiClient 请求契约测试。
 */
class NewApiClientTest {

    private static final String BASE_URL = "https://yunmian.tech";
    private static final String USERNAME = "user@example.com";

    @Test
    void shouldPostLoginRequestAndDeserializeResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NewApiClient client = new NewApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/user/login?turnstile="))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.password").value("password"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "access_expires_at": 1790732536,
                            "access_token": "access-token",
                            "token_type": "Bearer",
                            "user": {
                              "id": 647,
                              "username": "user@example.com"
                            }
                          },
                          "message": "",
                          "success": true
                        }
                        """, MediaType.APPLICATION_JSON));

        NewApiLoginResponse response = client.login(
                BASE_URL,
                new NewApiLoginRequest(USERNAME, "password")
        );

        assertTrue(response.success());
        assertEquals("access-token", response.data().accessToken());
        assertEquals(647L, response.data().user().id());
        server.verify();
    }

    @Test
    void shouldGetGroupsWithBearerTokenAndDeserializeDynamicKeys() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NewApiClient client = new NewApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/user/self/groups"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "codex-特价": {
                              "base_ratio": 0.12,
                              "desc": "特价分组",
                              "order": 10,
                              "ratio": 0.12,
                              "schedule_active": false,
                              "schedule_enabled": false
                            },
                            "国产-旗舰": {
                              "base_ratio": 0.7,
                              "desc": "国模系列",
                              "order": 24,
                              "ratio": 0.7,
                              "schedule_active": false,
                              "schedule_enabled": false
                            }
                          },
                          "message": "",
                          "success": true
                        }
                        """, MediaType.APPLICATION_JSON));

        NewApiGroupsResponse response = client.fetchGroups(BASE_URL, "access-token");

        assertTrue(response.success());
        assertEquals(2, response.data().size());
        assertEquals(0.12, response.data().get("codex-特价").baseRatio(), 0.0001);
        assertEquals(10, response.data().get("codex-特价").order());
        assertFalse(response.data().get("codex-特价").scheduleActive());
        assertEquals(0.7, response.data().get("国产-旗舰").ratio(), 0.0001);
        server.verify();
    }
}