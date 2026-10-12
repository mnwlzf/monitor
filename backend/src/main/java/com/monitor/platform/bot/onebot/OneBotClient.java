package com.monitor.platform.bot.onebot;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** OneBot 地址（仅用于状态展示）。 */
    public String baseUrl() {
        return properties.getApiBaseUrl();
    }

    /**
     * 探活：问 NapCat 要运行状态与登录信息（OneBot v11 的 /get_status 与 /get_login_info）。
     *
     * <p>「机器人状态」页面用它回答两个最要紧的问题：OneBot 连得上吗、机器人账号在线吗。
     * 任何失败都翻译成结果对象，不抛异常 —— 页面只是展示，不该因为探活失败而报错。</p>
     */
    public ProbeResult probe() {
        if (!properties.isConfigured()) {
            return ProbeResult.notConfigured();
        }
        try {
            JsonNode status = postData("/get_status");
            JsonNode login = postData("/get_login_info");
            boolean online = status != null && status.path("online").asBoolean(false);
            Long selfId = null;
            if (login != null && login.path("user_id").isNumber()) {
                selfId = login.path("user_id").asLong();
            }
            String nickname = login == null ? null : login.path("nickname").asText(null);
            return ProbeResult.ok(online, selfId, nickname);
        } catch (RuntimeException ex) {
            log.warn("OneBot 探活失败: {}", ex.getMessage());
            return ProbeResult.failed(ex.getMessage());
        }
    }

    /** 调一个不关心入参的 OneBot 接口，返回响应里的 data。 */
    private JsonNode postData(String path) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        String token = properties.getApiToken();
        if (token != null && !token.isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        JsonNode response = spec.body(Map.of()).retrieve().body(JsonNode.class);
        return response == null ? null : response.path("data");
    }

    /**
     * 探活结果。
     *
     * @param configured 是否配了 OneBot 地址（没配就别谈通不通）
     * @param reachable  这次探测是否拿到了响应
     * @param online     OneBot 自报的在线状态
     * @param selfId     机器人自己的 QQ 号，取不到为 null
     * @param nickname   机器人昵称，取不到为 null
     * @param error      探测失败时的原因，成功为 null
     */
    public record ProbeResult(boolean configured, boolean reachable, boolean online,
                              Long selfId, String nickname, String error) {

        static ProbeResult notConfigured() {
            return new ProbeResult(false, false, false, null, null, null);
        }

        static ProbeResult ok(boolean online, Long selfId, String nickname) {
            return new ProbeResult(true, true, online, selfId, nickname, null);
        }

        static ProbeResult failed(String error) {
            return new ProbeResult(true, false, false, null, null, error);
        }
    }    private void send(String path, String idField, long id, String text) {
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