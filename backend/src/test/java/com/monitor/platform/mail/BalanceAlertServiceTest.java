package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.notify.QqMessagePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余额提醒测试：邮件与 QQ 两条通道各自独立，重复提醒按间隔冷却。
 */
class BalanceAlertServiceTest {

    private final PlatformRepository platformRepository = mock(PlatformRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountMetricSnapshotRepository metricSnapshotRepository =
            mock(AccountMetricSnapshotRepository.class);
    private final NotificationSettingsRepository notificationSettingsRepository =
            mock(NotificationSettingsRepository.class);
    private final MailRecipientRepository mailRecipientRepository = mock(MailRecipientRepository.class);
    private final SmtpConfigProvider smtpConfigProvider = mock(SmtpConfigProvider.class);
    private final MailService mailService = mock(MailService.class);
    private final QqMessagePushService qqMessagePushService = mock(QqMessagePushService.class);

    private final NotificationSettingsEntity settings = new NotificationSettingsEntity();
    private final AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
    private final MailRecipientEntity recipient = new MailRecipientEntity();

    private BalanceAlertService service;

    @BeforeEach
    void setUp() {
        service = new BalanceAlertService(
                platformRepository, accountRepository, metricSnapshotRepository,
                notificationSettingsRepository, mailRecipientRepository, smtpConfigProvider, mailService,
                qqMessagePushService);

        PlatformEntity platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("测试平台");
        platform.setPlatformType("newapi");
        platform.setStatus(true);

        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        account.setUsername("tester");
        account.setStatus(true);

        snapshot.setBalance(new BigDecimal("3"));

        settings.setId(1);
        settings.setBalanceAlertEnabled(true);
        settings.setBalanceThreshold(new BigDecimal("5"));
        settings.setAlertIntervalMinutes(360);

        recipient.setEmail("ops@example.com");

        SmtpConfig smtpConfig = new SmtpConfig(true, "smtp.example.com", 587, "user", "pw",
                "monitor@example.com", "Monitor", false);

        when(platformRepository.findEnabled()).thenReturn(List.of(platform));
        when(accountRepository.findEnabledByPlatformId(1)).thenReturn(List.of(account));
        when(metricSnapshotRepository.findLatest(7)).thenReturn(Optional.of(snapshot));
        when(notificationSettingsRepository.get()).thenReturn(settings);
        when(mailRecipientRepository.findByScene(MailScene.BALANCE_ALERT)).thenReturn(List.of(recipient));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig);
    }

    @Test
    void repeatsOnlyAfterIntervalWhileStillLow() {
        // 第一轮：低于阈值且从未提醒，发送
        service.checkAndNotify();
        verify(mailService, times(1)).send(any(SmtpConfig.class), anyList(), anyString(), anyString());

        // 第二轮：仍在间隔内，不重复发送
        service.checkAndNotify();
        verify(mailService, times(1)).send(any(SmtpConfig.class), anyList(), anyString(), anyString());

        // 模拟已超过提醒间隔（默认 360 分钟），再次发送
        settings.setLastAlertAt(OffsetDateTime.now().minusHours(7));
        service.checkAndNotify();
        verify(mailService, times(2)).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    @Test
    void doesNotSendWhenBalanceAboveThreshold() {
        snapshot.setBalance(new BigDecimal("50"));

        service.checkAndNotify();

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
        verify(qqMessagePushService, never()).push(any(), anyString());
    }

    @Test
    void pushesToQqWhenMailHasNoRecipient() {
        // 只配了 QQ：邮件不发，但提醒必须照发
        when(mailRecipientRepository.findByScene(MailScene.BALANCE_ALERT)).thenReturn(List.of());
        when(qqMessagePushService.push(eq(MailScene.BALANCE_ALERT), anyString())).thenReturn(1);

        service.checkAndNotify();

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(qqMessagePushService).push(eq(MailScene.BALANCE_ALERT), text.capture());
        assertThat(text.getValue())
                .contains("余额不足提醒")
                .contains("测试平台")
                .contains("tester：$3");
        assertThat(settings.getLastAlertAt()).isNotNull();
    }

    @Test
    void doesNotStartCooldownWhenNoChannelDelivered() {
        // 邮件没配、QQ 也没配好：本轮不算提醒过，下一轮还要接着试
        when(mailRecipientRepository.findByScene(MailScene.BALANCE_ALERT)).thenReturn(List.of());
        when(qqMessagePushService.push(any(), anyString())).thenReturn(0);

        service.checkAndNotify();
        service.checkAndNotify();

        assertThat(settings.getLastAlertAt()).isNull();
        verify(qqMessagePushService, times(2)).push(eq(MailScene.BALANCE_ALERT), anyString());
    }

    @Test
    void pushesToQqEvenWhenMailSendingFails() {
        // 邮件发失败不能把 QQ 推送一起带走
        doThrow(BusinessException.of("邮件发送失败：连接超时"))
                .when(mailService).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
        when(qqMessagePushService.push(eq(MailScene.BALANCE_ALERT), anyString())).thenReturn(2);

        service.checkAndNotify();

        verify(qqMessagePushService).push(eq(MailScene.BALANCE_ALERT), anyString());
        assertThat(settings.getLastAlertAt()).isNotNull();
    }
}
