package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.pool.RateChangeImpactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道倍率告警：一条事件有多个影响时，邮件也必须逐行一一对应。
 */
class ChannelChangeAlertServiceTest {

    private final UpstreamChangeEventRepository changeEventRepository = mock(UpstreamChangeEventRepository.class);
    private final AccountNameResolver accountNameResolver = mock(AccountNameResolver.class);
    private final MailRecipientRepository mailRecipientRepository = mock(MailRecipientRepository.class);
    private final SmtpConfigProvider smtpConfigProvider = mock(SmtpConfigProvider.class);
    private final MailService mailService = mock(MailService.class);
    private final RateChangeImpactService rateChangeImpactService = mock(RateChangeImpactService.class);

    private ChannelChangeAlertService service;

    @BeforeEach
    void setUp() {
        service = new ChannelChangeAlertService(
                changeEventRepository,
                accountNameResolver,
                mailRecipientRepository,
                smtpConfigProvider,
                mailService,
                rateChangeImpactService);
    }

    @Test
    void shouldRenderEachImpactAsItsOwnRowWithoutAggregatedKeys() {
        UpstreamChangeEventEntity event = rateEvent();
        when(changeEventRepository.findInUseGroupRateEventsSince(any(), anyInt()))
                .thenReturn(List.of(event));
        when(mailRecipientRepository.findByScene(MailScene.CHANNEL_ALERT))
                .thenReturn(List.of(recipient()));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig());
        when(accountNameResolver.platformName(eq(7), any())).thenReturn("平台A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of(
                new RateChangeImpactService.ImpactRow(
                        "本平台 Key A", 11L, "上游 Key A", "分组 A",
                        new BigDecimal("1.20"), "Ic渠道",
                        new BigDecimal("1.10"), new BigDecimal("1.30"),
                        new BigDecimal("18.18"), false, "WARNING"),
                new RateChangeImpactService.ImpactRow(
                        "本平台 Key B", 12L, "上游 Key B", "分组 B",
                        new BigDecimal("1.00"), "Ic渠道",
                        new BigDecimal("1.10"), new BigDecimal("1.30"),
                        new BigDecimal("18.18"), true, "CRITICAL")));

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());

        assertThat(body.getValue())
                .contains("<td>本平台 Key A</td>")
                .contains("<td>上游 Key A</td>")
                .contains("<td>分组 A</td>")
                .contains("<td>本平台 Key B</td>")
                .contains("<td>上游 Key B</td>")
                .contains("<td>分组 B</td>")
                .contains("<th align=\"left\">上游倍率（原 → 新）</th>")
                .contains("<td>1.1 &rarr; 1.3</td>")
                .contains("<td>Ic渠道</td>")
                .doesNotContain("<td>Ic渠道（1.1")
                .doesNotContain("聚合上游 Key A、聚合上游 Key B")
                .doesNotContain("聚合本平台 Key A、聚合本平台 Key B")
                .doesNotContain("大模型总结");
    }

    @Test
    void shouldShowUpstreamRatioWhenNoImpactRows() {
        UpstreamChangeEventEntity event = rateEvent();
        event.setOldValue("0.3");
        event.setNewValue("0.6");
        when(changeEventRepository.findInUseGroupRateEventsSince(any(), anyInt()))
                .thenReturn(List.of(event));
        when(mailRecipientRepository.findByScene(MailScene.CHANNEL_ALERT))
                .thenReturn(List.of(recipient()));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig());
        when(accountNameResolver.platformName(eq(7), any())).thenReturn("平台A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of());

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("<th align=\"left\">上游倍率（原 → 新）</th>")
                .contains("<td>0.3 &rarr; 0.6</td>")
                .contains("<td>Ic渠道</td>");
    }

    private UpstreamChangeEventEntity rateEvent() {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setId(1L);
        event.setAccountId(7);
        event.setEntityType("GROUP");
        event.setEntityKey("group-1");
        event.setGroupName("Ic渠道");
        event.setChangeType("RATE_CHANGED");
        event.setSeverity("WARNING");
        event.setMessage("渠道倍率发生变化");
        event.setDetectedAt(OffsetDateTime.now());
        event.setOldValue("\"1.10\"");
        event.setNewValue("\"1.30\"");
        // 故意设置聚合字段，验证邮件不会再使用它们。
        event.setUpstreamKeyNames("聚合上游 Key A、聚合上游 Key B");
        event.setLocalKeyNames("聚合本平台 Key A、聚合本平台 Key B");
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