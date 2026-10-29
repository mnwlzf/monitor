package com.monitor.platform.ops;

import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
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

    private final QqBotProperties botProperties;
    private final QqRecipientRepository recipientRepository;
    private final OneBotClient oneBotClient;

    public OpsIncidentNotifier(QqBotProperties botProperties,
                               QqRecipientRepository recipientRepository,
                               OneBotClient oneBotClient) {
        this.botProperties = botProperties;
        this.recipientRepository = recipientRepository;
        this.oneBotClient = oneBotClient;
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

    private String buildMessage(OpsIncidentEntity incident) {
        return "【智能运维事故】" + incident.getSeverity() + "\n"
                + "编号：#" + incident.getId() + "\n"
                + "故障：" + incident.getTitle() + "\n"
                + "影响：" + (incident.getImpactSummary() == null ? "—" : incident.getImpactSummary()) + "\n"
                + "查看：/事故 #" + incident.getId() + "\n"
                + "生成计划：/自愈 #" + incident.getId();
    }
}