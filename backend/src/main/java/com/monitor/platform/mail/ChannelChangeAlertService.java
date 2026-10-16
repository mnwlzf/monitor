package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.RateChangeImpactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><strong>正文逐条展开</strong>：一条渠道倍率事件按实际影响拆成多行，
 * 一行只表达一个「本平台账号 × 本平台分组」的对应关系，不再把多个 Key
 * 用「、」拼在同一个单元格里，也不再生成聚合式大模型总结。</p>
 *
 * <p>表格把上下游分开写：上游一侧是「上游平台 / 上游账号 / 上游渠道（分组）/ 上游 Key /
 * 上游倍率（原 → 新）」，本平台一侧是「本平台账号 / 本平台分组（倍率）」。以前的
 * 「所属分组」一列在匹配到影响时是本平台分组、匹配不到时又变成上游渠道名，
 * 「分组倍率」也是两套口径，看的人分不清哪边是哪边。</p>
 *
 * <p>本类所有方法都不抛异常：发信失败只记日志，不拖累采集任务。</p>
 */
@Service
public class ChannelChangeAlertService {

    private static final Logger log = LoggerFactory.getLogger(ChannelChangeAlertService.class);

    /** 单封邮件最多包含的变更条数，防止异常数据把邮件撑爆。 */
    private static final int MAX_EVENTS = 200;

    private final UpstreamChangeEventRepository changeEventRepository;
    private final AccountNameResolver accountNameResolver;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;
    private final RateChangeImpactService rateChangeImpactService;

