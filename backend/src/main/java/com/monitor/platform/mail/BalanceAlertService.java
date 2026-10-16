package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.notify.QqMessagePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 余额不足提醒服务。
 *
 * <p>余额采集完成后调用：汇总各启用平台下启用账号的最新余额，若平台余额合计低于阈值，
 * 则向页面维护的收件人发送提醒。<strong>两个通道各自独立</strong>：邮件走 SMTP，
 * QQ 消息走 OneBot（见 {@link QqMessagePushService}）；只配了其中一个也照发，
 * 两个都没配好就不发送、也不占用提醒冷却。</p>
 *
 * <p>只要余额仍低于阈值，就每隔 {@code alert_interval_minutes} 分钟重复提醒一次
 * （默认 360 分钟 = 6 小时），用 {@code notification_settings.last_alert_at} 作为冷却判断依据；
 * 余额恢复到阈值以上后不再提醒，再次跌破会立即重新提醒。</p>
 *
 * <p>该方法不会抛出异常：发信失败只记录日志，避免影响采集任务本身的执行状态。</p>
 */
@Service
public class BalanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertService.class);

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountMetricSnapshotRepository metricSnapshotRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;
    private final QqMessagePushService qqMessagePushService;

    public BalanceAlertService(PlatformRepository platformRepository,
                               AccountRepository accountRepository,
                               AccountMetricSnapshotRepository metricSnapshotRepository,
                               NotificationSettingsRepository notificationSettingsRepository,
                               MailRecipientRepository mailRecipientRepository,
                               SmtpConfigProvider smtpConfigProvider,
                               MailService mailService,
                               QqMessagePushService qqMessagePushService) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
        this.qqMessagePushService = qqMessagePushService;
    }

    /**
     * 检查各平台余额并按需发送提醒（邮件 + QQ）。
     */
    public void checkAndNotify() {
        try {
            NotificationSettingsEntity settings = notificationSettingsRepository.get();
            if (!Boolean.TRUE.equals(settings.getBalanceAlertEnabled())) {
                return;
            }
            BigDecimal threshold = settings.getBalanceThreshold() == null
                    ? NotificationSettingsRepository.DEFAULT_THRESHOLD
                    : settings.getBalanceThreshold();
            int intervalMinutes = settings.getAlertIntervalMinutes() == null || settings.getAlertIntervalMinutes() <= 0
                    ? NotificationSettingsRepository.DEFAULT_ALERT_INTERVAL_MINUTES
                    : settings.getAlertIntervalMinutes();

            List<PlatformBalance> lowPlatforms = new ArrayList<>();
            for (PlatformEntity platform : platformRepository.findEnabled()) {
                PlatformBalance snapshot = summarize(platform);
                if (snapshot != null && snapshot.total().compareTo(threshold) < 0) {
                    lowPlatforms.add(snapshot);
                }
            }
            if (lowPlatforms.isEmpty()) {
                // 没有低余额平台：不提醒
                return;
            }

            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime lastAlertAt = settings.getLastAlertAt();
            if (lastAlertAt != null && lastAlertAt.plusMinutes(intervalMinutes).isAfter(now)) {
                log.debug("未到重复提醒间隔，跳过: lastAlertAt={}, intervalMinutes={}", lastAlertAt, intervalMinutes);
                return;
            }

            // 邮件与 QQ 是两条独立的通道：一边没配好 / 发失败，另一边照发。
            boolean mailSent = sendMail(buildSubject(lowPlatforms.size()), buildBody(lowPlatforms, threshold));
            int pushed = qqMessagePushService.push(
                    MailScene.BALANCE_ALERT, buildTextBody(lowPlatforms, threshold));

            List<Integer> platformIds = lowPlatforms.stream().map(item -> item.platform().getId()).toList();
            if (!mailSent && pushed == 0) {
                // 两个通道都没送达：不占用提醒冷却，等下一轮采集接着试
                log.info("余额提醒没有任何通道送达（邮件 / QQ 未配置或全部失败），本次不更新提醒时间: lowPlatforms={}",
                        platformIds);
                return;
            }

            settings.setLastAlertAt(now);
            settings.setUpdatedAt(now);
            notificationSettingsRepository.save(settings);
            log.info("余额提醒已发送: mail={}, qqPushCount={}, lowPlatforms={}, intervalMinutes={}",
                    mailSent, pushed, platformIds, intervalMinutes);
        } catch (Exception ex) {
            log.error("余额提醒检查失败", ex);
        }
    }

    /**
     * 汇总单个平台下启用账号的最新余额；无任何余额数据时返回 null（不参与判断）。
     */
    private PlatformBalance summarize(PlatformEntity platform) {
        List<AccountBalance> accounts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean hasBalance = false;
        for (AccountEntity account : accountRepository.findEnabledByPlatformId(platform.getId())) {
            BigDecimal balance = metricSnapshotRepository.findLatest(account.getId())
                    .map(snapshot -> snapshot.getBalance())
                    .orElse(null);
            if (balance == null) {
                continue;
            }
            hasBalance = true;
            total = total.add(balance);
            accounts.add(new AccountBalance(MailFormat.displayName(account), balance));
        }
        return hasBalance ? new PlatformBalance(platform, total, accounts) : null;
    }

    /**
     * 发余额提醒邮件。
     *
     * @return 是否真的发出去了：没配收件人、SMTP 没配好、发信失败都返回 false
     */
    private boolean sendMail(String subject, String htmlBody) {
        List<MailRecipientEntity> recipients = mailRecipientRepository.findByScene(MailScene.BALANCE_ALERT);
        if (recipients.isEmpty()) {
            log.info("余额提醒未配置邮件收件人，跳过邮件");
            return false;
        }
        SmtpConfig smtpConfig = smtpConfigProvider.load();
        if (!smtpConfig.isConfigured()) {
            log.warn("余额提醒配置了 {} 个邮件收件人，但 SMTP 未配置，跳过邮件", recipients.size());
            return false;
        }
        List<String> to = recipients.stream().map(MailRecipientEntity::getEmail).toList();
        try {
            mailService.send(smtpConfig, to, subject, htmlBody);
            return true;
        } catch (Exception ex) {
            // 邮件失败不能把 QQ 推送一起带走
            log.error("余额提醒邮件发送失败: to={}", to, ex);
            return false;
        }
    }

    /**
     * QQ 用的纯文本正文。
     *
     * <p>邮件正文是 HTML，QQ 消息不能直接发 HTML —— 这里单独拼一份纯文本：
     * 一个平台一行，账号明细用「、」连接。</p>
     */
    private String buildTextBody(List<PlatformBalance> lowPlatforms, BigDecimal threshold) {
        StringBuilder text = new StringBuilder();
        text.append("【余额不足提醒】以下平台的账号余额合计已低于阈值 $")
                .append(money(threshold)).append("，请及时充值。");
        for (PlatformBalance item : lowPlatforms) {
            text.append("\n\n· ").append(item.platform().getPlatformName())
                    .append("（").append(item.platform().getPlatformType()).append("）合计 $")
                    .append(money(item.total()));
            if (item.accounts().isEmpty()) {
                text.append("\n    无账号明细");
            } else {
                text.append("\n    ").append(item.accounts().stream()
                        .map(account -> account.name() + "：$" + money(account.balance()))
                        .collect(Collectors.joining("、")));
            }
        }
        text.append("\n\n余额持续低于阈值会按提醒间隔重复提醒。");
        return text.toString();
    }

    /** 金额去掉无意义的尾零：5.0000 → 5。 */
    private static String money(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String buildSubject(int platformCount) {
        return "[Monitor] 平台余额不足提醒（" + platformCount + " 个平台）";
    }

    private String buildBody(List<PlatformBalance> lowPlatforms, BigDecimal threshold) {
        String thresholdText = money(threshold);
        StringBuilder html = new StringBuilder();
        html.append("<p>以下平台的账号余额合计已低于阈值 <strong>$").append(thresholdText)
                .append("</strong>，请及时充值。</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">平台</th>")
                .append("<th align=\"left\">类型</th>")
                .append("<th align=\"right\">余额合计</th>")
                .append("<th align=\"left\">账号明细</th>")
                .append("</tr>");
        for (PlatformBalance item : lowPlatforms) {
            html.append("<tr>")
                    .append("<td>").append(MailFormat.escape(item.platform().getPlatformName())).append("</td>")
                    .append("<td>").append(MailFormat.escape(item.platform().getPlatformType())).append("</td>")
                    .append("<td align=\"right\">$").append(money(item.total())).append("</td>")
                    .append("<td>").append(accountBreakdown(item.accounts())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 余额采集任务自动发送；余额持续低于阈值会按提醒间隔重复发送。</p>");
        return html.toString();
    }

    private String accountBreakdown(List<AccountBalance> accounts) {
        if (accounts.isEmpty()) {
            return "—";
        }
        return accounts.stream()
                .map(account -> MailFormat.escape(account.name()) + "：$" + money(account.balance()))
                .collect(Collectors.joining("<br/>"));
    }

    /** 账号余额明细。 */
    private record AccountBalance(String name, BigDecimal balance) {
    }

    /** 平台余额汇总。 */
    private record PlatformBalance(PlatformEntity platform, BigDecimal total, List<AccountBalance> accounts) {
    }
}