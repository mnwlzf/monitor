package com.monitor.platform.bot;

import com.monitor.platform.bot.onebot.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 回调入口鉴权：这个接口没有登录会话，完全靠共享密钥，必须 fail closed。
 */
class QqBotWebhookControllerTest {

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
        controller = new QqBotWebhookController(properties, messageService);
    }

    private OneBotEvent sampleEvent() {
        return new OneBotEvent("message", "private", null, 1L, 2L, "你好", null, null);
    }

    @Test
    void shouldReturnNotFoundWhenBotDisabled() {
        QqBotWebhookController disabled = new QqBotWebhookController(new QqBotProperties(), messageService);

        assertEquals(HttpStatus.NOT_FOUND, disabled.onEvent("Bearer secret", null, sampleEvent()).getStatusCode());
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldRejectMissingOrWrongToken() {
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent(null, null, sampleEvent()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent("Bearer wrong", null, sampleEvent()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.onEvent(null, "wrong", sampleEvent()).getStatusCode());
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldAcceptBearerHeaderAndQueryToken() {
        OneBotEvent event = sampleEvent();

        assertEquals(HttpStatus.NO_CONTENT, controller.onEvent("Bearer secret", null, event).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.onEvent(null, "secret", event).getStatusCode());

        verify(messageService, times(2)).onEvent(event);
    }
}