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
            log.debug("已发送 QQ 消息: path={}, id={}, len={}", path, id, text.length());
        } catch (Exception ex) {
            // 联调阶段最常见的问题就是 OneBot 地址/端口/token 没配好；把内容一并打出来，
            // 这样即使消息发不出去，也能从日志确认「查询链路本身是通的」。
            log.warn("发送 QQ 消息失败: path={}, id={}, err={}; 本条回复内容: {}", path, id, ex.getMessage(), preview(text));
        }
    }

    /** 日志里预览回复内容，过长时截断。 */
    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}