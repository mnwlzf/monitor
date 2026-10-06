package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余额提醒测试：余额持续低于阈值时按间隔重复提醒，未到间隔不重复发送。
 */
class BalanceAlertServiceTest {

    private final MailService mailService = mock(MailService.class);
    private final NotificationSettingsEntity settings = new NotificationSettingsEntity();
    private final AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
    private BalanceAlertService service;

    @BeforeEach
    void setUp() {
        PlatformRepository platformRepository = mock(PlatformRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AccountMetricSnapshotRepository metricSnapshotRepository = mock(AccountMetricSnapshotRepository.class);
        NotificationSettingsRepository notificationSettingsRepository = mock(NotificationSettingsRepository.class);
        MailRecipientRepository mailRecipientRepository = mock(MailRecipientRepository.class);
        SmtpConfigProvider smtpConfigProvider = mock(SmtpConfigProvider.class);

        service = new BalanceAlertService(
                platformRepository, accountRepository, metricSnapshotRepository,
                notificationSettingsRepository, mailRecipientRepository, smtpConfigProvider, mailService);

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

        MailRecipientEntity recipient = new MailRecipientEntity();
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
    }
}