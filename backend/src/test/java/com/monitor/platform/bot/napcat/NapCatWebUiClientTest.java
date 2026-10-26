package com.monitor.platform.bot.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.api.dto.NapCatStatusResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NapCatWebUiClientTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private NapCatProperties properties;
    private NapCatWebUiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        Path webui = tempDir.resolve("webui.json");
        Files.writeString(webui, "{\"token\":\"test-token\"}", StandardCharsets.UTF_8);

        properties = new NapCatProperties();
        properties.setWebuiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setWebuiConfig(webui.toString());
        client = new NapCatWebUiClient(properties, RestClient.create(), objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void statusLogsInAndParsesLoginState() throws Exception {
        AtomicReference<String> loginHash = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();

        server.createContext("/api/auth/login", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            loginHash.set(body.path("hash").asText());
            respond(exchange, """
                    {"code":0,"data":{"Credential":"credential-1"},"message":"success"}
                    """);
        });
        server.createContext("/api/QQLogin/CheckLoginStatus", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {"code":0,"data":{"isLogin":false,"isOffline":true,"loginPhase":"waiting_qrcode",
                    "qrcodeurl":"https://txz.qq.com/p?k=test","loginError":""},"message":"success"}
                    """);
        });

        NapCatStatusResponse status = client.status();

        assertThat(status.configured()).isTrue();
        assertThat(status.reachable()).isTrue();
        assertThat(status.login()).isFalse();
        assertThat(status.offline()).isTrue();
        assertThat(status.loginPhase()).isEqualTo("waiting_qrcode");
        assertThat(status.qrCodeUrl()).isEqualTo("https://txz.qq.com/p?k=test");
        assertThat(loginHash.get()).isEqualTo(sha256("test-token.napcat"));
        assertThat(authorization.get()).isEqualTo("Bearer credential-1");
    }

    @Test
    void refreshQrCodeCallsNapCatThenReadsLatestStatus() throws Exception {
        AtomicBoolean refreshed = new AtomicBoolean();
        AtomicInteger statusCalls = new AtomicInteger();

        server.createContext("/api/auth/login", exchange -> respond(exchange, """
                {"code":0,"data":{"Credential":"credential-1"},"message":"success"}
                """));
        server.createContext("/api/QQLogin/RefreshQRcode", exchange -> {
            refreshed.set(true);
            respond(exchange, """
                    {"code":0,"data":{"qrcodeurl":"https://txz.qq.com/p?k=new"},"message":"success"}
                    """);
        });
        server.createContext("/api/QQLogin/CheckLoginStatus", exchange -> {
            statusCalls.incrementAndGet();
            respond(exchange, """
                    {"code":0,"data":{"isLogin":false,"isOffline":false,"loginPhase":"waiting_qrcode",
                    "qrcodeurl":"https://txz.qq.com/p?k=new","loginError":""},"message":"success"}
                    """);
        });

        NapCatStatusResponse status = client.refreshQrCode();

        assertThat(refreshed).isTrue();
        assertThat(statusCalls).hasValue(1);
        assertThat(status.qrCodeUrl()).isEqualTo("https://txz.qq.com/p?k=new");
    }

    @Test
    void streamLogsParsesSseAndRemovesAnsiColors() throws Exception {
        server.createContext("/api/auth/login", exchange -> respond(exchange, """
                {"code":0,"data":{"Credential":"credential-1"},"message":"success"}
                """));
        server.createContext("/api/Log/GetLogRealTime", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer credential-1");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String message = objectMapper.writeValueAsString(Map.of(
                    "level", "warn",
                    "message", "\u001B[33mhello\u001B[39m world"));
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: " + message + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });

        List<NapCatWebUiClient.LogEntry> entries = new ArrayList<>();
        client.streamLogs(entries::add);

        assertThat(entries).containsExactly(new NapCatWebUiClient.LogEntry("warn", "hello world"));
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
