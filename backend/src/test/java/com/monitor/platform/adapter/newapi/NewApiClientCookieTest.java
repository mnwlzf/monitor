package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.config.RestClientConfig;
import com.monitor.platform.config.UpstreamHttpProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * refresh token 的真实 HTTP 往返测试。
 *
 * <p>MockRestServiceServer 不经过真实 JDK HttpClient，无法覆盖 Cookie 的读写，
 * 这里用本地 socket 验证两段关键行为：登录响应里的 {@code Set-Cookie} 能否被读到、
 * 刷新请求的 {@code Cookie} 头能否真的发出去。</p>
 */
class NewApiClientCookieTest {

    private static final String LOGIN_BODY = """
            {"data":{"access_expires_at":1790732536,"access_token":"access-token","token_type":"Bearer",
            "user":{"id":647,"username":"user@example.com"}},"message":"","success":true}
            """;

    private static final String REFRESH_BODY = """
            {"data":{"access_expires_at":1790732536,"access_token":"refreshed-token","token_type":"Bearer",
            "user":{"id":647,"username":"user@example.com"}},"message":"","success":true}
            """;

    @Test
    void shouldReadLoginCookieAndSendItOnRefresh() throws IOException {
        Map<String, String> refreshRequestHeaders = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie",
                    "new_api_refresh=sid-1.secret-1; Path=/api/user/auth; HttpOnly; Secure; SameSite=Strict");
            respond(exchange, LOGIN_BODY);
        });
        server.createContext("/api/user/auth/refresh", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) ->
                    refreshRequestHeaders.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            exchange.getResponseHeaders().add("Set-Cookie",
                    "new_api_refresh=sid-1.secret-2; Path=/api/user/auth; HttpOnly; Secure; SameSite=Strict");
            respond(exchange, REFRESH_BODY);
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient restClient = new RestClientConfig().restClient(new UpstreamHttpProperties());
            NewApiClient client = new NewApiClient(restClient);

            NewApiClient.AuthSession login = client.login(baseUrl, new NewApiLoginRequest("u", "p"));
            assertEquals("sid-1.secret-1", login.refreshToken());
            assertTrue(login.setCookieHeaders().stream().anyMatch(cookie -> cookie.startsWith("new_api_refresh=")),
                    "应保留原始 Set-Cookie 头用于排查，实际: " + login.setCookieHeaders());

            NewApiClient.AuthSession refreshed = client.refreshAuth(baseUrl, login.refreshToken());
            assertEquals("new_api_refresh=sid-1.secret-1", refreshRequestHeaders.get("cookie"));
            assertEquals(baseUrl, refreshRequestHeaders.get("origin"));
            assertEquals("sid-1.secret-2", refreshed.refreshToken());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSkipClearedCookieAndPickTheRealRefreshToken() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/login", exchange -> {
            var headers = exchange.getResponseHeaders();
            headers.add("Set-Cookie", "new_api_refresh=; Path=/api/user/auth; Max-Age=0; HttpOnly; Secure; SameSite=Strict");
            headers.add("Set-Cookie", "new_api_has_session=1; Path=/; HttpOnly=false; Secure; SameSite=Strict");
            headers.add("Set-Cookie", "new_api_refresh=sid-2.secret-2; Path=/api/user/auth; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict");
            respond(exchange, LOGIN_BODY);
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient restClient = new RestClientConfig().restClient(new UpstreamHttpProperties());
            NewApiClient client = new NewApiClient(restClient);

            NewApiClient.AuthSession login = client.login(baseUrl, new NewApiLoginRequest("u", "p"));

            assertEquals("sid-2.secret-2", login.refreshToken());
        } finally {
            server.stop(0);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
