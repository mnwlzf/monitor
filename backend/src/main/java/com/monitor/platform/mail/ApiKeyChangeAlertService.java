package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 正在使用密钥的变更提醒服务。
 *
 * <p>密钥采集任务结束后调用：汇总本轮采集过程中「正在使用（启用）」密钥发生的变更，
 * 向页面维护的密钥变更收件人发送提醒邮件。</p>
 *
 * <p>只发送本轮新产生的变更：以任务开始时间为界，避免同一批变更被重复提醒。
 * 该方法不会抛出异常：发信失败只记录日志，避免影响采集任务本身的执行状态。</p>
 */
@Service
public class ApiKeyChangeAlertService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyChangeAlertService.class);

    /** 单封提醒邮件最多包含的变更条数，防止异常数据导致邮件过大。 */
    private static final int MAX_EVENTS = 200;

    /** 邮件展示时间使用与采集口径一致的时区。 */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UpstreamChangeEventRepository changeEventRepository;
    private final AccountRepository accountRepository;
    private final PlatformRepository platformRepository;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;

    public ApiKeyChangeAlertService(UpstreamChangeEventRepository changeEventRepository,
                                    AccountRepository accountRepository,
                                    PlatformRepository platformRepository,
                                    MailRecipientRepository mailRecipientRepository,
                                    SmtpConfigProvider smtpConfigProvider,
                                    MailService mailService) {
        this.changeEventRepository = changeEventRepository;
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
    }

    /**
     * 发送正在使用密钥的变更提醒邮件。
     *
     * @param since 本轮采集的开始时间；为空时跳过，避免误发历史变更
     */
    public void notifyInUseKeyChanges(OffsetDateTime since) {
        try {
            if (since == null) {
                return;
            }
            List<UpstreamChangeEventEntity> events =
                    changeEventRepository.findInUseKeyEventsSince(since, MAX_EVENTS);
            if (events.isEmpty()) {
                return;
            }

            List<MailRecipientEntity> recipients = mailRecipientRepository.findByScene(MailScene.API_KEY_CHANGE);
            if (recipients.isEmpty()) {
                log.info("检测到正在使用密钥的变更但未配置收件人，暂不发送: eventCount={}", events.size());
                return;
            }

            SmtpConfig smtpConfig = smtpConfigProvider.load();
            if (!smtpConfig.isConfigured()) {
                log.warn("检测到正在使用密钥的变更但 SMTP 未配置，暂不发送: eventCount={}", events.size());
                return;
            }

            Map<Integer, String> accountNames = new HashMap<>();
            Map<Integer, String> platformNames = new HashMap<>();
            List<String> to = recipients.stream().map(MailRecipientEntity::getEmail).toList();
            mailService.send(smtpConfig, to, buildSubject(events.size()),
                    buildBody(events, accountNames, platformNames));
            log.info("密钥变更提醒邮件已发送: recipients={}, eventCount={}", to, events.size());
        } catch (Exception ex) {
            log.error("密钥变更提醒发送失败", ex);
        }
    }

    private String buildSubject(int eventCount) {
        return "[Monitor] 正在使用的密钥发生变更（" + eventCount + " 项）";
    }

    private String buildBody(List<UpstreamChangeEventEntity> events,
                             Map<Integer, String> accountNames,
                             Map<Integer, String> platformNames) {
        StringBuilder html = new StringBuilder();
        html.append("<p>以下 <strong>正在使用</strong> 的密钥发生了变更，请及时确认是否为预期操作。</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">时间</th>")
                .append("<th align=\"left\">平台</th>")
                .append("<th align=\"left\">账号</th>")
                .append("<th align=\"left\">类型</th>")
                .append("<th align=\"left\">变化</th>")
                .append("<th align=\"left\">说明</th>")
                .append("</tr>");
        for (UpstreamChangeEventEntity event : events) {
            html.append("<tr>")
                    .append("<td>").append(formatTime(event.getDetectedAt())).append("</td>")
                    .append("<td>").append(escape(platformName(event.getAccountId(), platformNames)))
                    .append("</td>")
                    .append("<td>").append(escape(accountName(event.getAccountId(), accountNames))).append("</td>")
                    .append("<td>").append(escape(changeLabel(event.getChangeType()))).append("</td>")
                    .append("<td>").append(changeDescription(event)).append("</td>")
                    .append("<td>").append(escape(event.getMessage())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 密钥采集任务自动发送。</p>");
        return html.toString();
    }

    /** 展示字段变化：标签 + 旧值 → 新值；无标量对比时展示破折号。 */
    private String changeDescription(UpstreamChangeEventEntity event) {
        String oldValue = scalar(event.getOldValue());
        String newValue = scalar(event.getNewValue());
        if (oldValue == null && newValue == null) {
            return "—";
        }
        return escape(fieldLabel(event.getFieldName())) + "：" + escape(oldValue) + " → " + escape(newValue);
    }

    private String platformName(Integer accountId, Map<Integer, String> platformNames) {
        if (accountId == null) {
            return "未知平台";
        }
        return platformNames.computeIfAbsent(accountId, id -> accountRepository.findById(id)
                .map(AccountEntity::getPlatformId)
                .flatMap(platformRepository::findById)
                .map(PlatformEntity::getPlatformName)
                .orElse("未知平台"));
    }

    private String accountName(Integer accountId, Map<Integer, String> accountNames) {
        if (accountId == null) {
            return "未知账号";
        }
        return accountNames.computeIfAbsent(accountId, id -> accountRepository.findById(id)
                .map(this::displayName)
                .orElse("账号 " + id));
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

    /** 变更事件里存的是 JSON 文本，字符串会带引号，这里去掉引号便于阅读。 */
    private String scalar(String jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        String text = jsonValue.trim();
        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    private String formatTime(OffsetDateTime time) {
        return time == null ? "—" : TIME_FORMATTER.format(time.atZoneSameInstant(REPORT_ZONE));
    }

    private String changeLabel(String changeType) {
        if (changeType == null) {
            return "未知";
        }
        return switch (changeType) {
            case "API_KEY_ADDED" -> "密钥新增";
            case "API_KEY_REMOVED" -> "密钥失效";
            case "API_KEY_ROTATED" -> "密钥轮换";
            case "API_KEY_UPDATED" -> "密钥更新";
            default -> changeType;
        };
    }

    private String fieldLabel(String fieldName) {
        if (fieldName == null) {
            return "变化";
        }
        return switch (fieldName) {
            case "name" -> "密钥名称";
            case "status" -> "密钥状态";
            case "group" -> "所属分组";
            case "key" -> "密钥明文";
            case "is_active" -> "启用状态";
            default -> fieldName;
        };
    }

    private String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}