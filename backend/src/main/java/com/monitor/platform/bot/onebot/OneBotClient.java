package com.monitor.platform.bot.onebot;

import com.monitor.platform.bot.QqBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OneBot v11 HTTP API 客户端（只用来发消息）。
 *
 * <p>发送失败只记日志：机器人答不出来不应该把回调链路也拖垮。
 * 但发图片时会把失败结果返回给调用方，好让它退回文本 —— 图片发不出去时不能什么都不发。</p>
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

    /**
     * 发图片到群。
     *
     * @return 是否发送成功；false 时调用方应退回文本
     */
    public boolean sendGroupImage(long groupId, byte[] png) {
        return sendImage("/send_group_msg", "group_id", groupId, png);
    }

    /** 发图片到私聊；返回值含义同上。 */
    public boolean sendPrivateImage(long userId, byte[] png) {
        return sendImage("/send_private_msg", "user_id", userId, png);
    }

    private void send(String path, String idField, long id, String text) {
        dispatch(path, idField, id, text, text);
    }

    private boolean sendImage(String path, String idField, long id, byte[] png) {
        if (png == null || png.length == 0) {
            return false;
        }
        // OneBot v11 的消息段数组格式：{"type":"image","data":{"file":"base64://..."}}
        Map<String, Object> segment = Map.of(
                "type", "image",
                "data", Map.of("file", "base64://" + Base64.getEncoder().encodeToString(png)));
        return dispatch(path, idField, id, List.of(segment), "image(" + png.length + " bytes)");
    }

    /**
     * 统一发送。
     *
     * @param message  OneBot 的 {@code message} 字段：文本是字符串，图片是消息段数组
     * @param describe 日志里怎么描述这条消息
     */
    private boolean dispatch(String path, String idField, long id, Object message, String describe) {
        if (!properties.isConfigured()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(idField, id);
        payload.put("message", message);
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            String token = properties.getApiToken();
            if (token != null && !token.isBlank()) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            spec.body(payload).retrieve().toBodilessEntity();
            log.debug("已发送 QQ 消息: path={}, id={}, body={}", path, id, describe);
            return true;
        } catch (Exception ex) {
            // 联调阶段最常见的问题就是 OneBot 地址/端口/token 没配好；把内容一并打出来，
            // 这样即使消息发不出去，也能从日志确认「查询链路本身是通的」。
            log.warn("发送 QQ 消息失败: path={}, id={}, err={}; 本条内容: {}",
                    path, id, ex.getMessage(), preview(describe));
            return false;
        }
    }

    /** 日志里预览内容，过长时截断。 */
    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}