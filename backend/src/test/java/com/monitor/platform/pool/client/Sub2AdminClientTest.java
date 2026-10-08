package com.monitor.platform.pool.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Sub2AdminClient 请求契约测试。
 */
class Sub2AdminClientTest {

    private static final String BASE_URL = "https://codex.trovebox.online";
    private static final String ADMIN_KEY = "admin-test-key";

    @Test
    void shouldFetchAccountsWithApiKeyHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2AdminClient client = new Sub2AdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/accounts?page=1&page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", ADMIN_KEY))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "items": [
                              {
                                "id": 52064,
                                "name": "满血",
                                "platform": "openai",
                                "account_type": "api_key",
                                "status": "active",
                                "schedulable": true,
                                "concurrency": 5,
                                "priority": 1,
                                "rate_multiplier": 0.18,
                                "last_used_at": "2026-10-08T10:25:44.565599+08:00"
                              }
                            ],
                            "total": 1,
                            "page": 1,
                            "page_size": 100
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Sub2AdminAccount> accounts = client.fetchAccounts(BASE_URL, ADMIN_KEY, 1, 100);

        assertEquals(1, accounts.size());
        Sub2AdminAccount account = accounts.get(0);
        assertEquals(52064L, account.id());
        assertEquals("满血", account.name());
        assertEquals("openai", account.platform());
        assertEquals("active", account.status());
        assertEquals(Boolean.TRUE, account.schedulable());
        assertEquals(5, account.concurrency());
        assertEquals(0.18, account.rateMultiplier().doubleValue(), 0.0001);
        server.verify();
    }

    @Test
    void shouldFetchAccountCredentialsFromExportEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2AdminClient client = new Sub2AdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/accounts/data"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", ADMIN_KEY))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": [
                            {"name": "满血", "credentials": {"api_key": "sk-abcdef123456"}}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Sub2AdminAccountCredential> credentials = client.fetchAccountCredentials(BASE_URL, ADMIN_KEY);

        assertEquals(1, credentials.size());
        assertEquals("满血", credentials.get(0).name());
        assertEquals("sk-abcdef123456", credentials.get(0).apiKey());
        server.verify();
    }

    @Test
    void shouldFetchUsageLogsWithCacheAndFirstTokenFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2AdminClient client = new Sub2AdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/usage?account_id=52064"
                        + "&start_date=2026-10-01&end_date=2026-10-08"
                        + "&sort_by=created_at&sort_order=desc&page=1&page_size=1000"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", ADMIN_KEY))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "items": [
                              {
                                "request_id": "req-1",
                                "api_key_id": 281,
                                "model": "gpt-5",
                                "channel_id": 12,
                                "endpoint": "/v1/responses",
                                "stream": true,
                                "created_at": "2026-10-08T10:25:44.565599+08:00",
                                "first_token_ms": 820,
                                "duration_ms": 46950,
                                "input_tokens": 1200,
                                "output_tokens": 340,
                                "cache_read_tokens": 4096,
                                "cache_creation_tokens": 0,
                                "total_cost": 0.25,
                                "actual_cost": 0.04
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Sub2UsageLog> logs = client.fetchUsage(BASE_URL, ADMIN_KEY, 52064L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 1, 1000);

        assertEquals(1, logs.size());
        Sub2UsageLog log = logs.get(0);
        assertEquals("req-1", log.requestId());
        assertEquals(820, log.firstTokenMs());
        assertEquals(4096L, log.cacheReadTokens());
        assertEquals(0L, log.cacheCreationTokens());
        assertEquals(Boolean.TRUE, log.stream());
        assertTrue(log.createdAt() != null);
        assertEquals(0.04, log.actualCost().doubleValue(), 0.0001);
        assertEquals("2026-10-08T10:25:44.565599+08:00", log.createdAt().toString());
        server.verify();
    }

    @Test
    void shouldFailWhenBusinessCodeIsNotZero() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Sub2AdminClient client = new Sub2AdminClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/admin/accounts/data"))
                .andRespond(withSuccess("""
                        {"code": 401, "message": "invalid admin key"}
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> client.fetchAccountCredentials(BASE_URL, ADMIN_KEY));
        assertTrue(ex.getMessage().contains("invalid admin key"));
        assertNull(null);
        server.verify();
    }
}