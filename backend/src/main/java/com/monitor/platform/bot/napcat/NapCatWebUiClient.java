package com.monitor.platform.bot.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.api.dto.NapCatStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NapCat WebUI 的只读客户端。
 *
 * <p>Monitor 与 NapCat 在同一 Docker 网络时直接访问 6099 端口。WebUI token 从
 * {@code webui.json} 读取；登录凭证只保存在内存里，不落库、不写日志。</p>
 */
@Component
public class NapCatWebUiClient {

    private static final Logger log = LoggerFactory.getLogger(NapCatWebUiClient.class);
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String STATUS_PATH = "/api/QQLogin/CheckLoginStatus";
    private static final String REFRESH_QR_PATH = "/api/QQLogin/RefreshQRcode";
    private static final String LOG_STREAM_PATH = "/api/Log/GetLogRealTime";
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final NapCatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile TokenCache tokenCache;
    private volatile String credential;
    private volatile Instant credentialExpiresAt;

    public NapCatWebUiClient(NapCatProperties properties,
                             @Qualifier(NapCatConfiguration.NAPCAT_REST_CLIENT) RestClient restClient,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** WebUI token 配置是否存在。 */
    public boolean isConfigured() {
        return properties.isEnabled() && !webUiToken().isBlank();
    }

    /** 查询当前 QQ 登录状态。 */
    public NapCatStatusResponse status() {
        if (!isConfigured()) {
            return new NapCatStatusResponse(false, false, false, false, "",
                    null, "", "未找到 NapCat WebUI 配置", Instant.now());
        }
        try {
            JsonNode data = authorizedPost(STATUS_PATH, Map.of());
            boolean login = data.path("isLogin").asBoolean(false);
            return new NapCatStatusResponse(
                    true,
                    true,
                    login,
                    data.path("isOffline").asBoolean(false),
                    text(data, "loginPhase"),
                    login ? null : text(data, "qrcodeurl"),
                    text(data, "loginError"),
                    "",
                    Instant.now());
        } catch (RuntimeException ex) {
            log.debug("读取 NapCat 登录状态失败: {}", ex.getMessage());
            return new NapCatStatusResponse(true, false, false, false, "",
                    null, "", ex.getMessage(), Instant.now());
        }
    }

    /** 刷新二维码，然后返回最新状态。 */
    public NapCatStatusResponse refreshQrCode() {
        if (!isConfigured()) {
            return status();
        }
        try {
            authorizedPost(REFRESH_QR_PATH, Map.of());
            return status();
        } catch (RuntimeException ex) {
            NapCatStatusResponse current = status();
            return new NapCatStatusResponse(current.configured(), current.reachable(), current.login(),
                    current.offline(), current.loginPhase(), current.qrCodeUrl(), current.loginError(),
                    ex.getMessage(), Instant.now());
        }
    }

    /**
     * 订阅 NapCat 实时日志。
     *
     * <p>该方法阻塞到 SSE 连接结束；调用方负责断线重连。</p>
     */
    public void streamLogs(Consumer<LogEntry> consumer) {
        if (!isConfigured()) {
            throw new IllegalStateException("NapCat WebUI 未配置");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = credential();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + LOG_STREAM_PATH))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .GET()
                    .build();
            try {
                HttpResponse<Stream<String>> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() == 401 && attempt == 0) {
                    invalidateCredential();
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("NapCat 日志流返回 HTTP " + response.statusCode());
                }
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> consumeSseLine(line, consumer));
                }
                return;
            } catch (IOException ex) {
                throw new IllegalStateException("NapCat 日志流连接失败: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 使当前 WebUI 登录凭证失效，下一次请求会重新登录。 */
    public void invalidateCredential() {
        credential = null;
        credentialExpiresAt = null;
    }

    private void consumeSseLine(String line, Consumer<LogEntry> consumer) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String json = line.substring(5).trim();
        if (json.isEmpty()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String message = ANSI.matcher(node.path("message").asText("")).replaceAll("").replace("\r", "");
            String level = node.path("level").asText("info");
            if (!message.isBlank()) {
                consumer.accept(new LogEntry(level, message));
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("跳过无法解析的 NapCat 日志行: {}", ex.getMessage());
        }
    }

    private JsonNode authorizedPost(String path, Object body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = credential();
            try {
                JsonNode response = restClient.post()
                        .uri(baseUrl() + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                return requireData(response);
            } catch (HttpClientErrorException.Unauthorized ex) {
                invalidateCredential();
                if (attempt == 1) {
                    throw ex;
                }
            }
        }
        throw new IllegalStateException("NapCat WebUI 鉴权失败");
    }

    private JsonNode postUnauthenticated(String path, Object body) {
        JsonNode response = restClient.post()
                .uri(baseUrl() + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return requireData(response);
    }

    private JsonNode requireData(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("NapCat WebUI 返回空响应");
        }
        if (response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(response.path("message").asText("NapCat 请求失败"));
        }
        return response.path("data");
    }

    private synchronized String credential() {
        Instant expiresAt = credentialExpiresAt;
        if (credential != null && expiresAt != null && expiresAt.isAfter(Instant.now())) {
            return credential;
        }
        String token = requireToken();
        String hash = sha256(token + ".napcat");
        JsonNode data = postUnauthenticated(LOGIN_PATH, Map.of("hash", hash));
        String value = data.path("Credential").asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("NapCat WebUI 未返回登录凭证");
        }
        Duration ttl = properties.getCredentialTtl() == null
                ? Duration.ofMinutes(30) : properties.getCredentialTtl();
        credential = value;
        credentialExpiresAt = Instant.now().plus(ttl);
        return value;
    }

    private String requireToken() {
        String token = webUiToken();
        if (token.isBlank()) {
            throw new IllegalStateException("NapCat WebUI token 未配置");
        }
        return token;
    }

    private String webUiToken() {
        if (!properties.isEnabled()) {
            return "";
        }
        Path path = Path.of(properties.getWebuiConfig());
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            FileTime modified = Files.getLastModifiedTime(path);
            TokenCache cached = tokenCache;
            if (cached != null && cached.modified().equals(modified)) {
                return cached.token();
            }
            JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            String token = root.path("token").asText("").trim();
            tokenCache = new TokenCache(modified, token);
            invalidateCredential();
            return token;
        } catch (IOException | RuntimeException ex) {
            log.warn("读取 NapCat WebUI 配置失败: path={}, reason={}", path, ex.getMessage());
            return "";
        }
    }

    private String baseUrl() {
        String url = properties.getWebuiBaseUrl() == null ? "" : properties.getWebuiBaseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算 NapCat WebUI 登录摘要", ex);
        }
    }

    /** NapCat SSE 日志条目。 */
    public record LogEntry(String level, String message) {
    }

    private record TokenCache(FileTime modified, String token) {
    }
}