package com.monitor.platform.notify;

import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.mail.MailScene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * QQ 消息推送 —— 邮件之外的第二个通知通道。
 *
 * <p>把一条<strong>纯文本</strong>消息推给「某个事件场景」下页面配置的群 / 私聊。
 * 收件人存在 {@code qq_recipients}，按场景区分，与邮件收件人各自独立：
 * 只配了 QQ 也照发，只配了邮件也照发。</p>
 *
 * <p>推送失败只记日志（OneBot 没接通、群号不存在、超时都算失败），
 * 绝不让采集任务跟着失败。</p>
 */
@Service
public class QqMessagePushService {

    private static final Logger log = LoggerFactory.getLogger(QqMessagePushService.class);

    private final QqRecipientRepository recipientRepository;
    private final OneBotClient oneBotClient;
    private final QqBotProperties properties;

    public QqMessagePushService(QqRecipientRepository recipientRepository,
                                OneBotClient oneBotClient,
                                QqBotProperties properties) {
        this.recipientRepository = recipientRepository;
        this.oneBotClient = oneBotClient;
        this.properties = properties;
    }

    /** 该场景配置的 QQ 接收人。 */
    public List<QqRecipientEntity> recipients(MailScene scene) {
        return recipientRepository.findByScene(scene);
    }

    /**
     * 把一条纯文本消息推给该场景下的全部 QQ 接收人。
     *
     * @param scene 事件场景
     * @param text  纯文本正文（QQ 消息不能直接发邮件里的 HTML）
     * @return 成功送达的条数；没配接收人、OneBot 未接通、全部失败都返回 0
     */
    public int push(MailScene scene, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        List<QqRecipientEntity> recipients = recipients(scene);
        if (recipients.isEmpty()) {
            log.info("{} 未配置 QQ 消息接收人，跳过 QQ 推送", scene);
            return 0;
        }
        if (!properties.isConfigured()) {
            log.warn("{} 配置了 {} 个 QQ 接收人，但 OneBot 未接通"
                            + "（monitor.bot.enabled / api-base-url / webhook-token），本次不推送",
                    scene, recipients.size());
            return 0;
        }

        String message = limit(text);
        int sent = 0;
        for (QqRecipientEntity recipient : recipients) {
            if (send(recipient, message)) {
                sent++;
            }
        }
        log.info("{} QQ 消息推送完成：成功 {}/{} 条", scene, sent, recipients.size());
        return sent;
    }

    /**
     * 按 QQ 单条消息长度限制截断（与机器人回复共用 max-reply-length）。
     *
     * <p>超长消息有的 OneBot 实现会直接拒收，整条提醒就丢了；截断至少把开头
     * 最重要的「哪些平台低于阈值」发出去，并注明完整内容在哪。</p>
     */
    private String limit(String text) {
        int max = properties.getMaxReplyLength();
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（内容过长已截断，完整内容见邮件或页面）";
    }

    /** 发给单个接收人；类型无法识别、OneBot 拒绝都算失败。 */
    private boolean send(QqRecipientEntity recipient, String text) {
        QqTargetType type = QqTargetType.parse(recipient.getTargetType());
        Long targetId = recipient.getTargetId();
        if (type == null || targetId == null) {
            log.warn("QQ 接收人配置无法识别，跳过: id={}, type={}, targetId={}",
                    recipient.getId(), recipient.getTargetType(), targetId);
            return false;
        }
        return switch (type) {
            case GROUP -> oneBotClient.sendGroupMessage(targetId, text);
            case PRIVATE -> oneBotClient.sendPrivateMessage(targetId, text);
        };
    }
}
