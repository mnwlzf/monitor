package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.channel.Sub2ChannelStatusReader;
import com.monitor.platform.bot.channel.Sub2ChannelStatusReader.ChannelStatusRow;
import com.monitor.platform.bot.error.Sub2ErrorReader;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.PoolAccountEntity;
import com.monitor.platform.pool.RateChangeImpactService;
import com.monitor.platform.pool.RateChangeImpactService.ImpactRow;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** A+B 监控聚合：多源信号、Provider/分组归因和机器人/API 报表。只读，不修改 Sub2API。 */
@Service
public class OpsMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(OpsMonitoringService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Duration CHANNEL_WINDOW = Duration.ofHours(1);
    private static final int CHANNEL_MIN_REQUESTS = 20;
    private static final double CHANNEL_ABNORMAL_RATE = 0.20;
    private static final int USER_ANOMALY_MIN = 20;
    private static final int ACCOUNT_LIMIT = 100;

    private final Sub2ChannelStatusReader channelReader;
    private final Sub2ErrorReader errorReader;
    private final Sub2ProxyReader proxyReader;
    private final PlatformRepository platformRepository;
    private final PoolAccountRepository poolAccountRepository;
    private final UpstreamChangeEventRepository changeEventRepository;
    private final RateChangeImpactService rateImpactService;
    private final ObjectMapper objectMapper;

    public OpsMonitoringService(Sub2ChannelStatusReader channelReader,
                                Sub2ErrorReader errorReader,
                                Sub2ProxyReader proxyReader,
                                PlatformRepository platformRepository,
                                PoolAccountRepository poolAccountRepository,
                                UpstreamChangeEventRepository changeEventRepository,
                                RateChangeImpactService rateImpactService,
                                ObjectMapper objectMapper) {
        this.channelReader = channelReader;
        this.errorReader = errorReader;
        this.proxyReader = proxyReader;
        this.platformRepository = platformRepository;
        this.poolAccountRepository = poolAccountRepository;
        this.changeEventRepository = changeEventRepository;
        this.rateImpactService = rateImpactService;
        this.objectMapper = objectMapper;
    }

    public Snapshot snapshot() {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        return new Snapshot(now, channels(now), accounts(now), proxies(now), rateRisks(now), userAnomalies(now));
    }

    /** 是否至少有一个可读的 Sub2API 监控源，用于避免数据源全挂时误关事故。 */
    public boolean hasReadableSource() {
        return errorReader.isAvailable() || channelReader.isAvailable() || proxyReader.isAvailable();
    }

    public List<MonitoringSignal> collectIncidentSignals() {
        Snapshot snapshot = snapshot();
        List<MonitoringSignal> signals = new ArrayList<>();
        for (ChannelSignal channel : snapshot.channels()) {
            if (!"INFO".equals(channel.severity())) {
                signals.add(signal("CHANNEL_HEALTH", "channel:" + channel.platform() + ":" + channel.groupId(),
                        "渠道健康异常：" + channel.groupName(), channel.severity(), "CHANNEL",
                        channel.platform() + ":" + channel.groupId(),
                        "请求 " + channel.requests() + "，错误率 " + percent(channel.errorRate()),
                        Map.of("platform", nz(channel.platform()), "groupId", channel.groupId(),
                                "groupName", nz(channel.groupName()), "requests", channel.requests(),
                                "errors", channel.errors(), "errorRate", channel.errorRate(),
                                "severity", channel.severity())));
            }
        }
        for (AccountSignal account : snapshot.accounts()) {
            if (!"INFO".equals(account.severity())) {
                signals.add(signal("ACCOUNT_HEALTH", "account:" + account.platform() + ":" + account.accountId(),
                        "账号健康异常：" + nz(account.name()), account.severity(), "ACCOUNT",
                        String.valueOf(account.accountId()), account.issue(),
                        Map.of("accountId", account.accountId(), "name", nz(account.name()),
                                "platform", nz(account.platform()), "status", nz(account.status()),
                                "issue", nz(account.issue()), "severity", account.severity())));
            }
        }
        for (ProxySignal proxy : snapshot.proxies()) {
            if (!"INFO".equals(proxy.severity())) {
                signals.add(signal("PROXY_HEALTH", "proxy:" + proxy.id(),
                        "代理健康异常：" + nz(proxy.name()), proxy.severity(), "PROXY",
                        String.valueOf(proxy.id()), proxy.issue(),
                        Map.of("proxyId", proxy.id(), "name", nz(proxy.name()), "status", nz(proxy.status()),
                                "expiresAt", proxy.expiresAt() == null ? "" : proxy.expiresAt().toString(),
                                "daysLeft", proxy.daysLeft(), "issue", nz(proxy.issue()),
                                "severity", proxy.severity())));
            }
        }
        for (RateRiskSignal risk : snapshot.rateRisks()) {
            if (!"INFO".equals(risk.severity())) {
                signals.add(signal("RATE_RISK", "rate:" + risk.eventId() + ":" + risk.groupName()
                                + ":" + risk.accountName(),
                        "倍率风险：" + nz(risk.groupName()), risk.severity(), "GROUP", nz(risk.groupName()),
                        "上游 " + nz(risk.upstreamChannel()) + " 倍率 " + nz(risk.oldRatio()) + " → "
                                + nz(risk.newRatio()) + "，分组倍率 " + nz(risk.groupRatio()),
                        Map.of("eventId", risk.eventId(), "platform", nz(risk.platform()),
                                "groupName", nz(risk.groupName()), "accountName", nz(risk.accountName()),
                                "upstreamChannel", nz(risk.upstreamChannel()), "oldRatio", nz(risk.oldRatio()),
                                "newRatio", nz(risk.newRatio()), "groupRatio", nz(risk.groupRatio()),
                                "severity", risk.severity())));
            }
        }
        for (UserAnomalySignal user : snapshot.userAnomalies()) {
            if (!"INFO".equals(user.severity())) {
                signals.add(signal("USER_ANOMALY", "user:" + user.userId(),
                        "用户异常： " + nz(user.email()), user.severity(), "USER", String.valueOf(user.userId()),
                        "近 1 小时错误 " + user.count() + " 次，业务限制 " + user.businessLimited(),
                        Map.of("userId", user.userId(), "email", nz(user.email()), "count", user.count(),
                                "businessLimited", user.businessLimited(), "severity", user.severity())));
            }
        }
        return signals;
    }

    public BotReport overviewReport() {
        Snapshot s = snapshot();
        List<ProviderSignal> providers = providerSignals(s.channels());
        long abnormalChannels = s.channels().stream().filter(c -> !"INFO".equals(c.severity())).count();
        long proxyIssues = s.proxies().stream().filter(p -> !"INFO".equals(p.severity())).count();
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("生成时间", time(s.generatedAt())));
        rows.add(BotReport.Row.of("监控渠道", String.valueOf(s.channels().size())));
        rows.add(BotReport.Row.of("异常渠道", String.valueOf(abnormalChannels)));
        rows.add(BotReport.Row.of("账号异常", String.valueOf(s.accounts().size())));
        rows.add(BotReport.Row.of("代理异常", String.valueOf(proxyIssues)));
        rows.add(BotReport.Row.of("倍率风险", String.valueOf(s.rateRisks().size())));
        rows.add(BotReport.Row.of("用户异常", String.valueOf(s.userAnomalies().size())));
        rows.add(BotReport.Row.of("Provider 风险", String.valueOf(providers.stream()
                .filter(p -> !"正常".equals(p.verdict())).count())));
        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"), BotReport.Col.right("值")), rows, null));
        List<BotReport.Row> providerRows = new ArrayList<>();
        for (ProviderSignal provider : providers) {
            providerRows.add(BotReport.Row.of(nz(provider.platform()), String.valueOf(provider.groups()),
                    String.valueOf(provider.requests()), percent(provider.errorRate()), provider.verdict()));
        }
        if (!providerRows.isEmpty()) {
            blocks.add(new BotReport.Block("Provider / 分组归因", List.of(
                    BotReport.Col.left("平台"), BotReport.Col.right("分组数"),
                    BotReport.Col.right("请求数"), BotReport.Col.right("错误率"),
                    BotReport.Col.left("判断")), providerRows, null));
        }
        return BotReport.of("智能运维监控总览", blocks,
                List.of("查看渠道：/监控 渠道", "查看账号：/监控 账号", "查看事故：/事故"));
    }

    public BotReport channelReport() {
        List<ChannelSignal> rows = channels(OffsetDateTime.now(ZONE));
        List<BotReport.Row> data = new ArrayList<>();
        for (ChannelSignal row : rows.stream().limit(30).toList()) {
            data.add(BotReport.Row.of(nz(row.platform()), nz(row.groupName()),
                    String.valueOf(row.requests()), String.valueOf(row.errors()),
                    percent(row.errorRate()), millis(row.avgTtftMs()), percent(row.cacheRate()),
                    severityLabel(row.severity())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("渠道健康", List.of("近 1 小时没有渠道监控数据。")));
        } else {
            blocks.add(new BotReport.Block("近 1 小时", List.of(
                    BotReport.Col.left("平台"), BotReport.Col.left("分组"),
                    BotReport.Col.right("请求"), BotReport.Col.right("错误"),
                    BotReport.Col.right("错误率"), BotReport.Col.right("首 Token"),
                    BotReport.Col.right("缓存率"), BotReport.Col.left("状态")), data, null));
        }
        return BotReport.of("渠道健康监控", blocks, List.of("错误率 ≥ 20% 且请求数 ≥ 20 时进入异常监控。"));
    }

    public BotReport accountReport() {
        List<AccountSignal> rows = accounts(OffsetDateTime.now(ZONE));
        List<BotReport.Row> data = new ArrayList<>();
        for (AccountSignal row : rows.stream().limit(50).toList()) {
            data.add(BotReport.Row.of(nz(row.name()), nz(row.platform()), nz(row.status()),
                    Boolean.TRUE.equals(row.schedulable()) ? "可调度" : "不可调度",
                    nz(row.issue()), severityLabel(row.severity())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("账号健康", List.of("当前没有发现账号健康异常。")));
        } else {
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("账号"), BotReport.Col.left("平台"),
                    BotReport.Col.left("状态"), BotReport.Col.left("调度"),
                    BotReport.Col.left("问题"), BotReport.Col.left("级别")), data, null));
        }
        return BotReport.of("账号健康监控", blocks, List.of("数据来自 Monitor 本地号池健康快照。"));
    }

    public BotReport proxyReport() {
        List<ProxySignal> rows = proxies(OffsetDateTime.now(ZONE));
        List<BotReport.Row> data = new ArrayList<>();
        for (ProxySignal row : rows.stream().limit(50).toList()) {
            data.add(BotReport.Row.of(nz(row.name()), nz(row.protocol()), nz(row.status()),
                    row.expiresAt() == null ? "永久" : time(row.expiresAt()),
                    row.daysLeft() < 0 ? "—" : row.daysLeft() + " 天", nz(row.issue()),
                    severityLabel(row.severity())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("代理健康", List.of("没有发现代理过期或即将过期。")));
        } else {
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("代理"), BotReport.Col.left("协议"),
                    BotReport.Col.left("状态"), BotReport.Col.left("到期"),
                    BotReport.Col.left("剩余"), BotReport.Col.left("问题"),
                    BotReport.Col.left("级别")), data, null));
        }
        return BotReport.of("代理健康监控", blocks, List.of("代理数据来自 Sub2API 只读库 proxies 表。"));
    }

    public BotReport rateRiskReport() {
        List<RateRiskSignal> rows = rateRisks(OffsetDateTime.now(ZONE));
        List<BotReport.Row> data = new ArrayList<>();
        for (RateRiskSignal row : rows.stream().limit(30).toList()) {
            data.add(BotReport.Row.of(nz(row.platform()), nz(row.groupName()), nz(row.accountName()),
                    nz(row.upstreamChannel()), nz(row.oldRatio()) + " → " + nz(row.newRatio()),
                    nz(row.groupRatio()), severityLabel(row.severity())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("倍率风险", List.of("近 24 小时没有发现倍率倒挂风险。")));
        } else {
            blocks.add(new BotReport.Block("近 24 小时", List.of(
                    BotReport.Col.left("平台"), BotReport.Col.left("分组"),
                    BotReport.Col.left("账号"), BotReport.Col.left("上游渠道"),
                    BotReport.Col.left("上游倍率"), BotReport.Col.left("分组倍率"),
                    BotReport.Col.left("级别")), data, null));
        }
        return BotReport.of("倍率风险监控", blocks, List.of("上游新倍率 > 分组倍率视为倒挂。"));
    }

    public BotReport userAnomalyReport() {
        List<UserAnomalySignal> rows = userAnomalies(OffsetDateTime.now(ZONE));
        List<BotReport.Row> data = new ArrayList<>();
        for (UserAnomalySignal row : rows.stream().limit(30).toList()) {
            data.add(BotReport.Row.of(String.valueOf(row.userId()), nz(row.email()),
                    String.valueOf(row.count()), String.valueOf(row.businessLimited()),
                    severityLabel(row.severity())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("用户异常", List.of("近 1 小时没有发现高错误用户。")));
        } else {
            blocks.add(new BotReport.Block("近 1 小时", List.of(
                    BotReport.Col.left("用户 ID"), BotReport.Col.left("邮箱"),
                    BotReport.Col.right("错误"), BotReport.Col.right("业务限制"),
                    BotReport.Col.left("级别")), data, null));
        }
        return BotReport.of("用户异常监控", blocks, List.of("当前阈值：近 1 小时错误 ≥ 20 次。"));
    }

    public BotReport providerReport() {
        List<ProviderSignal> rows = providerSignals(channels(OffsetDateTime.now(ZONE)));
        List<BotReport.Row> data = new ArrayList<>();
        for (ProviderSignal row : rows) {
            data.add(BotReport.Row.of(nz(row.platform()), String.valueOf(row.groups()),
                    String.valueOf(row.requests()), percent(row.errorRate()),
                    String.valueOf(row.abnormalGroups()), row.verdict()));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (data.isEmpty()) {
            blocks.add(BotReport.paragraph("Provider 归因", List.of("暂无渠道监控数据。")));
        } else {
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("平台"), BotReport.Col.right("分组数"),
                    BotReport.Col.right("请求数"), BotReport.Col.right("错误率"),
                    BotReport.Col.right("异常分组"), BotReport.Col.left("判断")), data, null));
        }
        return BotReport.of("Provider / 分组归因", blocks,
                List.of("多个分组同时异常时优先判断为 Provider 级或模型级问题。"));
    }

    public List<ChannelSignal> channels(OffsetDateTime now) {
        if (!channelReader.isAvailable()) {
            return List.of();
        }
        try {
            return channelReader.byPlatformGroup(now.minus(CHANNEL_WINDOW), now).stream()
                    .map(this::channelSignal)
                    .sorted(Comparator.comparing(ChannelSignal::errorRate).reversed())
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("读取渠道监控信号失败: {}", ex.getMessage());
            return List.of();
        }
    }

    public List<AccountSignal> accounts(OffsetDateTime now) {
        List<AccountSignal> result = new ArrayList<>();
        try {
            for (PlatformEntity platform : platformRepository.findAll()) {
                if (!Boolean.TRUE.equals(platform.getStatus())) {
                    continue;
                }
                for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platform.getId())) {
                    String issue = accountIssue(account, now);
                    if (issue != null) {
                        result.add(accountSignal(account, issue));
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("读取账号监控信号失败: {}", ex.getMessage());
        }
        return result.stream().limit(ACCOUNT_LIMIT).toList();
    }

    public List<ProxySignal> proxies(OffsetDateTime now) {
        if (!proxyReader.isAvailable()) {
            return List.of();
        }
        try {
            return proxyReader.list().stream().map(proxy -> proxySignal(proxy, now)).toList();
        } catch (RuntimeException ex) {
            log.warn("读取代理监控信号失败: {}", ex.getMessage());
            return List.of();
        }
    }

    public List<RateRiskSignal> rateRisks(OffsetDateTime now) {
        List<RateRiskSignal> result = new ArrayList<>();
        try {
            for (UpstreamChangeEventEntity event : changeEventRepository.findInUseGroupRateEventsSince(
                    now.minus(Duration.ofHours(24)), 100)) {
                for (ImpactRow impact : rateImpactService.impactOf(event)) {
                    if (!impact.affected()) {
                        continue;
                    }
                    result.add(new RateRiskSignal(event.getId(), impact.groupPlatform(), impact.groupName(),
                            impact.accountName(), impact.upstreamChannel(), impact.oldRatio(),
                            impact.newRatio(), impact.groupRatio(),
                            "CRITICAL".equals(impact.level()) ? "P1" : "P2"));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("读取倍率风险信号失败: {}", ex.getMessage());
        }
        return result;
    }

    public List<UserAnomalySignal> userAnomalies(OffsetDateTime now) {
        if (!errorReader.isAvailable()) {
            return List.of();
        }
        try {
            return errorReader.topUsers(now.minus(Duration.ofHours(1)), now,
                            ErrorFilter.everything(null), 30).stream()
                    .filter(user -> user.count() >= USER_ANOMALY_MIN)
                    .map(user -> new UserAnomalySignal(user.userId(), user.userEmail(), user.count(),
                            user.businessLimited(), user.count() >= 100 ? "P1" : "P2"))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("读取用户异常信号失败: {}", ex.getMessage());
            return List.of();
        }
    }

    private ChannelSignal channelSignal(ChannelStatusRow row) {
        long requests = row.requestCount();
        double errorRate = requests == 0 ? 0 : (double) row.errorRequests() / requests;
        String severity = requests < CHANNEL_MIN_REQUESTS || errorRate < CHANNEL_ABNORMAL_RATE
                ? "INFO" : errorRate >= 0.5 ? "P1" : "P2";
        return new ChannelSignal(row.platform(), row.groupId(), groupLabel(row), requests,
                row.errorRequests(), errorRate, row.averageTtftMs(), row.cacheRate(), severity);
    }

    private AccountSignal accountSignal(PoolAccountEntity account, String issue) {
        return new AccountSignal(account.getExternalAccountId(), account.getName(), account.getPlatform(),
                account.getStatus(), account.getSchedulable(), issue, accountSeverity(account, issue));
    }

    private String accountIssue(PoolAccountEntity account, OffsetDateTime now) {
        if (account == null) {
            return null;
        }
        if (account.getStatus() != null && !"active".equalsIgnoreCase(account.getStatus())) {
            return "账号状态异常：" + account.getStatus();
        }
        if (Boolean.FALSE.equals(account.getSchedulable())) {
            return "账号不可调度";
        }
        if (account.getRateLimitResetAt() != null && account.getRateLimitResetAt().isAfter(now)) {
            return "限流中，恢复时间 " + time(account.getRateLimitResetAt());
        }
        if (account.getOverloadUntil() != null && account.getOverloadUntil().isAfter(now)) {
            return "过载中，恢复时间 " + time(account.getOverloadUntil());
        }
        if (account.getTempUnschedulableUntil() != null && account.getTempUnschedulableUntil().isAfter(now)) {
            return "临时不可调度：" + nz(account.getTempUnschedulableReason());
        }
        if (account.getLastSyncError() != null && !account.getLastSyncError().isBlank()) {
            return "采集失败：" + account.getLastSyncError();
        }
        return null;
    }

    private String accountSeverity(PoolAccountEntity account, String issue) {
        if (account != null && account.getStatus() != null && !"active".equalsIgnoreCase(account.getStatus())) {
            return "P1";
        }
        return "P2";
    }

    private ProxySignal proxySignal(Sub2ProxyReader.ProxyRow proxy, OffsetDateTime now) {
        long daysLeft = proxy.expiresAt() == null ? -1
                : Math.max(-1, Duration.between(now, proxy.expiresAt()).toDays());
        String issue = null;
        String severity = "INFO";
        if (proxy.expiresAt() != null && !proxy.expiresAt().isAfter(now)) {
            issue = "代理已过期";
            severity = "P1";
        } else if (proxy.expiresAt() != null
                && !proxy.expiresAt().isAfter(now.plusDays(Math.max(1, proxy.expiryWarnDays())))) {
            issue = "代理即将过期";
            severity = "P2";
        } else if (proxy.status() != null && !"active".equalsIgnoreCase(proxy.status())) {
            issue = "代理状态异常：" + proxy.status();
            severity = "P2";
        }
        return new ProxySignal(proxy.id(), proxy.name(), proxy.protocol(), proxy.status(),
                proxy.expiresAt(), daysLeft, issue, severity);
    }

    private List<ProviderSignal> providerSignals(List<ChannelSignal> channels) {
        Map<String, List<ChannelSignal>> byPlatform = channels.stream()
                .filter(c -> c.platform() != null && !c.platform().isBlank())
                .collect(Collectors.groupingBy(ChannelSignal::platform, LinkedHashMap::new, Collectors.toList()));
        List<ProviderSignal> result = new ArrayList<>();
        for (Map.Entry<String, List<ChannelSignal>> entry : byPlatform.entrySet()) {
            List<ChannelSignal> rows = entry.getValue();
            long requests = rows.stream().mapToLong(ChannelSignal::requests).sum();
            long errors = rows.stream().mapToLong(ChannelSignal::errors).sum();
            long abnormal = rows.stream().filter(c -> !"INFO".equals(c.severity())).count();
            double rate = requests == 0 ? 0 : (double) errors / requests;
            String verdict = abnormal == 0 ? "正常"
                    : abnormal >= 2 && abnormal == rows.size() ? "疑似 Provider 级异常"
                    : abnormal >= 2 ? "多分组异常，疑似 Provider/模型级"
                    : "单分组异常";
            result.add(new ProviderSignal(entry.getKey(), rows.size(), requests, rate, abnormal, verdict));
        }
        result.sort(Comparator.comparing(ProviderSignal::abnormalGroups).reversed()
                .thenComparing(Comparator.comparingDouble(ProviderSignal::errorRate).reversed()));
        return result;
    }

    private static String groupLabel(ChannelStatusRow row) {
        return row.groupName() == null || row.groupName().isBlank()
                ? "分组 " + row.groupId() : row.groupName();
    }

    private static String severityLabel(String severity) {
        return switch (severity == null ? "INFO" : severity) {
            case "P1" -> "异常";
            case "P2" -> "注意";
            default -> "正常";
        };
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "—" : value.atZoneSameInstant(ZONE).format(TIME);
    }

    private static String percent(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String millis(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1fs", value / 1000.0);
    }

    private static String nz(Object value) {
        if (value == null) {
            return "—";
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "—" : text;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private MonitoringSignal signal(String type, String fingerprint, String title, String severity,
                                    String scopeType, String scopeId, String impactSummary,
                                    Map<String, Object> evidence) {
        return new MonitoringSignal(type, fingerprint, title, severity, scopeType, scopeId,
                impactSummary, json(evidence));
    }

    public record Snapshot(OffsetDateTime generatedAt, List<ChannelSignal> channels,
                           List<AccountSignal> accounts, List<ProxySignal> proxies,
                           List<RateRiskSignal> rateRisks, List<UserAnomalySignal> userAnomalies) {
    }

    public record ChannelSignal(String platform, Long groupId, String groupName, long requests,
                                long errors, double errorRate, Double avgTtftMs,
                                Double cacheRate, String severity) {
    }

    public record AccountSignal(Long accountId, String name, String platform, String status,
                                Boolean schedulable, String issue, String severity) {
    }

    public record ProxySignal(Long id, String name, String protocol, String status,
                              OffsetDateTime expiresAt, long daysLeft, String issue, String severity) {
    }

    public record RateRiskSignal(Long eventId, String platform, String groupName, String accountName,
                                 String upstreamChannel, BigDecimal oldRatio, BigDecimal newRatio,
                                 BigDecimal groupRatio, String severity) {
    }

    public record UserAnomalySignal(Long userId, String email, long count, long businessLimited,
                                    String severity) {
    }

    public record ProviderSignal(String platform, int groups, long requests, double errorRate,
                                 long abnormalGroups, String verdict) {
    }

    public record MonitoringSignal(String type, String fingerprint, String title, String severity,
                                   String scopeType, String scopeId, String impactSummary,
                                   String evidenceJson) {
    }
}
