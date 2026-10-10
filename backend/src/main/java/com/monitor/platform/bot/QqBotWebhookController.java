package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * OneBot v11 反向 HTTP 上报入口。
 *
 * <p>机器人在 OneBot 实现（NapCat / Lagrange / go-cqhttp）里配置「反向 HTTP 上报」指向
 * {@code POST /api/v1/bot/onebot}。未配置密钥时直接 404，避免无意中把入口暴露出去。</p>
 *
 * <p><strong>鉴权兼容两种写法</strong>（都依赖 {@code monitor.bot.webhook-token}）：</p>
 * <ol>
 *     <li>OneBot v11 标准：{@code X-Signature: sha1=HMAC_SHA1(token, 原始请求体)}。
 *         NapCat 用的就是这种，它<em>不会</em>发 Authorization 头；</li>
 *     <li>简单共享密钥：{@code Authorization: Bearer <token>} 或 {@code ?access_token=<token>}。</li>
 * </ol>
 *
 * <p>这个接口不需要登录会话，且只读、只回消息。为了能校验签名，这里接收原始字符串再自行解析 JSON。</p>
 */
@RestController
@RequestMapping("/api/v1/bot/onebot")
public class QqBotWebhookController {

    private static final String BEARER = "Bearer ";
    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final String SIGNATURE_PREFIX = "sha1=";

    private final QqBotProperties properties;
    private final BotMessageService messageService;
    private final ObjectMapper objectMapper;

    public QqBotWebhookController(QqBotProperties properties,
                                  BotMessageService messageService,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> onEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestParam(value = "access_token", required = false) String accessToken,
            @RequestBody(required = false) String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.notFound().build();
        }
        if (!authorized(authorization, accessToken, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // 立刻返回，真正的处理在后台线程里做，避免 OneBot 侧超时重推
        messageService.onEvent(parse(rawBody));
        return ResponseEntity.noContent().build();
    }

    /** 兼容 X-Signature 签名与 Bearer / access_token 共享密钥。 */
    private boolean authorized(String authorization, String accessToken, String signature, String rawBody) {
        if (signature != null && !signature.isBlank()) {
            return verifySignature(signature, rawBody);
        }
        String provided = accessToken;
        if ((provided == null || provided.isBlank())
                && authorization != null && authorization.startsWith(BEARER)) {
            provided = authorization.substring(BEARER.length()).trim();
        }
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.getWebhookToken().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验 {@code X-Signature: sha1=HMAC_SHA1(token, 原始请求体)}。 */
    private boolean verifySignature(String signature, String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(properties.getWebhookToken().getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            String expected = SIGNATURE_PREFIX + HexFormat.of()
                    .formatHex(mac.doFinal((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return false;
        }
    }

    private OneBotEvent parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, OneBotEvent.class);
        } catch (Exception ex) {
            return null;
        }
    }
}