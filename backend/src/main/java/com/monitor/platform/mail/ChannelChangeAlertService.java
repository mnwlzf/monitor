package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.RateChangeImpactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道变更提醒服务。
 *
 * <p>两类邮件，收件人各自配置（{@link MailScene}）：</p>
 * <ul>
 *     <li><b>实时告警</b>（{@link #notifyInUseRateChanges}）：<strong>正在使用</strong>的渠道发生
 *         <strong>倍率变化</strong>时立刻发 —— 涨价直接关系成本，不能等；</li>
 *     <li><b>每日汇总</b>（{@link #sendDailyDigest}）：非在用渠道的变更每天一封，
 *         避免它们把实时告警淹掉。</li>
 * </ul>
 *
 * <p><strong>正文由大模型总结</strong>：把原始变更读成人话再发。但模型只是「锦上添花」——
 * 没配模型、调用失败、返回为空，都会自动退回原始明细表格，
 * <strong>绝不因为模型问题导致该发的告警发不出去</strong>。</p>
 *
 * <p>本类所有方法都不抛异常：发信失败只记日志，不拖累采集任务。</p>
 */
@Service
public class ChannelChangeAlertService {

    private static final Logger log = LoggerFactory.getLogger(ChannelChangeAlertService.class);

    /** 单封邮件最多包含的变更条数，防止异常数据把邮件撑爆。 */
    private static final int MAX_EVENTS = 200;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是运维助手，负责把上游渠道的变更记录总结成给运维看的中文邮件正文。
            要求：
            1. 只讲记录里的事实，不编造、不猜测原因；
            2. 优先说清楚「哪些渠道的倍率变了、是涨还是降、涨了多少」——这直接关系成本；
            3. 直接输出正文，不要 Markdown 标题、表格或代码块；
            4. 控制在 200 字以内，能一眼看完。
            """;

    private final UpstreamChangeEventRepository changeEventRepository;
    private final AccountNameResolver accountNameResolver;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final RateChangeImpactService rateChangeImpactService;

    public ChannelChangeAlertService(UpstreamChangeEventRepository changeEventRepository,
                                     AccountNameResolver accountNameResolver,
                                     MailRecipientRepository mailRecipientRepository,
                                     SmtpConfigProvider smtpConfigProvider,
                                     MailService mailService,
                                     ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                     RateChangeImpactService rateChangeImpactService) {
        this.changeEventRepository = changeEventRepository;
        this.accountNameResolver = accountNameResolver;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
        this.chatClientBuilder = chatClientBuilder;
        this.rateChangeImpactService = rateChangeImpactService;
    }

    /**
     * 正在使用渠道的倍率变化 —— 实时告警。
     *
     * <p>渠道采集任务结束后调用，以任务开始时间为界，只发本轮新产生的变化，
     * 避免同一批变更被反复提醒。</p>
     *
     * @param since 本轮采集开始时间；为空时跳过，避免误发历史变更
     */
    public void notifyInUseRateChanges(OffsetDateTime since) {
        if (since == null) {
            return;
        }
        try {
            List<UpstreamChangeEventEntity> events =
                    changeEventRepository.findInUseGroupRateEventsSince(since, MAX_EVENTS);
            if (events.isEmpty()) {
                return;
            }
            String subject = "[Monitor] 正在使用的渠道倍率变化（" + events.size() + " 项）";
            send(MailScene.CHANNEL_ALERT, events, subject,
                    "以下 <b>正在使用</b> 的渠道发生了倍率变化，请确认是否为预期调整 —— 上调会直接推高成本。",
                    "渠道实时告警");
        } catch (Exception ex) {
            log.error("渠道倍率告警发送失败", ex);
        }
    }

    /**
     * 非在用渠道的变更 —— 每日汇总。
     *
     * <p>默认汇总「最近 24 小时」，由定时任务每天调用一次。</p>
     */
    public void sendDailyDigest() {
        try {
            OffsetDateTime to = OffsetDateTime.now(BusinessTime.ZONE);
            OffsetDateTime from = to.minusDays(1);
            List<UpstreamChangeEventEntity> events =
                    changeEventRepository.findGroupEventsBetween(from, to, false, MAX_EVENTS);
            if (events.isEmpty()) {
                log.debug("最近 24 小时没有非在用渠道的变更，不发送汇总");
                return;
            }
            String subject = "[Monitor] 非在用渠道变更汇总（" + events.size() + " 项）";
            send(MailScene.CHANNEL_DIGEST, events, subject,
                    "以下是最近 24 小时内 <b>非在用</b> 渠道的变更汇总，仅供了解，不需要立即处理。",
                    "渠道变更日报");
        } catch (Exception ex) {
            log.error("渠道变更日报发送失败", ex);
        }
    }

    /** 取收件人、读取 SMTP、组正文并发信。任何一环缺失都只记日志、不抛异常。 */
    private void send(MailScene scene, List<UpstreamChangeEventEntity> events,
                      String subject, String intro, String sceneLabel) {
        List<MailRecipientEntity> recipients = mailRecipientRepository.findByScene(scene);
        if (recipients.isEmpty()) {
            log.info("检测到{}但未配置收件人，暂不发送: scene={}, eventCount={}", sceneLabel, scene, events.size());
            return;
        }
        SmtpConfig smtpConfig = smtpConfigProvider.load();
        if (!smtpConfig.isConfigured()) {
            log.warn("检测到{}但 SMTP 未配置，暂不发送: eventCount={}", sceneLabel, events.size());
            return;
        }

        Map<Integer, String> accountNames = new HashMap<>();
        Map<Integer, String> platformNames = new HashMap<>();
        List<RateChangeImpactService.ImpactRow> impacts = collectImpacts(events);
        String summary = summarize(events, accountNames, impacts);
        String body = buildBody(events, intro, summary, accountNames, platformNames, impacts);
        List<String> to = recipients.stream().map(MailRecipientEntity::getEmail).toList();
        mailService.send(smtpConfig, to, subject, body);
        log.info("{}邮件已发送: recipients={}, eventCount={}, aiSummary={}",
                sceneLabel, to, events.size(), summary != null);
    }

    /**
     * 让大模型把原始变更读成一段人话。
     *
     * <p>模型没配、调用失败、返回为空 —— 任何一种情况都返回 {@code null}，
     * 调用方会退回原始明细表格。**告警的送达不能依赖模型可用。**</p>
     */
    /**
     * 逐条评估「上游涨价 → 我平台哪些分组倒挂」。
     *
     * <p>只读库没配 / 查询失败都只记日志：影响评估是锦上添花，
     * 不能让告警本身发不出去。返回的每行是「本平台账号 × 分组」。</p>
     */
    private List<RateChangeImpactService.ImpactRow> collectImpacts(List<UpstreamChangeEventEntity> events) {
        List<RateChangeImpactService.ImpactRow> rows = new ArrayList<>();
        for (UpstreamChangeEventEntity event : events) {
            try {
                rows.addAll(rateChangeImpactService.impactOf(event));
            } catch (RuntimeException ex) {
                log.warn("渠道影响评估失败: eventId={}, reason={}", event.getId(), ex.getMessage());
            }
        }
        return rows;
    }

    private String summarize(List<UpstreamChangeEventEntity> events, Map<Integer, String> accountNames,
                             List<RateChangeImpactService.ImpactRow> impacts) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            return null;
        }
        try {
            String answer = builder.build().prompt()
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .user("原始变更记录：\n" + rawLines(events, accountNames, impacts))
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) {
                return null;
            }
            return MailFormat.escape(answer.trim()).replace("\n", "<br>");
        } catch (Exception ex) {
            log.warn("渠道变更总结失败，将退回原始明细: {}", ex.getMessage());
            return null;
        }
    }

    /** 喂给模型的原始行（一行一条，含账号与旧值→新值）。 */
    private String rawLines(List<UpstreamChangeEventEntity> events, Map<Integer, String> accountNames,
                            List<RateChangeImpactService.ImpactRow> impacts) {
        StringBuilder sb = new StringBuilder();
        for (UpstreamChangeEventEntity event : events) {
            sb.append("- ").append(MailFormat.formatTime(event.getDetectedAt()))
                    .append(' ').append(accountNameResolver.accountName(event.getAccountId(), accountNames))
                    .append(" 渠道/密钥=").append(channelName(event))
                    .append(' ').append(event.getMessage() == null ? event.getChangeType() : event.getMessage());
            String before = MailFormat.scalar(event.getOldValue());
            String after = MailFormat.scalar(event.getNewValue());
            if (before != null || after != null) {
                sb.append("（").append(before == null ? "?" : before)
                        .append(" -> ").append(after == null ? "?" : after).append("）");
            }
            appendImpactLines(sb, event, impacts);
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildBody(List<UpstreamChangeEventEntity> events, String intro, String summary,
                             Map<Integer, String> accountNames, Map<Integer, String> platformNames,
                             List<RateChangeImpactService.ImpactRow> impacts) {
        StringBuilder html = new StringBuilder();
        html.append("<p>").append(intro).append("</p>");

        if (summary != null) {
            html.append("<div style=\"background:#f6f8fa;border-left:3px solid #409eff;")
                    .append("padding:10px 12px;margin:14px 0;font-size:13px\">")
                    .append("<strong>大模型总结</strong>")
                    .append("<p style=\"margin:8px 0 0;line-height:1.7\">").append(summary).append("</p>")
                    .append("</div>");
        } else {
            html.append("<p style=\"color:#999;font-size:12px\">")
                    .append("（未生成 AI 总结：未配置大模型或调用失败，下面直接列出原始变更）</p>");
        }

        html.append(impactBlock(impacts));
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">时间</th>")
                .append("<th align=\"left\">平台</th>")
                .append("<th align=\"left\">上游账号</th>")
                .append("<th align=\"left\">上游 Key</th>")
                .append("<th align=\"left\">本平台 Key</th>")
                .append("<th align=\"left\">分组（倍率）</th>")
                .append("<th align=\"left\">级别</th>")
                .append("<th align=\"left\">类型</th>")
                .append("<th align=\"left\">变化</th>")
                .append("</tr>");
        for (UpstreamChangeEventEntity event : events) {
            html.append("<tr>")
                    .append("<td>").append(MailFormat.formatTime(event.getDetectedAt())).append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.platformName(event.getAccountId(), platformNames))).append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.accountName(event.getAccountId(), accountNames))).append("</td>")
                    .append("<td>").append(MailFormat.escape(upstreamKeyLabel(event))).append("</td>")
                    .append("<td>").append(MailFormat.escape(localKeyLabel(event))).append("</td>")
                    .append("<td>").append(MailFormat.escape(groupWithRatio(event))).append("</td>")
                    .append("<td>").append(MailFormat.escape(severityLabel(event.getSeverity()))).append("</td>")
                    .append("<td>").append(MailFormat.escape(changeLabel(event.getChangeType()))).append("</td>")
                    .append("<td>").append(changeDescription(event)).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 渠道采集任务自动发送。</p>");
        return html.toString();
    }

    /**
     * 影响评估表格：一行一个（本平台账号 × 所属分组）。
     *
     * <p>这是「一眼看出影响范围」的核心：上游渠道涨价后，我平台哪些分组已经倒挂 ——
     * 上游新倍率高于该分组的售价倍率，倒挂行标红。</p>
     */
    private String impactBlock(List<RateChangeImpactService.ImpactRow> impacts) {
        if (impacts == null || impacts.isEmpty()) {
            return "";
        }
        boolean anyAffected = RateChangeImpactService.hasAffected(impacts);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"background:#fff7e6;border-left:3px solid #fa8c16;padding:10px 12px;margin:14px 0\">")
                .append("<strong>影响评估：上游新倍率 vs 本平台分组倍率</strong>")
                .append("<p style=\"margin:8px 0 0;font-size:13px\">")
                .append(anyAffected
                        ? "标红的分组已经<strong>倒挂</strong>：上游成本倍率高于该分组售价倍率，继续按这个分组卖会亏。"
                        : "本次涨价尚未超过任何分组倍率，暂无倒挂。")
                .append("</p>")
                .append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px;margin-top:8px\">")
                .append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">本平台账号</th>")
                .append("<th align=\"left\">所属分组</th>")
                .append("<th align=\"right\">分组倍率</th>")
                .append("<th align=\"left\">上游渠道</th>")
                .append("<th align=\"right\">上游倍率（原 → 新）</th>")
                .append("<th align=\"right\">涨价幅度</th>")
                .append("<th align=\"left\">是否受影响</th>")
                .append("<th align=\"left\">提醒级别</th>")
                .append("</tr>");
        for (RateChangeImpactService.ImpactRow row : impacts) {
            html.append(row.affected() ? "<tr style=\"background:#fff1f0\">" : "<tr>")
                    .append("<td>").append(MailFormat.escape(row.accountName())).append("</td>")
                    .append("<td>").append(MailFormat.escape(row.groupName())).append("</td>")
                    .append("<td align=\"right\">").append(decimal(row.groupRatio())).append("</td>")
                    .append("<td>").append(MailFormat.escape(row.upstreamChannel())).append("</td>")
                    .append("<td align=\"right\">").append(decimal(row.oldRatio())).append(" → ")
                    .append(decimal(row.newRatio())).append("</td>")
                    .append("<td align=\"right\">").append(percent(row.increasePercent())).append("</td>")
                    .append("<td>").append(row.affected() ? "<strong>是</strong>" : "否").append("</td>")
                    .append("<td>").append(levelLabel(row.level())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table></div>");
        return html.toString();
    }

    /** 把影响评估一并喂给大模型，避免总结里只说「涨了」不说「影响到谁」。 */
    private void appendImpactLines(StringBuilder sb, UpstreamChangeEventEntity event,
                                   List<RateChangeImpactService.ImpactRow> impacts) {
        if (impacts == null || impacts.isEmpty()) {
            return;
        }
        for (RateChangeImpactService.ImpactRow row : impacts) {
            if (event.getGroupName() != null && !event.getGroupName().equals(row.upstreamChannel())) {
                continue;
            }
            sb.append("；影响=").append(row.accountName())
                    .append('/').append(row.groupName())
                    .append("(分组倍率 ").append(decimal(row.groupRatio()))
                    .append(row.affected() ? "，已倒挂)" : "，未倒挂)");
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String levelLabel(String level) {
        return switch (level == null ? "INFO" : level) {
            case "CRITICAL" -> "严重";
            case "WARNING" -> "警告";
            default -> "信息";
        };
    }
    /** 受影响的「上游账号 Key」名；取不到退回单个 key 名 / 上游 ID。 */
    private String upstreamKeyLabel(UpstreamChangeEventEntity event) {
        if (event.getUpstreamKeyNames() != null && !event.getUpstreamKeyNames().isBlank()) {
            return event.getUpstreamKeyNames();
        }
        if (event.getKeyName() != null && !event.getKeyName().isBlank()) {
            return event.getKeyName();
        }
        return event.getEntityKey() == null ? "-" : event.getEntityKey();
    }

    /** 对应的「本平台 Key」（号池账号名）；没绑定显示「-」。 */
    private String localKeyLabel(UpstreamChangeEventEntity event) {
        return event.getLocalKeyNames() == null || event.getLocalKeyNames().isBlank()
                ? "-"
                : event.getLocalKeyNames();
    }

    /** 分组名 + 当前倍率。 */
    private String groupWithRatio(UpstreamChangeEventEntity event) {
        String group = channelName(event);
        if (event.getGroupRatio() == null) {
            return group;
        }
        return group + "（倍率 " + event.getGroupRatio().stripTrailingZeros().toPlainString() + "）";
    }

    /**
     * 变更对象的名字：渠道事件取渠道名，密钥事件取上游密钥名，取不到退回上游 ID（至少还能查）。
     */
    private String channelName(UpstreamChangeEventEntity event) {
        if ("API_KEY".equalsIgnoreCase(event.getEntityType())
                && event.getKeyName() != null && !event.getKeyName().isBlank()) {
            return event.getKeyName();
        }
        if (event.getGroupName() != null && !event.getGroupName().isBlank()) {
            return event.getGroupName();
        }
        return event.getEntityKey() == null ? "-" : event.getEntityKey();
    }

    /** 变化列：标量变化展示「旧值 → 新值」，整块 JSON 只给结论。 */
    private String changeDescription(UpstreamChangeEventEntity event) {
        String before = MailFormat.scalar(event.getOldValue());
        String after = MailFormat.scalar(event.getNewValue());
        if (before == null || after == null || before.startsWith("{") || before.startsWith("[")) {
            return MailFormat.escape(event.getMessage());
        }
        return MailFormat.escape(before) + " → " + MailFormat.escape(after);
    }

    private String severityLabel(String severity) {
        return "WARNING".equalsIgnoreCase(severity) ? "⚠ 警告" : "信息";
    }

    private String changeLabel(String changeType) {
        if (changeType == null) {
            return "未知";
        }
        return switch (changeType) {
            case "RATE_CHANGED" -> "倍率变化";
            case "BASE_RATE_CHANGED" -> "基础倍率变化";
            case "STATUS_CHANGED" -> "状态变化";
            case "GROUP_ADDED" -> "渠道新增";
            case "GROUP_REMOVED" -> "渠道移除";
            case "GROUP_UPDATED" -> "渠道更新";
            default -> changeType;
        };
    }
}
