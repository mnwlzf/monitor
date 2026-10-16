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
        when(accountNameResolver.accountName(eq(7), any())).thenReturn("上游账号A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of(
                new RateChangeImpactService.ImpactRow(
                        "本平台账号 A", 11L, "上游 Key A", "分组 A", "openai",
                        new BigDecimal("1.20"), "Ic渠道",
                        new BigDecimal("1.10"), new BigDecimal("1.30"),
                        new BigDecimal("18.18"), false, "WARNING"),
                new RateChangeImpactService.ImpactRow(
                        "本平台账号 B", 12L, "上游 Key B", "分组 B", "anthropic",
                        new BigDecimal("1.00"), "Ic渠道",
                        new BigDecimal("1.10"), new BigDecimal("1.30"),
                        new BigDecimal("18.18"), true, "CRITICAL")));

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());

        assertThat(body.getValue())
                .contains("<th align=\"left\">上游平台</th>")
                .contains("<th align=\"left\">上游账号</th>")
                .contains("<th align=\"left\">上游渠道（分组）</th>")
                .contains("<th align=\"left\">本平台账号</th>")
                .contains("<th align=\"left\">上游 Key 名称</th>")
                .contains("<th align=\"left\">本平台分组（倍率）</th>")
                .contains("<td>上游账号A</td>")
                .contains("<td>本平台账号 A</td>")
                .contains("<td>上游 Key A</td>")
                .contains("<td>分组 A（openai &middot; 倍率 1.2）</td>")
                .contains("<td>本平台账号 B</td>")
                .contains("<td>上游 Key B</td>")
                .contains("<td>分组 B（anthropic &middot; 倍率 1）</td>")
                .contains("<th align=\"left\">上游倍率（原 → 新）</th>")
                .contains("<td>1.1 &rarr; 1.3</td>")
                .contains("<td>Ic渠道（ID group-1）</td>")
                .doesNotContain("<td>Ic渠道（1.1")
                .doesNotContain("聚合上游 Key A、聚合上游 Key B")
                .doesNotContain("聚合本平台 Key A、聚合本平台 Key B")
                .doesNotContain("大模型总结")
                // 有上调时必须点名「上调」，成本口径不能含糊
                .contains("其中 <b>1</b> 条为 <b>上调</b>");
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
        when(accountNameResolver.accountName(eq(7), any())).thenReturn("上游账号A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of());

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("<th align=\"left\">上游倍率（原 → 新）</th>")
                .contains("<td>0.3 &rarr; 0.6</td>")
                .contains("<td>Ic渠道（ID group-1）</td>")
                // 对不上分组时如实写「未匹配到」，不再回退成聚合的 Key 列表
                .contains("<td>未匹配到本平台账号</td>")
                .doesNotContain("聚合本平台 Key A、聚合本平台 Key B");
    }

    /**
     * 影响评估算不出来（例如号池账号还没归属任何 Sub2API 分组）时，
     * 至少要退回「这个渠道里的上游 Key 名称 + 绑定了它的本平台账号」——
     * 只写一句「未匹配到」等于没给任何可用信息。
     */
    @Test
    void shouldFallBackToChannelBindingWhenImpactIsEmpty() {
        UpstreamChangeEventEntity event = rateEvent();
        event.setChannelKeyNames("企业组");
        event.setChannelPoolAccounts("玩具AI-企业组");
        when(changeEventRepository.findInUseGroupRateEventsSince(any(), anyInt()))
                .thenReturn(List.of(event));
        when(mailRecipientRepository.findByScene(MailScene.CHANNEL_ALERT))
                .thenReturn(List.of(recipient()));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig());
        when(accountNameResolver.platformName(eq(7), any())).thenReturn("平台A");
        when(accountNameResolver.accountName(eq(7), any())).thenReturn("上游账号A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of());

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("<td>企业组</td>")
                .contains("<td>玩具AI-企业组</td>")
                .doesNotContain("未匹配到上游 Key 名称")
                .doesNotContain("未匹配到本平台账号");
    }

    @Test
    void shouldSayEverythingIsPriceCutWhenNoEventRises() {
        UpstreamChangeEventEntity event = rateEvent();
        event.setOldValue("0.3");
        event.setNewValue("0.23");
        when(changeEventRepository.findInUseGroupRateEventsSince(any(), anyInt()))
                .thenReturn(List.of(event));
        when(mailRecipientRepository.findByScene(MailScene.CHANNEL_ALERT))
                .thenReturn(List.of(recipient()));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig());
        when(accountNameResolver.platformName(eq(7), any())).thenReturn("平台A");
        when(accountNameResolver.accountName(eq(7), any())).thenReturn("上游账号A");
        when(rateChangeImpactService.impactOf(event)).thenReturn(List.of());

        service.notifyInUseRateChanges(OffsetDateTime.now().minusMinutes(1));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), subject.capture(), body.capture());
        assertThat(subject.getValue()).contains("全部为下调");
        assertThat(body.getValue())
                .contains("本次 <b>全部为下调</b>，成本不会因此上升")
                .doesNotContain("会直接推高成本");
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