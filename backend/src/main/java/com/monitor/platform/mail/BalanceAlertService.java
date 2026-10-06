package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 余额不足提醒服务。
 *
 * <p>余额采集完成后调用：汇总各启用平台下启用账号的最新余额，若平台余额合计低于阈值，
 * 则向页面维护的收件人发送提醒邮件。</p>
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

    public BalanceAlertService(PlatformRepository platformRepository,
                               AccountRepository accountRepository,
                               AccountMetricSnapshotRepository metricSnapshotRepository,
                               NotificationSettingsRepository notificationSettingsRepository,
                               MailRecipientRepository mailRecipientRepository,
                               SmtpConfigProvider smtpConfigProvider,
                               MailService mailService) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
    }

    /**
     * 检查各平台余额并按需发送提醒邮件。
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

            List<MailRecipientEntity> recipients = mailRecipientRepository.findByScene(MailScene.BALANCE_ALERT);
            if (recipients.isEmpty()) {
                log.info("检测到低余额平台但未配置收件人，暂不发送: platformCount={}", lowPlatforms.size());
                return;
            }

            SmtpConfig smtpConfig = smtpConfigProvider.load();
            if (!smtpConfig.isConfigured()) {
                log.warn("检测到低余额平台但 SMTP 未配置，暂不发送: platformCount={}", lowPlatforms.size());
                return;
            }

            List<String> to = recipients.stream().map(MailRecipientEntity::getEmail).toList();
            mailService.send(smtpConfig, to, buildSubject(lowPlatforms.size()), buildBody(lowPlatforms, threshold));

            settings.setLastAlertAt(now);
            settings.setUpdatedAt(now);
            notificationSettingsRepository.save(settings);
            log.info("余额提醒邮件已发送: recipients={}, lowPlatforms={}, intervalMinutes={}",
                    to, lowPlatforms.stream().map(item -> item.platform().getId()).toList(), intervalMinutes);
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
            accounts.add(new AccountBalance(displayName(account), balance));
        }
        return hasBalance ? new PlatformBalance(platform, total, accounts) : null;
    }

    private String displayName(AccountEntity account) {
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return "账号 " + account.getId();
    }

    private String buildSubject(int platformCount) {
        return "[Monitor] 平台余额不足提醒（" + platformCount + " 个平台）";
    }

    private String buildBody(List<PlatformBalance> lowPlatforms, BigDecimal threshold) {
        String thresholdText = threshold.stripTrailingZeros().toPlainString();
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
                    .append("<td>").append(escape(item.platform().getPlatformName())).append("</td>")
                    .append("<td>").append(escape(item.platform().getPlatformType())).append("</td>")
                    .append("<td align=\"right\">$").append(item.total().stripTrailingZeros().toPlainString()).append("</td>")
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
                .map(account -> escape(account.name()) + "：$" + account.balance().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("<br/>"));
    }

    private String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    /** 账号余额明细。 */
    private record AccountBalance(String name, BigDecimal balance) {
    }

    /** 平台余额汇总。 */
    private record PlatformBalance(PlatformEntity platform, BigDecimal total, List<AccountBalance> accounts) {
    }
}