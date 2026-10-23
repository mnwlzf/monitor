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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Sub2API 错误突增告警。
 *
 * <p>开关、阈值和接收人都来自页面配置；同一告警在冷却期内不重复发送。</p>
 */
@Service
public class BotErrorAlertService {

    private static final Logger log = LoggerFactory.getLogger(BotErrorAlertService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Sub2ErrorReader reader;
    private final BotSettingsService settingsService;
    private final QqBotProperties botProperties;
    private final QqRecipientRepository recipientRepository;
    private final OneBotClient oneBotClient;

    private volatile Instant lastSentAt;

    public BotErrorAlertService(Sub2ErrorReader reader,
                                BotSettingsService settingsService,
                                QqBotProperties botProperties,
                                QqRecipientRepository recipientRepository,
                                OneBotClient oneBotClient) {
        this.reader = reader;
        this.settingsService = settingsService;
        this.botProperties = botProperties;
        this.recipientRepository = recipientRepository;
        this.oneBotClient = oneBotClient;
    }

    /** 定时任务入口。 */
    public void checkAndNotify() {
        BotSettings settings = settingsService.current();
        if (!settings.enabled() || !settings.errorAlertEnabled()
                || !botProperties.isConfigured() || !reader.isAvailable()) {
            return;
        }

        List<QqRecipientEntity> recipients;
        try {
            recipients = recipientRepository.findByScene(MailScene.ERROR_ALERT);
        } catch (RuntimeException ex) {
            log.warn("读取 Sub2API 错误告警接收人失败: {}", ex.getMessage());
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        if (inCooldown(settings.errorAlertCooldownMinutes())) {
            return;
        }

        int minutes = Math.max(1, settings.errorAlertWindowMinutes());
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime currentFrom = now.minusMinutes(minutes);
        OffsetDateTime previousFrom = currentFrom.minusMinutes(minutes);

        try {
            ErrorFilter filter = ErrorFilter.everything(null);
            ErrorSummary current = reader.summary(currentFrom, now, filter);
            ErrorSummary previous = reader.summary(previousFrom, currentFrom, filter);
            long currentIngress = reader.ingressRejectCount(currentFrom, now);
            long previousIngress = reader.ingressRejectCount(previousFrom, currentFrom);

            boolean errorSpike = spike(current.totalErrors(), previous.totalErrors(),
                    settings.errorAlertMinCount(), settings.errorAlertSpikeRatio());
            boolean rateSpike = spike(current.rateLimited(), previous.rateLimited(),
                    settings.errorAlertMinCount(), settings.errorAlertSpikeRatio());
            boolean ingressSpike = spike(currentIngress, previousIngress,
                    settings.errorAlertMinCount(), settings.errorAlertSpikeRatio());
            if (!errorSpike && !rateSpike && !ingressSpike) {
                return;
            }

            List<ErrorFingerprint> fingerprints = reader.topFingerprints(
                    currentFrom, now, filter, 1);
            String message = buildMessage(minutes, current, previous,
                    currentIngress, previousIngress, fingerprints);
            boolean sent = send(message, recipients);
            if (sent) {
                lastSentAt = Instant.now();
                log.info("已发送 Sub2API 错误告警: window={}m, errors={}, previous={}, ingress={}, recipients={}",
                        minutes, current.totalErrors(), previous.totalErrors(),
                        currentIngress, recipients.size());
            }
        } catch (RuntimeException ex) {
            log.warn("检查 Sub2API 错误告警失败: {}", ex.getMessage());
        }
    }

    private static boolean spike(long current, long previous, int minCount, double spikeRatio) {
        int min = Math.max(1, minCount);
        if (current < min) {
            return false;
        }
        double ratio = Double.isFinite(spikeRatio) && spikeRatio >= 1.0 ? spikeRatio : 1.5;
        return previous == 0 || current >= previous * ratio;
    }

    private boolean inCooldown(int cooldownMinutes) {
        Instant last = lastSentAt;
        if (last == null) {
            return false;
        }
        int minutes = Math.max(1, cooldownMinutes);
        return last.plus(Duration.ofMinutes(minutes)).isAfter(Instant.now());
    }

    private boolean send(String message, List<QqRecipientEntity> recipients) {
        boolean sent = false;
        for (QqRecipientEntity recipient : recipients) {
            if (recipient == null || recipient.getTargetId() == null) {
                continue;
            }
            Long id = recipient.getTargetId();
            try {
                if ("GROUP".equalsIgnoreCase(recipient.getTargetType())) {
                    sent |= oneBotClient.sendGroupMessage(id, message);
                } else if ("PRIVATE".equalsIgnoreCase(recipient.getTargetType())) {
                    sent |= oneBotClient.sendPrivateMessage(id, message);
                }
            } catch (RuntimeException ex) {
                log.warn("发送 Sub2API 错误告警到 {} {} 失败: {}",
                        recipient.getTargetType(), id, ex.getMessage());
            }
        }
        return sent;
    }

    private String buildMessage(int minutes, ErrorSummary current, ErrorSummary previous,
                                long currentIngress, long previousIngress,
                                List<ErrorFingerprint> fingerprints) {
        StringBuilder text = new StringBuilder();
        text.append("【Sub2API 错误告警】近 ").append(minutes).append(" 分钟\n");
        text.append("错误：").append(current.totalErrors())
                .append("（上一周期 ").append(previous.totalErrors()).append("）\n");
        text.append("429/529：").append(current.rateLimited())
                .append("（上一周期 ").append(previous.rateLimited()).append("）\n");
        text.append("入口拒绝：").append(currentIngress)
                .append("（上一周期 ").append(previousIngress).append("）\n");
        if (!fingerprints.isEmpty()) {
            ErrorFingerprint top = fingerprints.get(0);
            text.append("主要指纹：").append(fingerprintLabel(top))
                    .append("，").append(top.count()).append(" 次")
                    .append("，影响 ").append(top.affectedUsers()).append(" 用户 / ")
                    .append(top.affectedAccounts()).append(" 账号\n");
        }
        text.append("查看：/诊断 ").append(minutes).append("m");
        text.append("  或  /错误指纹 ").append(minutes).append("m");
        return text.toString();
    }

    private static String fingerprintLabel(ErrorFingerprint fingerprint) {
        String code = fingerprint.providerErrorCode();
        String label = (fingerprint.phase() == null ? "" : fingerprint.phase())
                + "/" + (fingerprint.type() == null ? "" : fingerprint.type())
                + " " + fingerprint.statusCode();
        return code == null || code.isBlank() ? label : label + " " + code;
    }
}
