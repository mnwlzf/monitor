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
 * OneBot v11 HTTP API 客户端（发消息 + 取引用消息原文）。
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

    /**
     * 发文本到群。
     *
     * @return 是否发送成功；false 时调用方（例如主动推送）可据此决定要不要重试
     */
    public boolean sendGroupMessage(long groupId, String text) {
        return send("/send_group_msg", "group_id", groupId, text);
    }

    /** 发文本到私聊；返回值含义同上。 */
    public boolean sendPrivateMessage(long userId, String text) {
        return send("/send_private_msg", "user_id", userId, text);
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
     * 取回被引用消息的原始 message 段（OneBot 的 /get_msg）。
     *
     * <p>QQ 的引用（回复）在消息段里只有一个 {@code reply} 段，通常<strong>只带消息 id</strong>，
     * 不带被引用消息的正文。所以用户引用一条消息再问「这个是什么」时，模型看不到那条消息。</p>
     *
     * <p>返回整个 message 节点而不是纯文本：被引用的消息本身可能就是<strong>图片</strong>
     * （「引用这张图，总结一下」），文字和图片要一起取回去。</p>
     *
     * <p>取不到（消息被撤回、实现不支持、权限不足）时返回 {@code null}，绝不抛异常 ——
     * 少一段上下文不该让整条回复失败。</p>
     *
     * @param messageId 被引用消息的 id
     * @return OneBot 的 message 节点（消息段数组或字符串）；取不到时 null
     */
    public JsonNode fetchMessage(String messageId) {
        if (!properties.isConfigured() || messageId == null || messageId.isBlank()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            // OneBot 的 message_id 标准上是整数；有的实现用字符串，两种都兼容
            body.put("message_id", Long.parseLong(messageId.trim()));
        } catch (NumberFormatException ex) {
            body.put("message_id", messageId.trim());
        }
        try {
            JsonNode data = postData("/get_msg", body);
            return data == null ? null : data.path("message");
        } catch (RuntimeException ex) {
            log.debug("取引用消息失败: id={}, err={}", messageId, ex.getMessage());
            return null;
        }
    }

    /**
     * 取回被引用消息的文字内容；取不到时空串。
     *
     * @see #fetchMessage(String)
     */
    public String fetchMessageText(String messageId) {
        return textOf(fetchMessage(messageId));
    }

    /**
     * 把 OneBot 的消息段拼成纯文本。
     *
     * <p>{@code message} 可能是消息段数组，也可能是字符串（{@code post_format=string}），两种都收。</p>
     */
    public static String textOf(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return "";
        }
        if (message.isTextual()) {
            return message.asText("").trim();
        }
        if (!message.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : message) {
            String type = segment.path("type").asText();
            switch (type) {
                case "text" -> sb.append(segment.path("data").path("text").asText(""));
                case "at" -> sb.append('@').append(segment.path("data").path("qq").asText(""));
                case "image" -> sb.append("[图片]");
                case "face" -> sb.append("[表情]");
                case "record" -> sb.append("[语音]");
                case "video" -> sb.append("[视频]");
                case "file" -> sb.append("[文件]");
                default -> {
                    // 其它段（reply / json / forward 等）不拼进正文
                }
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }    /**
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
            JsonNode status = postData("/get_status", Map.of());
            JsonNode login = postData("/get_login_info", Map.of());
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

    /** 调一个 OneBot 接口，返回响应里的 data。 */
    private JsonNode postData(String path, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        String token = properties.getApiToken();
        if (token != null && !token.isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        JsonNode response = spec.body(body == null ? Map.of() : body).retrieve().body(JsonNode.class);
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
    }    private boolean send(String path, String idField, long id, String text) {
        return dispatch(path, idField, id, text, text);
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