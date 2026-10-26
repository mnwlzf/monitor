package com.monitor.platform.bot.user;

import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API Key 用量详情服务。
 *
 * <p>普通用户的邮箱由请求方注入，只能查自己的 Key；管理员必须先指定用户。
 * Key 只显示从 1 开始的序号，不展示真实数据库 ID。一次最多选择 3 个 Key。
 * 每个 Key 先给总计，再按模型分组显示，使用两列表格适配窄屏。</p>
 */
@Service
public class BotKeyUsageService {

    private static final int MAX_KEYS = 3;
    private static final int MAX_DAYS = 90;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final ZoneId ZONE = BusinessTime.ZONE;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final Sub2ApiUserCache userCache;
    private final Sub2UserAdminClient client;
    private final Sub2KeyUsageReader reader;

    public BotKeyUsageService(PlatformRepository platformRepository,
                              PoolAdminKeyResolver adminKeyResolver,
                              Sub2ApiUserCache userCache,
                              Sub2UserAdminClient client,
                              Sub2KeyUsageReader reader) {
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.userCache = userCache;
        this.client = client;
        this.reader = reader;
    }

    /** 普通用户：只能查询请求绑定邮箱自己的 Key。 */
    public BotReport selfReport(String email, String fromDate, String toDate, String keyIndexes) {
        Conn conn = connect();
        if (conn.error() != null) {
            return message(conn.error());
        }
        Long userId = resolveUserId(conn, email);
        if (userId == null) {
            return message("在平台上没有找到你的账号，请联系管理员。");
        }
        return report(conn, userId, nz(email), fromDate, toDate, keyIndexes, "我的 Key 用量");
    }

    /** 管理员：先指定用户，再查询该用户的 Key。 */
    public BotReport adminReport(String account, String fromDate, String toDate, String keyIndexes) {
        Conn conn = connect();
        if (conn.error() != null) {
            return message(conn.error());
        }
        if (isBlank(account)) {
            return message("管理员查询 Key 用量前，请先指定用户邮箱、用户名或用户 ID。");
        }
        Resolution resolution = resolveTarget(conn, account);
        if (resolution.user() == null) {
            return message(resolution.error());
        }
        Sub2AdminUser target = resolution.user();
        if (target.id() == null) {
            return message("这个平台用户缺少内部 ID，暂时无法查询 Key。");
        }
        String label = nz(target.email()) + " / " + nz(target.username());
        return report(conn, target.id(), label, fromDate, toDate, keyIndexes,
                "用户 " + label + " 的 Key 用量");
    }

