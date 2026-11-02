package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.common.util.SecretMasker;
import com.monitor.platform.mail.MailScene;
import com.monitor.platform.notify.QqRecipientEntity;
import com.monitor.platform.notify.QqRecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 新事故 QQ 通知。 */
@Service
public class OpsIncidentNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsIncidentNotifier.class);
    private static final int MAX_RESPONSE_CHARS = 500;
    private static final List<String> RESPONSE_KEYS = List.of(
            "sampleMessage", "sample_message", "responseBody", "response_body",
            "response", "errorMessage", "error_message", "detail", "issue", "message");

    private final QqBotProperties botProperties;
    private final QqRecipientRepository recipientRepository;
    private final OneBotClient oneBotClient;
    private final ObjectMapper objectMapper;

    public OpsIncidentNotifier(QqBotProperties botProperties,
                               QqRecipientRepository recipientRepository,
                               OneBotClient oneBotClient,
                               ObjectMapper objectMapper) {
        this.botProperties = botProperties;
        this.recipientRepository = recipientRepository;
        this.oneBotClient = oneBotClient;
        this.objectMapper = objectMapper;
    }

    public void notifyNewIncident(OpsIncidentEntity incident) {
        if (incident == null || !botProperties.isConfigured()) {
            return;
        }
        List<QqRecipientEntity> recipients;
        try {
            recipients = recipientRepository.findByScene(MailScene.ERROR_ALERT);
        } catch (RuntimeException ex) {
            log.warn("读取智能运维通知接收人失败: {}", ex.getMessage());
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        String text = buildMessage(incident);
        for (QqRecipientEntity recipient : recipients) {
            if (recipient == null || recipient.getTargetId() == null) {
                continue;
            }
            try {
                if ("GROUP".equalsIgnoreCase(recipient.getTargetType())) {
                    oneBotClient.sendGroupMessage(recipient.getTargetId(), text);
                } else if ("PRIVATE".equalsIgnoreCase(recipient.getTargetType())) {
                    oneBotClient.sendPrivateMessage(recipient.getTargetId(), text);
                }
            } catch (RuntimeException ex) {
                log.warn("发送智能运维事故通知失败: target={}, reason={}",
                        recipient.getTargetId(), ex.getMessage());
            }
        }
    }

    String buildMessage(OpsIncidentEntity incident) {
        StringBuilder message = new StringBuilder("【智能运维事故】")
                .append(incident.getSeverity()).append('\n')
                .append("编号：#").append(incident.getId()).append('\n')
                .append("故障：").append(incident.getTitle()).append('\n')
                .append("影响：").append(incident.getImpactSummary() == null ? "—" : incident.getImpactSummary());
        if ("P1".equalsIgnoreCase(incident.getSeverity())) {
            message.append('\n').append("响应内容：").append(responseContent(incident));
        }
        message.append('\n').append("总览：/监控")
                .append('\n').append("查看：/事故 #").append(incident.getId())
                .append('\n').append("生成计划：管理员私聊发送 /自愈 #").append(incident.getId());
        return message.toString();
    }

    private String responseContent(OpsIncidentEntity incident) {
        String evidence = incident.getEvidenceJson();
        if (evidence == null || evidence.isBlank()) {
            return "未记录";
        }
        try {
            JsonNode root = objectMapper.readTree(evidence);
            for (String key : RESPONSE_KEYS) {
                JsonNode value = root.get(key);
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                String text = value.isValueNode() ? value.asText("") : value.toString();
                if (text != null && !text.isBlank()) {
                    return clip(SecretMasker.redactSecrets(text.strip()));
                }
            }
        } catch (Exception ex) {
            log.debug("解析智能运维事故响应内容失败: incident={}, reason={}",
                    incident.getId(), ex.getMessage());
        }
        return "未记录";
    }

    private static String clip(String text) {
        return text.length() <= MAX_RESPONSE_CHARS
                ? text
                : text.substring(0, MAX_RESPONSE_CHARS) + "…";
    }
}
