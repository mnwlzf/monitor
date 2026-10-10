package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 回调入口鉴权：这个接口没有登录会话，完全靠共享密钥，必须 fail closed。
 *
 * <p>重点覆盖 NapCat 的写法：它走 {@code X-Signature: sha1=HMAC_SHA1(token, body)}，
 * 不发 Authorization 头。</p>
 */
class QqBotWebhookControllerTest {

    private static final String BODY = "{\"post_type\":\"message\",\"message_type\":\"private\",\"user_id\":1}";

    private final ObjectMapper mapper = new ObjectMapper();
    private QqBotProperties properties;
    private BotMessageService messageService;
    private QqBotWebhookController controller;

    @BeforeEach
    void setUp() {
        properties = new QqBotProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("secret");
        properties.setApiBaseUrl("http://127.0.0.1:3000");
        messageService = mock(BotMessageService.class);
        controller = new QqBotWebhookController(properties, messageService, mapper);
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return "sha1=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldReturnNotFoundWhenBotDisabled() {
        QqBotWebhookController disabled = new QqBotWebhookController(new QqBotProperties(), messageService, mapper);

        assertEquals(HttpStatus.NOT_FOUND, disabled.onEvent("Bearer secret", null, null, BODY).getStatusCode());
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldAcceptNapCatSignatureHeader() throws Exception {
        assertEquals(HttpStatus.NO_CONTENT,
                controller.onEvent(null, sign(BODY), null, BODY).getStatusCode());
        verify(messageService).onEvent(any());
    }

    @Test
    void shouldRejectWrongSignature() {
        assertEquals(HttpStatus.FORBIDDEN,
                controller.onEvent(null, "sha1=deadbeef", null, BODY).getStatusCode());
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldRejectMissingOrWrongToken() {
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent(null, null, null, BODY).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent("Bearer wrong", null, null, BODY).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent(null, null, "wrong", BODY).getStatusCode());
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldAcceptBearerHeaderAndQueryToken() {
        assertEquals(HttpStatus.NO_CONTENT,
                controller.onEvent("Bearer secret", null, null, BODY).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT,
                controller.onEvent(null, null, "secret", BODY).getStatusCode());
        verify(messageService, times(2)).onEvent(any());
    }
}