    private BotReport report(Conn conn, long userId, String userLabel,
                             String fromDate, String toDate, String keyIndexes, String title) {
        DateRange range;
        try {
            range = parseRange(fromDate, toDate);
        } catch (IllegalArgumentException ex) {
            return message(ex.getMessage());
        }

        List<Sub2AdminApiKey> keys = fetchAllKeys(conn, userId);
        if (keys.isEmpty()) {
            return message("这个用户名下还没有 API Key。");
        }
        List<Sub2AdminApiKey> ordered = keys.stream()
                .sorted(Comparator.comparingLong(key -> key.id() == null ? Long.MAX_VALUE : key.id()))
                .toList();

        if (isBlank(keyIndexes)) {
            return keyListReport(title, userLabel, range, ordered);
        }

        List<Integer> indexes;
        try {
            indexes = parseKeyIndexes(keyIndexes);
        } catch (IllegalArgumentException ex) {
            return message(ex.getMessage());
        }
        if (indexes.isEmpty()) {
            return keyListReport(title, userLabel, range, ordered);
        }
        if (indexes.size() > MAX_KEYS) {
            return message("一次最多选择 3 个 Key，请重新选择。");
        }

        List<SelectedKey> selected = new ArrayList<>();
        for (Integer index : indexes) {
            if (index == null || index < 1 || index > ordered.size()) {
                return message("Key 序号 " + index + " 不存在，请从列表重新选择。");
            }
            Sub2AdminApiKey key = ordered.get(index - 1);
            if (key.id() == null) {
                return message("Key 序号 " + index + " 缺少内部标识，暂时无法查询。");
            }
            selected.add(new SelectedKey(index, key));
        }

        if (!reader.isAvailable()) {
            return message("没有配置 Sub2API 只读库，Key 用量详情不可用。需要设置 SUB2API_DB_URL 与 SUB2API_DB_USERNAME。");
        }

        List<Long> ids = selected.stream().map(item -> item.key().id()).toList();
        Map<Long, Sub2KeyUsageReader.KeyUsage> totals = reader.byKeyIds(ids, range.start(), range.end());
        List<Sub2KeyUsageReader.ModelUsage> models = reader.byKeyAndModel(ids, range.start(), range.end());
        Map<Long, List<Sub2KeyUsageReader.ModelUsage>> byKey = models.stream()
                .collect(Collectors.groupingBy(Sub2KeyUsageReader.ModelUsage::apiKeyId));

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(BotReport.paragraph(null, List.of(
                "用户：" + userLabel,
                "时间范围：" + range.label())));
        for (SelectedKey item : selected) {
            Sub2KeyUsageReader.KeyUsage total = totals.getOrDefault(
                    item.key().id(), Sub2KeyUsageReader.KeyUsage.empty(item.key().id()));
            blocks.add(totalBlock(item, total));
            List<Sub2KeyUsageReader.ModelUsage> modelRows =
                    byKey.getOrDefault(item.key().id(), List.of());
            if (modelRows.isEmpty()) {
                blocks.add(BotReport.paragraph("Key " + item.index() + " · 模型明细",
                        List.of("当前时间范围内没有模型用量记录。")));
            } else {
                for (Sub2KeyUsageReader.ModelUsage model : modelRows) {
                    blocks.add(modelBlock(item, model));
                }
            }
        }
        return BotReport.of(title, blocks, List.of("金额单位：USD；总计在前，模型明细按实际消耗倒序。"));
    }

    private BotReport keyListReport(String title, String userLabel, DateRange range,
                                    List<Sub2AdminApiKey> keys) {
        List<String> lines = new ArrayList<>();
        lines.add("用户：" + userLabel);
        lines.add("查询范围：" + range.label());
        lines.add("请选择要查询的 Key（最多 3 个）：");
        for (int i = 0; i < keys.size(); i++) {
            Sub2AdminApiKey key = keys.get(i);
            lines.add((i + 1) + ". " + nz(key.name()) + " · " + nz(key.maskedKey()));
            lines.add("   状态：" + statusText(key.status()) + "｜额度：" + quotaText(key));
            lines.add("   最近使用：" + (key.lastUsedAt() == null
                    ? "从未" : key.lastUsedAt().atZoneSameInstant(ZONE).format(TIME_FORMAT)));
        }
        lines.add("回复序号，例如：1,3");
        return BotReport.of(title, List.of(BotReport.paragraph(null, lines)),
                List.of("不展示真实 Key ID；序号按当前列表顺序映射。金额单位：USD"));
    }

    private BotReport.Block totalBlock(SelectedKey item, Sub2KeyUsageReader.KeyUsage usage) {
        return metricsBlock("Key " + item.index() + " · " + nz(item.key().name()) + " · 总计",
                item.key().maskedKey(), usage.requestCount(), usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationTokens(), usage.cacheReadTokens(), usage.standardCost(),
                usage.actualCost(), usage.averageDurationMs());
    }

    private BotReport.Block modelBlock(SelectedKey item, Sub2KeyUsageReader.ModelUsage usage) {
        return metricsBlock("Key " + item.index() + " · 模型 " + nz(usage.model()),
                item.key().maskedKey(), usage.requestCount(), usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationTokens(), usage.cacheReadTokens(), usage.standardCost(),
                usage.actualCost(), usage.averageDurationMs());
    }