    public ChannelChangeAlertService(UpstreamChangeEventRepository changeEventRepository,
                                     AccountNameResolver accountNameResolver,
                                     MailRecipientRepository mailRecipientRepository,
                                     SmtpConfigProvider smtpConfigProvider,
                                     MailService mailService,
                                     RateChangeImpactService rateChangeImpactService) {
        this.changeEventRepository = changeEventRepository;
        this.accountNameResolver = accountNameResolver;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
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
            int rising = countRising(events);
            String subject = "[Monitor] 正在使用的渠道倍率变化（" + events.size() + " 条事件"
                    + (rising > 0 ? "，" + rising + " 条上调" : "，全部为下调") + "）";
            send(MailScene.CHANNEL_ALERT, events, subject, alertIntro(rising), "渠道实时告警");
        } catch (Exception ex) {
            log.error("渠道倍率告警发送失败", ex);
        }
    }

    /**
     * 实时告警的开场白。
     *
     * <p>以前这里写死「上调会直接推高成本」，降价告警也照样带着这句话，
     * 收件人就会以为系统把 0.3 → 0.23 判成了涨价。现在按本批的实际方向说话：
     * 有上调才提成本上升，全是下调就明说成本不会上升。</p>
     *
     * @param rising 本批事件里「新倍率大于旧倍率」的条数
     */
    private static String alertIntro(int rising) {
        if (rising == 0) {
            return "以下 <b>正在使用</b> 的渠道发生了倍率变化，请确认是否为预期调整 —— "
                    + "本次 <b>全部为下调</b>，成本不会因此上升。";
        }
        return "以下 <b>正在使用</b> 的渠道发生了倍率变化，请确认是否为预期调整 —— "
                + "其中 <b>" + rising + "</b> 条为 <b>上调</b>（级别标为 ⚠ 警告），会直接推高成本。";
    }

    /** 本批事件里有几条是上调（新倍率大于旧倍率）。 */
    private static int countRising(List<UpstreamChangeEventEntity> events) {
        int rising = 0;
        for (UpstreamChangeEventEntity event : events) {
            BigDecimal before = decimal(event.getOldValue());
            BigDecimal after = decimal(event.getNewValue());
            if (before != null && after != null && after.compareTo(before) > 0) {
                rising++;
            }
        }
        return rising;
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
            String subject = "[Monitor] 非在用渠道变更汇总（" + events.size() + " 条事件）";
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

        Map<Integer, String> platformNames = new HashMap<>();
        List<DisplayRow> rows = displayRows(events);
        String body = buildBody(intro, rows, platformNames);
        List<String> to = recipients.stream().map(MailRecipientEntity::getEmail).toList();
        mailService.send(smtpConfig, to, subject, body);
        log.info("{}邮件已发送: recipients={}, eventCount={}, rowCount={}",
                sceneLabel, to, events.size(), rows.size());
    }

    /**
     * 逐条展开：一条渠道倍率事件有影响评估时，按影响行拆成多行；
     * 没有影响评估的非倍率事件保留一条事件行。
     */
    private List<DisplayRow> displayRows(List<UpstreamChangeEventEntity> events) {
        List<DisplayRow> rows = new ArrayList<>();
        for (UpstreamChangeEventEntity event : events) {
            List<RateChangeImpactService.ImpactRow> impacts = impactsOf(event);
            if (impacts.isEmpty()) {
                rows.add(new DisplayRow(event, null));
                continue;
            }
            for (RateChangeImpactService.ImpactRow impact : impacts) {
                rows.add(new DisplayRow(event, impact));
            }
        }
        return rows;
    }

    /**
     * 影响评估是逐条对应关系：一条上游倍率变化可能命中多个「本平台账号 × 分组」，
     * 每个命中都独立成一行，不能再用逗号或顿号合并。
     */
    private List<RateChangeImpactService.ImpactRow> impactsOf(UpstreamChangeEventEntity event) {
        try {
            return rateChangeImpactService.impactOf(event);
        } catch (RuntimeException ex) {
            log.warn("渠道影响评估失败: eventId={}, reason={}", event.getId(), ex.getMessage());
            return List.of();
        }
    }

    private String buildBody(String intro, List<DisplayRow> rows,
                             Map<Integer, String> platformNames) {
        Map<Integer, String> accountNames = new HashMap<>();
        StringBuilder html = new StringBuilder();
        html.append("<p>").append(intro).append("</p>");

        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">时间</th>")
                .append("<th align=\"left\">级别</th>")
                .append("<th align=\"left\">上游平台</th>")
                .append("<th align=\"left\">上游账号</th>")
                .append("<th align=\"left\">上游渠道（分组）</th>")
                .append("<th align=\"left\">上游 Key</th>")
                .append("<th align=\"left\">上游倍率（原 → 新）</th>")
                .append("<th align=\"left\">本平台账号</th>")
                .append("<th align=\"left\">本平台分组（倍率）</th>")
                .append("<th align=\"left\">是否受影响</th>")
                .append("<th align=\"left\">内容</th>")
                .append("</tr>");

        for (DisplayRow display : rows) {
            UpstreamChangeEventEntity event = display.event();

            html.append("<tr>")
                    .append("<td>").append(MailFormat.formatTime(event.getDetectedAt())).append("</td>")
                    .append("<td>").append(MailFormat.escape(severityLabel(event.getSeverity()))).append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.platformName(event.getAccountId(), platformNames))).append("</td>")
                    .append("<td>").append(MailFormat.escape(
                            accountNameResolver.accountName(event.getAccountId(), accountNames))).append("</td>")
                    .append("<td>").append(MailFormat.escape(upstreamChannel(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(upstreamKey(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(upstreamRatioChange(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(localAccount(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(localGroup(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(affectedText(display))).append("</td>")
                    .append("<td>").append(MailFormat.escape(contentText(event))).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 渠道采集任务自动发送。</p>");
        return html.toString();
    }

    /**
     * 上游渠道（分组）名：发生变化的那个渠道。
     *
     * <p>影响行里带的是评估时的渠道名，取不到就退回事件自身的渠道名 ——
     * 这一列永远是「上游改价的那个渠道」，不会再和本平台分组混在一起。</p>
     */
    private static String upstreamChannel(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        String name = impact != null && impact.upstreamChannel() != null && !impact.upstreamChannel().isBlank()
                ? impact.upstreamChannel()
                : channelName(display.event());
        return withEntityId(name, display.event());
    }

    /**
     * 渠道名后面带上游 ID。
     *
     * <p>以前 ID 只出现在「内容」列的「倍率变化: 36」里 —— 光给一个数字，
     * 收件人既不知道它是渠道 ID，也容易误读成倍率值。挪到渠道名旁边，含义一目了然。</p>
     */
    private static String withEntityId(String name, UpstreamChangeEventEntity event) {
        String id = event.getEntityKey();
        if (name == null || name.isBlank() || id == null || id.isBlank() || name.contains(id)) {
            return name;
        }
        return name + "（ID " + id + "）";
    }

    /** 上游 Key：有影响行时就是绑定的那把 Key，否则退回事件自己的 Key（多为「—」）。 */
    private static String upstreamKey(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        if (impact != null && impact.upstreamKeyName() != null && !impact.upstreamKeyName().isBlank()) {
            return impact.upstreamKeyName();
        }
        String single = singleUpstreamKeyLabel(display.event());
        if (single != null && !single.isBlank()) {
            return single;
        }
        return rateChange(display.event()) ? "未匹配到上游 Key" : "—";
    }

    /** 是否倍率事件 —— 「对不上本平台账号」这类话只在倍率事件里说。 */
    private static boolean rateChange(UpstreamChangeEventEntity event) {
        String changeType = event.getChangeType();
        return "RATE_CHANGED".equals(changeType) || "BASE_RATE_CHANGED".equals(changeType);
    }

    /**
     * 本平台账号（号池账号名）。
     *
     * <p>只有「上游 Key 分组 == 变更渠道」时才拿得到账号名；对不上就如实写「未匹配到」，
     * 不退回账号级的聚合 Key 列表 —— 那会把别的渠道的账号也算进来，告警里看着像
     * 自己一堆账号都被波及了。分组名只是被上游改过（加后缀）的情况由
     * {@code RateChangeImpactService#scopeKeys} 兜住，正常能对上。</p>
     */
    private static String localAccount(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        if (impact != null && impact.accountName() != null && !impact.accountName().isBlank()) {
            return impact.accountName();
        }
        return rateChange(display.event()) ? "未匹配到本平台账号" : "—";
    }

    /** 本平台分组：分组名 + 分组所属平台 + 分组倍率。 */
    private static String localGroup(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        if (impact == null) {
            return "—";
        }
        String name = impact.groupName() == null || impact.groupName().isBlank() ? "未知分组" : impact.groupName();
        String ratio = decimal(impact.groupRatio());
        String platform = impact.groupPlatform();
        return platform == null || platform.isBlank()
                ? name + "（倍率 " + ratio + "）"
                : name + "（" + platform + " · 倍率 " + ratio + "）";
    }

    /** 上游新倍率是否已超过本平台分组倍率（倒挂）。 */
    private static String affectedText(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        if (impact == null) {
            return "—";
        }
        return impact.affected() ? "是（倒挂）" : "否";
    }

    /** 一条事件的普通说明；倍率变化的具体旧值/新值放在“上游倍率”列里逐行展示。 */
    private static String contentText(UpstreamChangeEventEntity event) {
        // 倍率事件的 message 只有「倍率变化: 36」这种渠道 ID，渠道名和 ID 都在
        // 「上游渠道（分组）」列里了，这里只说清是哪种变化。
        if (rateChange(event)) {
            return changeLabel(event.getChangeType());
        }
        String message = event.getMessage();
        if (message != null && !message.isBlank()) {
            return message.replaceAll("\\s+", " ").trim();
        }
        return changeLabel(event.getChangeType());
    }

    /** 无法拿到影响评估时，只展示事件自己的单个上游 Key，绝不展示聚合列表。 */
    private static String singleUpstreamKeyLabel(UpstreamChangeEventEntity event) {
        return event.getKeyName();
    }

    /** 上游渠道倍率的变化；优先使用影响评估，缺失时回退到事件自身的原/新值。 */
    private static String upstreamRatioChange(DisplayRow display) {
        RateChangeImpactService.ImpactRow impact = display.impact();
        if (impact != null && (impact.oldRatio() != null || impact.newRatio() != null)) {
            return ratioChange(impact.oldRatio(), impact.newRatio());
        }

        String changeType = display.event().getChangeType();
        if (!"RATE_CHANGED".equals(changeType) && !"BASE_RATE_CHANGED".equals(changeType)) {
            return "—";
        }
        BigDecimal oldRatio = decimal(display.event().getOldValue());
        BigDecimal newRatio = decimal(display.event().getNewValue());
        return oldRatio == null && newRatio == null ? "—" : ratioChange(oldRatio, newRatio);
    }

    private static String ratioChange(BigDecimal oldRatio, BigDecimal newRatio) {
        return decimal(oldRatio) + " → " + decimal(newRatio);
    }

    /** 解析事件里 JSON 形式的倍率，例如 0.3 或 "0.3"。 */
    private static BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // 34 是双引号；事件值可能是 JSON 字符串 "0.3"。
        if (text.length() >= 2 && text.charAt(0) == 34 && text.charAt(text.length() - 1) == 34) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isEmpty() || text.startsWith("{") || text.startsWith("[")) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private static String severityLabel(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return "严重";
        }
        return "WARNING".equalsIgnoreCase(severity) ? "⚠ 警告" : "信息";
    }

    /**
     * 变更对象的名字：渠道事件取渠道名，密钥事件取上游密钥名，取不到退回上游 ID。
     */
    private static String channelName(UpstreamChangeEventEntity event) {
        if ("API_KEY".equalsIgnoreCase(event.getEntityType())
                && event.getKeyName() != null && !event.getKeyName().isBlank()) {
            return event.getKeyName();
        }
        if (event.getGroupName() != null && !event.getGroupName().isBlank()) {
            return event.getGroupName();
        }
        return event.getEntityKey();
    }

    private static String changeLabel(String changeType) {
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

    /** 一条邮件展示行：倍率事件为「事件 × 影响」，普通事件为「事件 × 空影响」。 */
    private record DisplayRow(UpstreamChangeEventEntity event,
                              RateChangeImpactService.ImpactRow impact) {
    }
}
