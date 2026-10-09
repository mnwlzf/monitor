package com.monitor.platform.bot;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * OneBot v11 反向 HTTP 上报入口。
 *
 * <p>机器人在 OneBot 实现（NapCat / Lagrange / go-cqhttp）里配置「反向 HTTP 上报」指向
 * {@code POST /api/v1/bot/onebot}，并把 {@code monitor.bot.webhook-token} 配成同样的值
 * 作为 Authorization 头。未配置密钥时直接 404，避免无意中把入口暴露出去。</p>
 *
 * <p>这个接口不需要登录会话，因此鉴权完全依赖共享密钥，且只读、只回消息。</p>
 */
@RestController
@RequestMapping("/api/v1/bot/onebot")
public class QqBotWebhookController {

    private static final String BEARER = "Bearer ";

    private final QqBotProperties properties;
    private final BotMessageService messageService;

    public QqBotWebhookController(QqBotProperties properties, BotMessageService messageService) {
        this.properties = properties;
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<Void> onEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "access_token", required = false) String accessToken,
            @RequestBody(required = false) OneBotEvent event) {
        if (!properties.isConfigured()) {
            return ResponseEntity.notFound().build();
        }
        if (!tokenMatches(authorization, accessToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // 立刻返回，真正的处理在后台线程里做，避免 OneBot 侧超时重推
        messageService.onEvent(event);
        return ResponseEntity.noContent().build();
    }

    private boolean tokenMatches(String authorization, String accessToken) {
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
}