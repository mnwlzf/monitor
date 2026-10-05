package com.monitor.platform.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 上游 RestClient 默认请求头测试：确认采集请求伪装成普通浏览器。
 */
class RestClientConfigTest {

    @Test
    void shouldSendBrowserLikeHeadersByDefault() {
        UpstreamHttpProperties properties = new UpstreamHttpProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new RestClientConfig().applyBrowserHeaders(builder, properties).build();

        server.expect(requestTo("https://yunmian.tech/api/status"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, properties.getUserAgent()))
                .andExpect(header(HttpHeaders.ACCEPT, properties.getAccept()))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, properties.getAcceptLanguage()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("https://yunmian.tech/api/status").retrieve().body(String.class);

        server.verify();
        assertFalse(properties.getUserAgent().contains("Java-http-client"));
    }

    @Test
    void shouldSkipBlankHeaders() {
        UpstreamHttpProperties properties = new UpstreamHttpProperties();
        properties.setUserAgent("   ");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new RestClientConfig().applyBrowserHeaders(builder, properties).build();

        server.expect(requestTo("https://yunmian.tech/api/status"))
                .andExpect(headerDoesNotExist(HttpHeaders.USER_AGENT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("https://yunmian.tech/api/status").retrieve().body(String.class);

        server.verify();
    }

    /**
     * 端到端验证：用真实 JDK HttpClient 发请求，检查服务端实际收到的请求头。
     *
     * <p>MockRestServiceServer 只校验我们设置了什么头，这里再走一次真实 socket，
     * 确认 JDK 自带的 {@code Java-http-client/xx} 没有泄漏出去。</p>
     */
    @Test
    void shouldNotLeakJavaHttpClientUserAgentOnTheWire() throws IOException {
        Map<String, String> receivedHeaders = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/status", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) ->
                    receivedHeaders.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient client = new RestClientConfig().restClient(new UpstreamHttpProperties());

            client.get().uri(baseUrl + "/api/status").retrieve().body(String.class);

            String userAgent = receivedHeaders.getOrDefault("user-agent", "");
            System.out.println("[wire] 上游收到的请求头 = " + receivedHeaders);
            assertTrue(userAgent.startsWith("Mozilla/5.0"), "User-Agent 应伪装成浏览器，实际: " + userAgent);
            assertFalse(userAgent.contains("Java-http-client"), "不应泄漏 JDK 默认 UA，实际: " + userAgent);
            assertFalse(receivedHeaders.toString().contains("Java-http-client"), "请求头中不应出现 Java-http-client");
            assertFalse(receivedHeaders.containsKey("accept-encoding"), "不应声明 Accept-Encoding，避免上游压缩后无法解析");
        } finally {
            server.stop(0);
        }
    }
}
