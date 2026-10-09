package com.monitor.platform.bot.onebot;

import com.monitor.platform.bot.QqBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot v11 HTTP API 客户端（只用来发消息）。
 *
 * <p>发送失败只记日志：机器人答不出来不应该把回调链路也拖垮。</p>
 */
@Component
public class OneBotClient {

    private static final Logger log = LoggerFactory.getLogger(OneBotClient.class);

    private final QqBotProperties properties;
    private final RestClient restClient;

    public OneBotClient(QqBotProperties properties) {
        this.properties = properties;
        String base = properties.getApiBaseUrl();
        this.restClient = RestClient.builder()
                .baseUrl(base == null || base.isBlank() ? "http://127.0.0.1" : base.replaceAll("/+$", ""))
                .build();
    }

    public void sendGroupMessage(long groupId, String text) {
        send("/send_group_msg", "group_id", groupId, text);
    }

    public void sendPrivateMessage(long userId, String text) {
        send("/send_private_msg", "user_id", userId, text);
    }

    private void send(String path, String idField, long id, String text) {
        if (!properties.isConfigured()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(idField, id);
        payload.put("message", text);
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            String token = properties.getApiToken();
            if (token != null && !token.isBlank()) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            spec.body(payload).retrieve().toBodilessEntity();
        } catch (Exception ex) {
            log.warn("发送 QQ 消息失败: path={}, id={}, err={}", path, id, ex.getMessage());
        }
    }
}