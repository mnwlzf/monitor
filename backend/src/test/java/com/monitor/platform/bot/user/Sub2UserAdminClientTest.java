package com.monitor.platform.bot.user;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Sub2UserAdminClientTest {

    private static final String BASE_URL = "https://sub2.example.com";

    @Test
    void shouldSearchUsersWithAdminKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2UserAdminClient client = new Sub2UserAdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/users?search=alice&page=1&page_size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "admin-key"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "items": [
                              {
                                "id": 7,
                                "email": "alice@example.com",
                                "username": "alice",
                                "role": "user",
                                "balance": 10.5,
                                "frozen_balance": 0,
                                "total_recharged": 100,
                                "status": "active"
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Sub2AdminUser> users = client.searchUsers(BASE_URL, "admin-key", "alice", 20);

        assertEquals(1, users.size());
        assertEquals(7L, users.get(0).id());
        assertEquals("alice@example.com", users.get(0).email());
        assertEquals(10.5, users.get(0).balance());
        server.verify();
    }

    @Test
    void shouldFindUserByNumericId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2UserAdminClient client = new Sub2UserAdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/users/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "admin-key"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "id": 7,
                            "email": "alice@example.com",
                            "username": "alice",
                            "role": "user",
                            "balance": 10.5,
                            "frozen_balance": 0,
                            "total_recharged": 100,
                            "status": "active"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Sub2AdminUser user = client.findUserById(BASE_URL, "admin-key", 7).orElseThrow();

        assertEquals(7L, user.id());
        assertEquals("alice", user.username());
        server.verify();
    }

    @Test
    void shouldPostBalanceAddWithIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2UserAdminClient client = new Sub2UserAdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/users/7/balance"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "admin-key"))
                .andExpect(header("Idempotency-Key", "monitor-bot-balance-ABC12345"))
                .andExpect(jsonPath("$.operation").value("add"))
                .andExpect(jsonPath("$.balance").value(12.5))
                .andExpect(jsonPath("$.notes").value("QQ机器人充值 by admin@example.com"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "id": 7,
                            "email": "alice@example.com",
                            "username": "alice",
                            "role": "user",
                            "balance": 23,
                            "frozen_balance": 0,
                            "total_recharged": 100,
                            "status": "active"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Sub2AdminUser updated = client.updateBalance(
                BASE_URL, "admin-key", 7, new BigDecimal("12.50"),
                "QQ机器人充值 by admin@example.com", "monitor-bot-balance-ABC12345");

        assertEquals(23.0, updated.balance());
        assertTrue(updated.id() == 7L);
        server.verify();
    }
}
