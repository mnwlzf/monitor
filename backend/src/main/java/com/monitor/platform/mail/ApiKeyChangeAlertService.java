package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 正在使用密钥的变更提醒服务。
 *
 * <p>密钥采集任务结束后调用：汇总本轮采集过程中「正在使用」密钥发生的变更，
 * 向页面维护的密钥变更收件人发送提醒邮件。</p>
 *
 * <p>这里的「正在使用」= <strong>本平台号池账号绑定了这把上游 Key</strong>（我自己的平台在用），
 * 不是「上游把它标成 active」—— 后者只是「活跃」（见 {@code V30__bind_in_use_key_changes.sql}）。</p>
 *
 * <p>只发送本轮新产生的变更：以任务开始时间为界，避免同一批变更被重复提醒。
 * 该方法不会抛出异常：发信失败只记录日志，避免影响采集任务本身的执行状态。</p>
 */
@Service
public class ApiKeyChangeAlertService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyChangeAlertService.class);

    /** 单封提醒邮件最多包含的变更条数，防止异常数据导致邮件过大。 */
    private static final int MAX_EVENTS = 200;

    private final UpstreamChangeEventRepository changeEventRepository;
    private final AccountNameResolver accountNameResolver;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;

    public ApiKeyChangeAlertService(UpstreamChangeEventRepository changeEventRepository,
                                    AccountNameResolver accountNameResolver,
                                    MailRecipientRepository mailRecipientRepository,
                                    SmtpConfigProvider smtpConfigProvider,
                                    MailService mailService) {
        this.changeEventRepository = changeEventRepository;
        this.accountNameResolver = accountNameResolver;
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
        html.append("<p>以下 <strong>正在使用</strong> 的密钥（已绑定到本平台号池账号）发生了变更，"
                + "请及时确认是否为预期操作。</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">时间</th>")
                .append("<th align=\"left\">平台</th>")
                .append("<th align=\"left\">账号</th>")
                .append("<th align=\"left\">本平台账号</th>")
                .append("<th align=\"left\">类型</th>")
                .append("<th align=\"left\">变化</th>")
                .append("<th align=\"left\">说明</th>")
                .append("</tr>");
        for (UpstreamChangeEventEntity event : events) {
            html.append("<tr>")
                    .append("<td>").append(MailFormat.formatTime(event.getDetectedAt())).append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.platformName(event.getAccountId(), platformNames)))
                    .append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.accountName(event.getAccountId(), accountNames))).append("</td>")
                    .append("<td>").append(MailFormat.escape(localAccount(event))).append("</td>")
                    .append("<td>").append(MailFormat.escape(changeLabel(event.getChangeType()))).append("</td>")
                    .append("<td>").append(changeDescription(event)).append("</td>")
                    .append("<td>").append(MailFormat.escape(event.getMessage())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 密钥采集任务自动发送。</p>");
        return html.toString();
    }

    /**
     * 正在使用这把密钥的本平台号池账号（页面上的「本平台账号」）。
     *
     * <p>事件查询已按绑定关系算好（{@code local_key_names}），口径就是 in_use 那一套，
     * 所以只要发了这封邮件，这里正常都能拿到账号名。</p>
     */
    private static String localAccount(UpstreamChangeEventEntity event) {
        String names = event.getLocalKeyNames();
        if (names == null || names.isBlank() || "未绑定本平台 Key".equals(names)) {
            return "—";
        }
        return names;
    }

    /**
     * 变更列的展示文案。
     *
     * <p>新增/轮换类事件的前后值往往是整块上游响应（很长，且包含密钥明文），这里只给结论，
     * 既不把邮件刷屏、也不把密钥写进邮件；只有标量字段才展示「旧值 → 新值」。</p>
     */
    private String changeDescription(UpstreamChangeEventEntity event) {
        if (isRawPayload(event.getOldValue()) || isRawPayload(event.getNewValue())) {
            return MailFormat.escape(summaryLabel(event));
        }
        String oldValue = MailFormat.scalar(event.getOldValue());
        String newValue = MailFormat.scalar(event.getNewValue());
        if (oldValue == null && newValue == null) {
            return MailFormat.escape(summaryLabel(event));
        }
        return MailFormat.escape(fieldLabel(event.getFieldName())) + "：" + MailFormat.escape(readable(oldValue))
                + " → " + MailFormat.escape(readable(newValue));
    }

    /** 整块 JSON / 超长文本视为原始响应，不直接展示。 */
    private boolean isRawPayload(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.isEmpty() || "null".equals(text)) {
            return false;
        }
        if (text.length() > 120) {
            return true;
        }
        return (text.startsWith("{") && text.endsWith("}"))
                || (text.startsWith("[") && text.endsWith("]"));
    }

    /** 无标量可对比时给出的结论文案。 */
    private String summaryLabel(UpstreamChangeEventEntity event) {
        String changeType = event.getChangeType();
        if (changeType == null) {
            return "内容已更新（明细见变更记录页）";
        }
        return switch (changeType) {
            case "API_KEY_ADDED" -> "新增密钥（明细见变更记录页）";
            case "API_KEY_REMOVED" -> "密钥已失效";
            case "API_KEY_ROTATED" -> "密钥已轮换";
            case "API_KEY_UPDATED" -> "密钥信息已更新（明细见变更记录页）";
            default -> "内容已更新（明细见变更记录页）";
        };
    }

    /** 标量值的可读展示：空值用破折号，超长截断。 */
    private String readable(String value) {
        if (value == null) {
            return "—";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
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
}