    private BotReport.Block metricsBlock(String heading, String maskedKey,
                                         long requests, long inputTokens, long outputTokens,
                                         long cacheCreation, long cacheRead,
                                         BigDecimal standardCost, BigDecimal actualCost,
                                         double avgDurationMs) {
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("请求数", count(requests)));
        rows.add(BotReport.Row.of("输入 Token", count(inputTokens)));
        rows.add(BotReport.Row.of("输出 Token", count(outputTokens)));
        rows.add(BotReport.Row.of("缓存写入 Token", count(cacheCreation)));
        rows.add(BotReport.Row.of("缓存读取 Token", count(cacheRead)));
        rows.add(BotReport.Row.of("总 Token", count(inputTokens + outputTokens + cacheCreation + cacheRead)));
        rows.add(BotReport.Row.of("标准费用", "$" + money(standardCost)));
        rows.add(BotReport.Row.of("实际消耗", "$" + money(actualCost)));
        rows.add(BotReport.Row.of("平均耗时", Math.round(avgDurationMs) + " ms"));
        return new BotReport.Block(
                heading,
                List.of(BotReport.Col.left("指标"), BotReport.Col.right("数值")),
                rows,
                "Key：" + nz(maskedKey));
    }

    private List<Sub2AdminApiKey> fetchAllKeys(Conn conn, long userId) {
        List<Sub2AdminApiKey> keys = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<Sub2AdminApiKey> current = client.fetchApiKeys(
                    conn.baseUrl(), conn.adminKey(), userId, page, PAGE_SIZE);
            if (current == null || current.isEmpty()) {
                break;
            }
            keys.addAll(current);
            if (current.size() < PAGE_SIZE) {
                break;
            }
        }
        return keys;
    }

    private Long resolveUserId(Conn conn, String email) {
        Long cached = userCache.userId(email).orElse(null);
        if (cached != null) {
            return cached;
        }
        return client.findUserByEmail(conn.baseUrl(), conn.adminKey(), email)
                .map(Sub2AdminUser::id)
                .orElse(null);
    }

    private Resolution resolveTarget(Conn conn, String account) {
        String query = account.trim();
        if (query.matches("\\d+")) {
            try {
                Sub2AdminUser user = client.findUserById(
                        conn.baseUrl(), conn.adminKey(), Long.parseLong(query)).orElse(null);
                return user == null
                        ? Resolution.error("没有找到 ID 为 " + query + " 的平台用户。")
                        : Resolution.found(user);
            } catch (NumberFormatException ex) {
                return Resolution.error("用户 ID 格式不正确。");
            }
        }

        List<Sub2AdminUser> candidates = client.searchUsers(
                conn.baseUrl(), conn.adminKey(), query, 20);
        boolean emailQuery = query.contains("@");
        List<Sub2AdminUser> exact = candidates.stream()
                .filter(user -> emailQuery
                        ? equalsIgnoreCase(user.email(), query)
                        : equalsIgnoreCase(user.username(), query))
                .toList();
        if (exact.size() == 1) {
            return Resolution.found(exact.get(0));
        }
        if (exact.size() > 1) {
            return Resolution.error("存在多个匹配用户，请提供用户 ID：" + candidateText(exact));
        }
        if (!candidates.isEmpty()) {
            return Resolution.error("没有精确匹配，请提供用户 ID 或完整邮箱/用户名：" + candidateText(candidates));
        }
        return Resolution.error("没有找到匹配的平台用户。");
    }

    private String candidateText(List<Sub2AdminUser> candidates) {
        return "\n" + candidates.stream().limit(10)
                .map(user -> "ID " + user.id() + " | "
                        + nz(user.email()) + " | " + nz(user.username()))
                .collect(Collectors.joining("\n"));
    }

    private Conn connect() {
        PlatformEntity platform = platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .findFirst()
                .orElse(null);
        if (platform == null) {
            return Conn.error("平台还没配置好（未标记号池监控源），请联系管理员。");
        }
        String adminKey = adminKeyResolver.resolve(platform).orElse(null);
        if (adminKey == null) {
            return Conn.error("平台管理员密钥未配置，请联系管理员。");
        }
        return new Conn(platform.getUrl(), adminKey, null);
    }

    /** 默认今天 00:00 到当前；自选范围按自然日计算，最多 90 天。 */
    static DateRange parseRange(String fromDate, String toDate) {
        boolean hasFrom = !isBlank(fromDate);
        boolean hasTo = !isBlank(toDate);
        if (!hasFrom && !hasTo) {
            OffsetDateTime now = OffsetDateTime.now(ZONE);
            OffsetDateTime start = LocalDate.now(ZONE).atStartOfDay(ZONE).toOffsetDateTime();
            return new DateRange(start, now, "今天 00:00 - " + now.format(TIME_FORMAT), 1);
        }
        if (!hasFrom || !hasTo) {
            throw new IllegalArgumentException("日期范围必须同时提供开始日期和结束日期，格式 yyyy-MM-dd。");
        }
        try {
            LocalDate from = LocalDate.parse(fromDate.trim(), DATE_FORMAT);
            LocalDate to = LocalDate.parse(toDate.trim(), DATE_FORMAT);
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("开始日期不能晚于结束日期。");
            }
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days > MAX_DAYS) {
                throw new IllegalArgumentException("日期范围最多 90 天，请缩小范围。");
            }
            return new DateRange(
                    from.atStartOfDay(ZONE).toOffsetDateTime(),
                    to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),
                    from + " 至 " + to + "（共 " + days + " 天）", days);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("日期格式错误，必须是 yyyy-MM-dd。");
        }
    }

    /** 解析序号，支持逗号、中文逗号、空格和顿号；去重后最多 3 个。 */
    static List<Integer> parseKeyIndexes(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (String token : raw.trim().split("[,，、\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                int value = Integer.parseInt(token);
                if (value <= 0) {
                    throw new IllegalArgumentException("Key 序号必须是从 1 开始的数字。");
                }
                result.add(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Key 序号格式错误，请回复类似 1,3 的序号。");
            }
        }
        if (result.size() > MAX_KEYS) {
            throw new IllegalArgumentException("一次最多选择 3 个 Key，请重新选择。");
        }
        return List.copyOf(result);
    }

    private static BotReport message(String text) {
        return BotReport.fromMarkdown(text);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String quotaText(Sub2AdminApiKey key) {
        double quota = key.quota() == null ? 0d : key.quota();
        double used = key.quotaUsed() == null ? 0d : key.quotaUsed();
        if (quota <= 0) {
            return "不限（已用 " + money(BigDecimal.valueOf(used)) + "）";
        }
        return money(BigDecimal.valueOf(used)) + " / " + money(BigDecimal.valueOf(quota));
    }

    private static String statusText(String status) {
        if (isBlank(status)) {
            return "—";
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "active" -> "正常";
            case "disabled", "banned" -> "已停用";
            case "expired" -> "已过期";
            case "exhausted" -> "额度用尽";
            default -> status;
        };
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static String nz(String value) {
        return isBlank(value) ? "—" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record DateRange(OffsetDateTime start, OffsetDateTime end, String label, long days) {
    }

    private record SelectedKey(int index, Sub2AdminApiKey key) {
    }

    private record Conn(String baseUrl, String adminKey, String error) {
        private static Conn error(String message) {
            return new Conn(null, null, message);
        }
    }

    private record Resolution(Sub2AdminUser user, String error) {
        private static Resolution found(Sub2AdminUser user) {
            return new Resolution(user, null);
        }

        private static Resolution error(String message) {
            return new Resolution(null, message);
        }
    }
}
