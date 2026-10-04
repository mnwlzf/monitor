package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokenKeyResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokensResponse;
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
    @Test
    void shouldGetSelfWithBearerTokenAndDeserializeProfile() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NewApiClient client = new NewApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/user/self"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "aff_code": "2dXI",
                            "aff_count": 1,
                            "aff_history_quota": 250000,
                            "aff_quota": 250000,
                            "discord_id": "",
                            "display_name": "2696775653@qq.com",
                            "email": "3097553108@qq.com",
                            "github_id": "",
                            "group": "default",
                            "has_password": true,
                            "id": 647,
                            "inviter_id": 0,
                            "linux_do_id": "",
                            "oidc_id": "",
                            "permissions": {
                              "admin_permissions": {
                                "audit": {
                                  "read": false
                                },
                                "channel": {
                                  "operate": false,
                                  "read": false,
                                  "secret_view": false,
                                  "sensitive_write": false,
                                  "write": false
                                },
                                "task_plugin": {
                                  "bind": false
                                }
                              },
                              "sidebar_modules": {
                                "admin": false
                              },
                              "sidebar_settings": true
                            },
                            "quota": 1467141,
                            "request_count": 33374,
                            "role": 1,
                            "setting": "{}",
                            "sidebar_modules": "{}",
                            "status": 1,
                            "stripe_customer": "",
                            "telegram_id": "",
                            "used_quota": 70135359,
                            "username": "2696775653@qq.com",
                            "wechat_id": ""
                          },
                          "message": "",
                          "success": true
                        }
                        """, MediaType.APPLICATION_JSON));

        NewApiSelfResponse response = client.fetchSelf(BASE_URL, "access-token");

        assertTrue(response.success());
        assertEquals(647L, response.data().id());
        assertEquals("2dXI", response.data().affCode());
        assertEquals(250000L, response.data().affHistoryQuota());
        assertEquals(1467141L, response.data().quota());
        assertEquals(70135359L, response.data().usedQuota());
        assertFalse(response.data().permissions().adminPermissions().channel().write());
        assertTrue(response.data().permissions().sidebarSettings());
        server.verify();
    }

    @Test
    void shouldGetTokensWithPagingAndDeserializeItems() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NewApiClient client = new NewApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/token/?p=1&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "page": 1,
                            "page_size": 20,
                            "total": 2,
                            "items": [
                              {
                                "id": 2561,
                                "user_id": 647,
                                "key": "1jai***********YABA",
                                "status": 1,
                                "name": "特价",
                                "created_time": 1786254732,
                                "accessed_time": 1788421061,
                                "expired_time": -1,
                                "remain_quota": -12577320,
                                "unlimited_quota": true,
                                "model_limits_enabled": false,
                                "model_limits": "",
                                "allow_ips": "",
                                "used_quota": 12577407,
                                "group": "codex-特价",
                                "cross_group_retry": false,
                                "group_route_config": "",
                                "group_route_sticky": false,
                                "DeletedAt": null,
                                "auto_groups": null
                              }
                            ]
                          },
                          "message": "",
                          "success": true
                        }
                        """, MediaType.APPLICATION_JSON));

        NewApiTokensResponse response = client.fetchTokens(BASE_URL, "access-token", 1, 20);

        assertTrue(response.success());
        assertEquals(2, response.data().total());
        assertEquals(1, response.data().items().size());
        NewApiTokensResponse.Item item = response.data().items().get(0);
        assertEquals(2561L, item.id());
        assertEquals(647L, item.userId());
        assertEquals("特价", item.name());
        assertEquals(1, item.status());
        assertEquals(-1L, item.expiredTime());
        assertEquals(-12577320L, item.remainQuota());
        assertTrue(item.unlimitedQuota());
        assertEquals(12577407L, item.usedQuota());
        assertEquals("codex-特价", item.group());
        server.verify();
    }

    @Test
    void shouldGetFullTokenKeyById() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NewApiClient client = new NewApiClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/token/2561/key"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "key": "1jaifC666M5jtXBGbR5Bie3YxcKnt79q0cY0MCFx5dQhYABA"
                          },
                          "message": "",
                          "success": true
                        }
                        """, MediaType.APPLICATION_JSON));

        NewApiTokenKeyResponse response = client.fetchTokenKey(BASE_URL, "access-token", 2561L);

        assertTrue(response.success());
        assertEquals("1jaifC666M5jtXBGbR5Bie3YxcKnt79q0cY0MCFx5dQhYABA", response.data().key());
        server.verify();
    }
}
