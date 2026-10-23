package com.monitor.platform.bot.error;

import com.monitor.platform.bot.BotSettings;
import com.monitor.platform.bot.BotSettingsService;
import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFingerprint;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorSummary;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.mail.MailScene;
import com.monitor.platform.notify.QqRecipientEntity;
import com.monitor.platform.notify.QqRecipientRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotErrorAlertServiceTest {

    private final Sub2ErrorReader reader = mock(Sub2ErrorReader.class);
    private final BotSettingsService settingsService = mock(BotSettingsService.class);
    private final QqBotProperties botProperties = new QqBotProperties();
    private final QqRecipientRepository recipientRepository = mock(QqRecipientRepository.class);
    private final OneBotClient client = mock(OneBotClient.class);
    private final BotErrorAlertService service = new BotErrorAlertService(
            reader, settingsService, botProperties, recipientRepository, client);

    @Test
    void shouldSendSpikeAlertAndRespectCooldown() {
        settingsServiceReturns(true);
        botProperties.setEnabled(true);
        botProperties.setWebhookToken("token");
        botProperties.setApiBaseUrl("http://localhost:3000");
        when(reader.isAvailable()).thenReturn(true);
        when(recipientRepository.findByScene(MailScene.ERROR_ALERT))
                .thenReturn(List.of(recipient("GROUP", 123L)));
        when(reader.summary(any(), any(), any(ErrorFilter.class)))
                .thenReturn(new ErrorSummary(10, 1, 6, 4, 2, 1, 3),
                        new ErrorSummary(2, 0, 1, 0, 0, 0, 1));
        when(reader.ingressRejectCount(any(), any())).thenReturn(10L, 1L);
        when(reader.topFingerprints(any(), any(), any(ErrorFilter.class), anyInt()))
                .thenReturn(List.of(new ErrorFingerprint("upstream", "rate_limit_error", 529,
                        "UPSTREAM_OVERLOAD", 8, 3, 2,
                        OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now(), "overloaded")));
        when(client.sendGroupMessage(eq(123L), any())).thenReturn(true);

        service.checkAndNotify();
        service.checkAndNotify();

        verify(client, times(1)).sendGroupMessage(eq(123L), contains("Sub2API 错误告警"));
    }

    @Test
    void shouldNotSendWhenPageHasNoRecipients() {
        settingsServiceReturns(true);
        botProperties.setEnabled(true);
        botProperties.setWebhookToken("token");
        botProperties.setApiBaseUrl("http://localhost:3000");
        when(reader.isAvailable()).thenReturn(true);
        when(recipientRepository.findByScene(MailScene.ERROR_ALERT)).thenReturn(List.of());

        service.checkAndNotify();

        verify(client, never()).sendGroupMessage(anyLong(), any());
        verify(client, never()).sendPrivateMessage(anyLong(), any());
    }

    @Test
    void shouldNotSendWhenPageToggleIsOff() {
        settingsServiceReturns(false);
        botProperties.setEnabled(true);
        botProperties.setWebhookToken("token");
        botProperties.setApiBaseUrl("http://localhost:3000");
        when(reader.isAvailable()).thenReturn(true);

        service.checkAndNotify();

        verify(recipientRepository, never()).findByScene(any());
        verify(client, never()).sendGroupMessage(anyLong(), any());
        verify(client, never()).sendPrivateMessage(anyLong(), any());
    }

    private void settingsServiceReturns(boolean enabled) {
        when(settingsService.current()).thenReturn(new BotSettings(
                true, Set.of(), Set.of(), Set.of(), true, "/", 900, 10,
                false, enabled, 15, 5, 1.5, 30));
    }

    private static QqRecipientEntity recipient(String type, Long targetId) {
        QqRecipientEntity entity = new QqRecipientEntity();
        entity.setTargetType(type);
        entity.setTargetId(targetId);
        return entity;
    }
}
