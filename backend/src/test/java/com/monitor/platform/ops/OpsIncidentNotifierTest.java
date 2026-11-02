package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.mail.MailScene;
import com.monitor.platform.notify.QqRecipientEntity;
import com.monitor.platform.notify.QqRecipientRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsIncidentNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QqBotProperties properties = configuredProperties();
    private final QqRecipientRepository recipients = mock(QqRecipientRepository.class);
    private final OneBotClient client = mock(OneBotClient.class);
    private final OpsIncidentNotifier notifier = new OpsIncidentNotifier(properties, recipients, client, mapper);

    @Test
    void shouldIncludeResponseContentForP1Incident() throws Exception {
        QqRecipientEntity recipient = new QqRecipientEntity();
        recipient.setTargetType("GROUP");
        recipient.setTargetId(100L);
        when(recipients.findByScene(MailScene.ERROR_ALERT)).thenReturn(List.of(recipient));

        OpsIncidentEntity incident = incident("P1", Map.of(
                "sampleMessage", "upstream returned 429: quota exhausted"));

        notifier.notifyNewIncident(incident);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).sendGroupMessage(eq(100L), message.capture());
        assertThat(message.getValue())
                .contains("【智能运维事故】P1")
                .contains("响应内容：upstream returned 429: quota exhausted");
    }

    @Test
    void shouldRedactSecretsInP1ResponseContent() throws Exception {
        QqRecipientEntity recipient = new QqRecipientEntity();
        recipient.setTargetType("PRIVATE");
        recipient.setTargetId(200L);
        when(recipients.findByScene(MailScene.ERROR_ALERT)).thenReturn(List.of(recipient));

        OpsIncidentEntity incident = incident("P1", Map.of(
                "responseBody", "Authorization: Bearer sk-secret123456789"));

        notifier.notifyNewIncident(incident);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).sendPrivateMessage(eq(200L), message.capture());
        assertThat(message.getValue())
                .contains("响应内容：")
                .contains("Bearer ****")
                .doesNotContain("secret123456789");
    }

    @Test
    void shouldNotIncludeResponseContentForNonP1Incident() throws Exception {
        QqRecipientEntity recipient = new QqRecipientEntity();
        recipient.setTargetType("GROUP");
        recipient.setTargetId(100L);
        when(recipients.findByScene(MailScene.ERROR_ALERT)).thenReturn(List.of(recipient));

        OpsIncidentEntity incident = incident("P2", Map.of("sampleMessage", "some detail"));

        notifier.notifyNewIncident(incident);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).sendGroupMessage(eq(100L), message.capture());
        assertThat(message.getValue()).doesNotContain("响应内容：");
        verify(client, never()).sendPrivateMessage(eq(200L), org.mockito.ArgumentMatchers.anyString());
    }

    private OpsIncidentEntity incident(String severity, Map<String, Object> evidence) throws Exception {
        OpsIncidentEntity incident = new OpsIncidentEntity();
        incident.setId(42L);
        incident.setSeverity(severity);
        incident.setTitle("上游服务错误（529）");
        incident.setImpactSummary("近 30 分钟 12 次，影响 2 用户 / 1 账号");
        incident.setEvidenceJson(mapper.writeValueAsString(evidence));
        return incident;
    }

    private static QqBotProperties configuredProperties() {
        QqBotProperties properties = new QqBotProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("secret");
        properties.setApiBaseUrl("http://127.0.0.1:3000");
        return properties;
    }
}
