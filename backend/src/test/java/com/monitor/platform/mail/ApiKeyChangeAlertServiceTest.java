package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正在使用密钥变更提醒测试。
 */
class ApiKeyChangeAlertServiceTest {

    private final MailService mailService = mock(MailService.class);
    private final UpstreamChangeEventRepository changeEventRepository = mock(UpstreamChangeEventRepository.class);
    private final MailRecipientRepository mailRecipientRepository = mock(MailRecipientRepository.class);
    private final SmtpConfigProvider smtpConfigProvider = mock(SmtpConfigProvider.class);
    private ApiKeyChangeAlertService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyChangeAlertService(
                changeEventRepository,
                mock(AccountRepository.class),
                mock(PlatformRepository.class),
                mailRecipientRepository,
                smtpConfigProvider,
                mailService);
    }

    @Test
    void sendsAlertForInUseKeyChanges() {
        when(changeEventRepository.findInUseKeyEventsSince(any(), anyInt()))
                .thenReturn(List.of(inUseKeyEvent()));
        when(mailRecipientRepository.findByScene(MailScene.API_KEY_CHANGE))
                .thenReturn(List.of(recipient()));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig());

        service.notifyInUseKeyChanges(OffsetDateTime.now().minusMinutes(1));

        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    @Test
    void skipsWhenNoInUseChanges() {
        when(changeEventRepository.findInUseKeyEventsSince(any(), anyInt())).thenReturn(List.of());

        service.notifyInUseKeyChanges(OffsetDateTime.now());

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    @Test
    void skipsWhenNoRecipientsConfigured() {
        when(changeEventRepository.findInUseKeyEventsSince(any(), anyInt()))
                .thenReturn(List.of(inUseKeyEvent()));
        when(mailRecipientRepository.findByScene(MailScene.API_KEY_CHANGE)).thenReturn(List.of());

        service.notifyInUseKeyChanges(OffsetDateTime.now());

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    @Test
    void skipsWhenSinceMissing() {
        service.notifyInUseKeyChanges(null);

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    private UpstreamChangeEventEntity inUseKeyEvent() {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setAccountId(7);
        event.setEntityType("API_KEY");
        event.setEntityKey("123");
        event.setChangeType("API_KEY_ROTATED");
        event.setFieldName("key");
        event.setInUse(true);
        event.setMessage("密钥已轮换");
        event.setDetectedAt(OffsetDateTime.now());
        return event;
    }

    private MailRecipientEntity recipient() {
        MailRecipientEntity recipient = new MailRecipientEntity();
        recipient.setEmail("ops@example.com");
        return recipient;
    }

    private SmtpConfig smtpConfig() {
        return new SmtpConfig(true, "smtp.example.com", 587, "user", "pw",
                "monitor@example.com", "Monitor", false);
    }
}