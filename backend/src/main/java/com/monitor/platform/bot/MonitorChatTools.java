package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.api.dto.ScheduledTaskResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.service.ScheduledTaskAdminService;
import com.monitor.platform.api.service.UpstreamAdminService;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 对话可查询的数据入口。
 *
 * <p>每个查询都有两份产物，共用同一套取数逻辑：</p>
 * <ul>
 *     <li>{@code @Tool} 方法返回<strong>纯文本</strong>：给 Spring AI 的 function calling 用，
 *         也是图片渲染不出来时的文本兜底；</li>
 *     <li>{@code *Report()} 方法返回<strong>结构化报表</strong>：确定性命令（{@code /号池} 等）
 *         用它渲染成表格 / 热力图图片，避免长内容被截断或排成难读的项目符号列表。</li>
 * </ul>
 *
 * <p>全部是只读查询，绝不触发采集、删除等写操作。</p>
 */
@Component
public class MonitorChatTools {

    /** 允许的时间窗，避免模型或用户传入奇怪的值。 */
    private static final Set<String> RANGES = Set.of("90m", "1h", "6h", "12h", "24h", "7d", "30d", "90d");

    /** 热力图最多展示的行数（有流量的账号，按请求数倒序）。 */
    private static final int MAX_HEAT_ROWS = 20;

    /** 热力图最多展示的列数；超过就按相邻桶合并，避免图片过宽。 */
    private static final int MAX_HEAT_COLS = 32;

    private static final DateTimeFormatter BUCKET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter BUCKET_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final PlatformRepository platformRepository;
    private final UpstreamAdminService upstreamAdminService;
    private final PoolQueryService poolQueryService;
    private final ScheduledTaskAdminService scheduledTaskAdminService;

    public MonitorChatTools(PlatformRepository platformRepository,
                            UpstreamAdminService upstreamAdminService,
                            PoolQueryService poolQueryService,
                            ScheduledTaskAdminService scheduledTaskAdminService) {
        this.platformRepository = platformRepository;
        this.upstreamAdminService = upstreamAdminService;
        this.poolQueryService = poolQueryService;
        this.scheduledTaskAdminService = scheduledTaskAdminService;
    }

    // ============================================================
    // 大模型工具：返回纯文本
    // ============================================================

    @Tool(description = "查询所有上游平台的概览：平台名、类型、账号数、异常账号数与余额合计")
    public String platformOverview() {
        return platformReport().toPlainText();
    }

    @Tool(description = "查询号池账号在指定时间窗内的请求数、缓存命中率与首 Token 耗时。"
            + "range 取值 90m / 1h / 6h / 12h / 24h / 7d / 30d / 90d，默认 24h")
    public String poolOverview(String range) {
        return poolReport(range).toPlainText();
    }

    /**
     * 号池时间桶热力图。
     *
     * <p><strong>刻意不返回表格数据</strong>：热力图是 20 行 × 24 列以上的矩阵，
     * 大模型抄不准 —— 它会把矩阵「重写」一遍，图案就和页面完全对不上了。
     * 这里只返回一个标记与关键数字，真正的热力图由后端按标记确定性渲染。</p>
     */
    @Tool(description = """
            查询号池渠道的「时间桶热力图」（每个渠道一行、每个时间桶一格，按缓存命中率着色）。
            需要展示号池趋势、热力、时间分布时用这个工具。
            返回内容里有一行形如 [[HEATMAP:24h]] 的标记：把它原样放在回答里，
            系统会在那个位置渲染出真正的热力图。不要自己画表格、色块或 emoji。
            range 取值 90m / 1h / 6h / 12h / 24h / 7d / 30d / 90d，默认 24h。
            """)
    public String poolHeatmap(String range) {
        return heatmapDirective(range);
    }

    /** 生成「热力图标记 + 关键数字」，供大模型引用。 */
    public String heatmapDirective(String range) {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return "还没有把任何平台标记为「号池监控源」，无法查询号池数据。";
        }
        String window = normalizeRange(range);
        PoolHeatmapResponse heatmap = poolQueryService.heatmap(platformId, window, null, null, null, null);
        List<PoolHeatmapResponse.Row> active = heatmap.rows().stream()
                .filter(row -> row.total() != null && row.total().requests() > 0)
                .sorted(Comparator.comparingLong((PoolHeatmapResponse.Row row) -> row.total().requests()).reversed())
                .toList();
        if (active.isEmpty()) {
            return "近 " + window + " 号池没有任何流量。";
        }

        long totalRequests = active.stream().mapToLong(row -> row.total().requests()).sum();
        PoolHeatmapResponse.Row best = active.stream()
                .filter(row -> row.total().cacheHitRate() != null)
                .max(Comparator.comparingDouble(row -> row.total().cacheHitRate()))
                .orElse(null);
        PoolHeatmapResponse.Row worst = active.stream()
                .filter(row -> row.total().cacheHitRate() != null)
                .min(Comparator.comparingDouble(row -> row.total().cacheHitRate()))
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("[[HEATMAP:").append(window).append("]]\n");
        sb.append("（系统会在标记处渲染出近 ").append(window)
                .append(" 的号池热力图，请勿自己画表格或色块）\n");
        sb.append("关键数字：有流量渠道 ").append(active.size())
                .append(" 个，请求合计 ").append(totalRequests);
        if (best != null) {
            sb.append("；缓存率最高 ").append(percent(best.total().cacheHitRate()))
                    .append("（").append(label(best)).append("）");
        }
        if (worst != null) {
            sb.append("，最低 ").append(percent(worst.total().cacheHitRate()))
                    .append("（").append(label(worst)).append("）");
        }
        sb.append("。");
        return sb.toString();
    }

    @Tool(description = "查询号池直连库增量采集的状态：是否生效、游标位置、最新明细滞后多少秒")
    public String poolIngestStatus() {
        return ingestReport().toPlainText();
    }

    @Tool(description = "按名称关键字模糊查询账号的余额、额度与最近采集状态")
    public String accountDetail(String keyword) {
        return accountReport(keyword).toPlainText();
    }

    @Tool(description = "查询最近的账号 / 密钥 / 渠道变更记录。"
            + "filter 可选：填「倍率」只看渠道倍率变化（上调会标成 WARNING，直接关系成本），"
            + "也可填渠道名或账号名做关键字过滤，留空看全部。limit 为返回条数，默认 5，最多 20")
    public String recentChanges(String filter, int limit) {
        return changesReport(filter, limit).toPlainText();
    }

    @Tool(description = "查询全平台账号的用量排行：余额、今日消耗、累计消耗与累计请求数。"
            + "limit 为返回条数，默认 10，最多 30")
    public String usageRanking(int limit) {
        return usageReport(limit).toPlainText();
    }

    @Tool(description = "查询当前异常账号名单：被停用、最近采集失败或凭证失效的账号，并给出原因")
    public String abnormalAccounts() {
        return abnormalReport().toPlainText();
    }

    @Tool(description = "查询定时任务的启用状态与最近一次执行结果，用来确认采集任务是否在正常运行")
    public String scheduledTasks() {
        return taskReport().toPlainText();
    }

    // ============================================================
    // 结构化报表：给确定性命令渲染图片用
    // ============================================================

    /** 上游平台概览。 */
    public BotReport platformReport() {
        List<PlatformResponse> platforms = upstreamAdminService.listPlatforms();
        if (platforms.isEmpty()) {
            return BotReport.fromMarkdown("当前还没有接入任何上游平台。");
        }

        List<BotReport.Row> rows = new ArrayList<>();
        for (PlatformResponse platform : platforms) {
            List<AccountResponse> accounts = upstreamAdminService.listAccounts(platform.id());
            long abnormal = accounts.stream().filter(a -> !Boolean.TRUE.equals(a.status())).count();
            BigDecimal balance = accounts.stream()
                    .map(AccountResponse::balance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(BotReport.Row.of(
                    platform.name(),
                    platform.platform(),
                    String.valueOf(accounts.size()),
                    String.valueOf(abnormal),
                    balance.stripTrailingZeros().toPlainString()));
        }

        BotReport.Block table = new BotReport.Block("一、上游平台", List.of(
                BotReport.Col.left("平台"),
                BotReport.Col.left("类型"),
                BotReport.Col.right("账号数"),
                BotReport.Col.right("异常"),
                BotReport.Col.right("余额合计")), rows, null);

        return BotReport.of("上游平台概览", List.of(table), List.of());
    }

    /**
     * 号池监控：左侧是账号汇总，右侧是时间桶热力色块。
     *
     * <p>列与配色都对齐前端「号池 → 趋势矩阵」页，这样 QQ 里看到的和页面上是同一张表。</p>
     */
    public BotReport poolReport(String range) {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return BotReport.fromMarkdown("还没有把任何平台标记为「号池监控源」，无法查询号池数据。");
        }
        String window = normalizeRange(range);
        PoolHeatmapResponse heatmap = poolQueryService.heatmap(platformId, window, null, null, null, null);
        if (heatmap.rows().isEmpty()) {
            return BotReport.fromMarkdown("号池里还没有账号（或尚未采集过）。");
        }

        List<PoolHeatmapResponse.Row> active = heatmap.rows().stream()
                .filter(row -> row.total() != null && row.total().requests() > 0)
                .sorted(Comparator.comparingLong((PoolHeatmapResponse.Row row) -> row.total().requests()).reversed())
                .toList();
        List<PoolHeatmapResponse.Row> idle = heatmap.rows().stream()
                .filter(row -> row.total() == null || row.total().requests() == 0)
                .toList();

        List<BotReport.Block> blocks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!active.isEmpty()) {
            Buckets buckets = buckets(heatmap);
            List<BotReport.Col> cols = new ArrayList<>(List.of(
                    BotReport.Col.left("平台 / 账号"),
                    BotReport.Col.right("缓存率"),
                    BotReport.Col.right("首 TOKEN"),
                    BotReport.Col.right("每秒 TOKEN"),
                    BotReport.Col.right("请求")));
            for (String label : buckets.labels()) {
                cols.add(BotReport.Col.heat(label));
            }

            List<BotReport.Row> rows = new ArrayList<>();
            int limit = Math.min(active.size(), MAX_HEAT_ROWS);
            for (int i = 0; i < limit; i++) {
                PoolHeatmapResponse.Row row = active.get(i);
                List<BotReport.Cell> cells = new ArrayList<>(List.of(
                        BotReport.Cell.of(label(row)),
                        BotReport.Cell.of(percent(row.total().cacheHitRate())),
                        BotReport.Cell.of(seconds(row.total().avgFirstTokenMs())),
                        BotReport.Cell.of(tps(row.total().tokensPerSecond())),
                        BotReport.Cell.of(String.valueOf(row.total().requests()))));
                for (Double heat : buckets.heatOf(row)) {
                    cells.add(BotReport.Cell.heat(heat));
                }
                rows.add(new BotReport.Row(cells));
            }

            blocks.add(new BotReport.Block("一、有流量的渠道（近 " + window + "，按请求数排序）",
                    cols, rows, null));
            if (active.size() > limit) {
                notes.add("有流量的渠道共 " + active.size() + " 个，这里只列出请求数最高的 " + limit + " 个。");
            }
        } else {
            notes.add("近 " + window + " 号池没有任何流量。");
        }

        if (!idle.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (PoolHeatmapResponse.Row row : idle) {
                names.add(label(row));
            }
            blocks.add(BotReport.paragraph("二、无流量的渠道（共 " + idle.size() + " 个）",
                    List.of(String.join("、", names))));
        }

        long totalRequests = active.stream()
                .mapToLong(row -> row.total().requests())
                .sum();
        notes.add("合计：有流量渠道 " + active.size() + " 个，请求 " + totalRequests
                + "；无流量渠道 " + idle.size() + " 个。");
        notes.add("色块为该时段缓存命中率：越绿越高、越红越低，灰色表示该时段没有流量。");

        return BotReport.of("号池监控（近 " + window + "）", blocks, notes);
    }

    /** 号池直连库增量采集状态。 */
    public BotReport ingestReport() {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return BotReport.fromMarkdown("还没有把任何平台标记为「号池监控源」。");
        }
        PoolIngestStatusResponse status = poolQueryService.ingestStatus(platformId);
        if (!status.enabled()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集未启用（SUB2API_DB_ENABLED=false），分钟级数据依赖 10 分钟一轮的接口采集。");
        }
        if (!status.configured()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集已开启，但数据源没配全（缺 URL 或用户名），任务每轮跳过。");
        }
        if (!status.passwordConfigured()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集已开启，但没配密码；若上游要求密码认证，连接会失败、游标不会推进。");
        }
        if (status.lastUsageLogId() <= 0L) {
            return BotReport.fromMarkdown("号池直连库增量采集已就绪，但游标尚未初始化（还没成功拉取过）。");
        }

        List<BotReport.Row> rows = List.of(
                BotReport.Row.of("游标 usage_logs.id", String.valueOf(status.lastUsageLogId())),
                BotReport.Row.of("游标最后推进", time(status.lastRunAt())),
                BotReport.Row.of("最新明细", time(status.latestSampleAt())),
                BotReport.Row.of("数据滞后", status.lagSeconds() == null ? "-" : humanDuration(status.lagSeconds())));

        BotReport.Block table = new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), rows, null);

        return BotReport.of("号池直连库增量采集：正常", List.of(table), List.of());
    }

    /** 按关键字查账号余额与状态。 */
    public BotReport accountReport(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return BotReport.fromMarkdown("请提供账号名称关键字，例如：/账号 主账号");
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        List<BotReport.Row> rows = new ArrayList<>();
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            for (AccountResponse account : upstreamAdminService.listAccounts(platform.id())) {
                String name = account.displayName() == null ? "" : account.displayName();
                String login = account.loginName() == null ? "" : account.loginName();
                if (!name.toLowerCase(Locale.ROOT).contains(needle)
                        && !login.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                rows.add(BotReport.Row.of(
                        name.isBlank() ? ("账号 " + account.id()) : name,
                        platform.name(),
                        Boolean.TRUE.equals(account.status()) ? "正常" : "异常",
                        decimal(account.balance()),
                        decimal(account.usedQuota()),
                        time(account.lastCollectedAt())));
            }
        }
        if (rows.isEmpty()) {
            return BotReport.fromMarkdown("没有找到名称包含「" + keyword.trim() + "」的账号。");
        }

        BotReport.Block table = new BotReport.Block("匹配到 " + rows.size() + " 个账号", List.of(
                BotReport.Col.left("账号"),
                BotReport.Col.left("平台"),
                BotReport.Col.left("状态"),
                BotReport.Col.right("余额"),
                BotReport.Col.right("已用额度"),
                BotReport.Col.left("最近采集")), rows, null);

        return BotReport.of("账号查询：" + keyword.trim(), List.of(table), List.of());
    }

    /**
     * 最近的账号 / 密钥 / 渠道变更。
     *
     * <p>排序刻意是「警告优先，再按时间倒序」：渠道<strong>涨价</strong>直接关系成本，
     * 必须排在最前面，而不是被「新增了一个渠道」这种噪声淹掉。</p>
     *
     * @param filter       关键字；填「倍率」只看渠道倍率变化，也可以填渠道名/账号名。
     *                     纯数字会被当成条数（例如 {@code /变更 20}）
     * @param defaultLimit 返回条数，默认 5，上限 20
     */
    public BotReport changesReport(String filter, int defaultLimit) {
        int size = defaultLimit <= 0 ? 5 : Math.min(defaultLimit, 20);
        String keyword = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        // /变更 20 这种纯数字写法当条数处理，剩下的才当关键字
        if (keyword.matches("\\d{1,3}")) {
            size = Math.max(1, Math.min(Integer.parseInt(keyword), 20));
            keyword = "";
        }

        List<PlatformChange> matched = new ArrayList<>();
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            // 关键字过滤在内存里做，所以多取一些候选，避免「近 5 条里恰好没有倍率变化」就查不到
            for (UpstreamChangeEventResponse event
                    : upstreamAdminService.listChanges(platform.id(), size * 4)) {
                if (matchesChange(event, keyword)) {
                    matched.add(new PlatformChange(platform.name(), event));
                }
            }
        }
        if (matched.isEmpty()) {
            return BotReport.fromMarkdown(keyword.isEmpty()
                    ? "最近没有任何变更记录。"
                    : "最近没有匹配「" + filter + "」的变更记录。");
        }

        matched.sort(Comparator
                .comparing((PlatformChange item) -> warning(item.event()) ? 0 : 1)
                .thenComparing((PlatformChange item) -> inUse(item.event()) ? 0 : 1)
                .thenComparing(item -> item.event().detectedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())));
        List<PlatformChange> shown = matched.subList(0, Math.min(size, matched.size()));

        List<BotReport.Row> rows = new ArrayList<>();
        for (PlatformChange item : shown) {
            rows.add(BotReport.Row.of(
                    time(item.event().detectedAt()),
                    warning(item.event()) ? "⚠ 警告" : "信息",
                    item.platform(),
                    channelName(item.event()),
                    inUse(item.event()) ? "使用中" : "—",
                    changeText(item.event())));
        }

        BotReport.Block table = new BotReport.Block("最近 " + rows.size() + " 条"
                + (keyword.isEmpty() ? "（警告优先）" : "（筛选：" + filter + "）"), List.of(
                BotReport.Col.left("时间"),
                BotReport.Col.left("级别"),
                BotReport.Col.left("平台"),
                BotReport.Col.left("渠道"),
                BotReport.Col.left("使用中"),
                BotReport.Col.left("内容")), rows, null);

        List<String> notes = new ArrayList<>();
        long warnings = matched.stream().filter(item -> warning(item.event())).count();
        if (warnings > 0) {
            notes.add("其中 " + warnings + " 条是警告（倍率上调 / 渠道移除），值得优先看。");
        }
        notes.add("想看渠道涨价可以问「最近有哪些渠道涨价了」，或发 /变更 倍率；只看使用中的渠道发「/变更 使用中」。");
        return BotReport.of("变更记录", List.of(table), notes);
    }

    /** 一条变更 + 它属于哪个平台。 */
    private record PlatformChange(String platform, UpstreamChangeEventResponse event) {
    }

    private static boolean warning(UpstreamChangeEventResponse event) {
        return "WARNING".equalsIgnoreCase(event.severity());
    }

    /**
     * 渠道名。
     *
     * <p>变更事件里存的是上游渠道 ID，而页面上只有渠道名 —— 只给 ID 等于让人自己去猜是哪个渠道。
     * 名字由查询 join upstream_groups 带出来；取不到时退回 ID（至少还能查）。</p>
     */
    private static String channelName(UpstreamChangeEventResponse event) {
        if (event.groupName() != null && !event.groupName().isBlank()) {
            return event.groupName();
        }
        return event.entityKey() == null ? "—" : event.entityKey();
    }    /** 变更是否发生在「使用中」的渠道/密钥上（由采集侧按状态判定，见 V14 迁移）。 */
    private static boolean inUse(UpstreamChangeEventResponse event) {
        return Boolean.TRUE.equals(event.inUse());
    }

    /** 关键字过滤：填「倍率」只看倍率类变化，其它按 内容/字段/新旧值 模糊匹配。 */
    private static boolean matchesChange(UpstreamChangeEventResponse event, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        if ("使用中".equals(keyword) || "在用".equals(keyword) || "inuse".equals(keyword)) {
            return Boolean.TRUE.equals(event.inUse());
        }
        if ("倍率".equals(keyword) || "rate".equals(keyword)) {
            return "RATE_CHANGED".equalsIgnoreCase(event.changeType())
                    || "BASE_RATE_CHANGED".equalsIgnoreCase(event.changeType());
        }
        String haystack = String.join(" ",
                String.valueOf(event.message()),
                String.valueOf(event.changeType()),
                String.valueOf(event.fieldName()),
                String.valueOf(event.entityKey()),
                String.valueOf(event.oldValue()),
                String.valueOf(event.newValue())).toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    /** 变更的可读说明，标量变化补上「旧值 → 新值」。 */
    private static String changeText(UpstreamChangeEventResponse event) {
        String base = event.message() == null || event.message().isBlank()
                ? event.changeType() + " " + event.fieldName()
                : event.message();
        String before = scalar(event.oldValue());
        String after = scalar(event.newValue());
        if (before == null && after == null) {
            return base;
        }
        return base + "（" + (before == null ? "?" : before) + " → " + (after == null ? "?" : after) + "）";
    }

    /** 只展示短标量；新增/轮换这类整块 JSON 不往表格里塞。 */
    private static String scalar(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("^\"|\"$", "");
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return null;
        }
        return trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24) + "…";
    }

    // ============================================================
    // 管理端只读查询：用量排行 / 异常名单 / 定时任务
    // ============================================================

    /**
     * 全平台账号用量排行。
     *
     * <p>取每个账号的最新用量快照，按累计消耗倒序 —— 页面上是「用量看板」，
     * 这里给群里一个「谁在花钱」的快速答案。</p>
     */
    public BotReport usageReport(int limit) {
        int size = limit <= 0 ? 10 : Math.min(limit, 30);
        record UsageRow(String platform, AccountUsageDashboardResponse usage, BigDecimal cost) {
        }

        List<UsageRow> all = new ArrayList<>();
        OffsetDateTime latest = null;
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            for (AccountUsageDashboardResponse usage : upstreamAdminService.listUsageDashboard(platform.id())) {
                all.add(new UsageRow(platform.name(), usage, cost(usage)));
                if (usage.collectedAt() != null && (latest == null || usage.collectedAt().isAfter(latest))) {
                    latest = usage.collectedAt();
                }
            }
        }
        if (all.isEmpty()) {
            return BotReport.fromMarkdown("还没有任何用量快照，等第一轮采集完成后再查。");
        }

        all.sort(Comparator.comparing((UsageRow row) -> row.cost()).reversed());
        List<UsageRow> top = all.subList(0, Math.min(size, all.size()));

        List<BotReport.Row> rows = new ArrayList<>();
        for (UsageRow row : top) {
            AccountUsageDashboardResponse usage = row.usage();
            String name = usage.displayName() == null || usage.displayName().isBlank()
                    ? ("账号 " + usage.accountId())
                    : usage.displayName();
            rows.add(BotReport.Row.of(
                    name,
                    row.platform(),
                    decimal(usage.balance()),
                    metric(usage.metrics(), "today_actual_cost"),
                    decimal(usage.totalActualCost() != null ? usage.totalActualCost() : usage.totalCost()),
                    usage.totalRequests() == null ? "-" : String.valueOf(usage.totalRequests())));
        }

        BotReport.Block table = new BotReport.Block(
                "累计消耗 TOP " + rows.size() + "（共 " + all.size() + " 个账号有快照）", List.of(
                BotReport.Col.left("账号"),
                BotReport.Col.left("平台"),
                BotReport.Col.right("余额"),
                BotReport.Col.right("今日消耗"),
                BotReport.Col.right("累计消耗"),
                BotReport.Col.right("累计请求")), rows, null);

        List<String> notes = new ArrayList<>();
        if (latest != null) {
            notes.add("数据来自最近一次采集快照（最晚 " + time(latest) + "）。");
        }
        if (all.size() > rows.size()) {
            notes.add("这里只列出累计消耗最高的 " + rows.size() + " 个，其余 " + (all.size() - rows.size()) + " 个略过。");
        }
        return BotReport.of("账号用量排行", List.of(table), notes);
    }

    /**
     * 异常账号名单。
     *
     * <p>「异常」= 被停用 / 最近采集失败或部分成功 / 凭证失效，
     * 和页面上账号列表里那几个红色标记是同一套判据。</p>
     */
    public BotReport abnormalReport() {
        List<BotReport.Row> rows = new ArrayList<>();
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            for (AccountResponse account : upstreamAdminService.listAccounts(platform.id())) {
                String reason = abnormalReason(account);
                if (reason == null) {
                    continue;
                }
                rows.add(BotReport.Row.of(
                        accountLabel(account),
                        platform.name(),
                        reason,
                        decimal(account.balance()),
                        time(account.lastCollectedAt())));
            }
        }
        if (rows.isEmpty()) {
            return BotReport.fromMarkdown("所有账号状态正常，没有需要处理的异常。");
        }

        BotReport.Block table = new BotReport.Block("异常账号 " + rows.size() + " 个", List.of(
                BotReport.Col.left("账号"),
                BotReport.Col.left("平台"),
                BotReport.Col.left("原因"),
                BotReport.Col.right("余额"),
                BotReport.Col.left("最近采集")), rows, null);

        return BotReport.of("异常账号", List.of(table), List.of());
    }

    /**
     * 定时任务状态。
     *
     * <p>采集出问题时第一个要看的就是这里：任务有没有被停用、最近一次跑成功没有。
     * 启用中的排在前面，失败的任务把错误信息单独列一段。</p>
     */
    public BotReport taskReport() {
        List<ScheduledTaskResponse> tasks = scheduledTaskAdminService.listTasks();
        if (tasks.isEmpty()) {
            return BotReport.fromMarkdown("还没有配置任何定时任务。");
        }
        long enabled = tasks.stream().filter(task -> Boolean.TRUE.equals(task.enabled())).count();

        List<ScheduledTaskResponse> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator
                .comparing((ScheduledTaskResponse task) -> !Boolean.TRUE.equals(task.enabled()))
                .thenComparing(ScheduledTaskResponse::lastRunAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<BotReport.Row> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (ScheduledTaskResponse task : sorted) {
            String name = task.taskName() == null || task.taskName().isBlank() ? task.taskCode() : task.taskName();
            rows.add(BotReport.Row.of(
                    name,
                    task.handlerName() == null || task.handlerName().isBlank() ? task.taskCode() : task.handlerName(),
                    Boolean.TRUE.equals(task.enabled()) ? "启用" : "停用",
                    time(task.lastRunAt()),
                    runResult(task.lastRunStatus())));
            if ("FAILED".equalsIgnoreCase(task.lastRunStatus())) {
                failures.add(name + "：" + (task.lastRunMessage() == null || task.lastRunMessage().isBlank()
                        ? "没有留下错误信息"
                        : task.lastRunMessage()));
            }
        }

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block(null, List.of(
                BotReport.Col.left("任务"),
                BotReport.Col.left("处理器"),
                BotReport.Col.left("状态"),
                BotReport.Col.left("最近执行"),
                BotReport.Col.left("结果")), rows, null));
        if (!failures.isEmpty()) {
            blocks.add(BotReport.paragraph("最近执行失败 " + failures.size() + " 个", failures));
        }

        return BotReport.of("定时任务（启用 " + enabled + " / 共 " + tasks.size() + "）", blocks, List.of());
    }

    /** 异常判据；正常账号返回 null。 */
    private static String abnormalReason(AccountResponse account) {
        List<String> reasons = new ArrayList<>();
        if (!Boolean.TRUE.equals(account.status())) {
            reasons.add("已停用");
        }
        if ("FAILED".equalsIgnoreCase(account.lastCollectStatus())) {
            reasons.add("采集失败");
        } else if ("PARTIAL".equalsIgnoreCase(account.lastCollectStatus())) {
            reasons.add("部分成功");
        }
        if ("INVALID".equalsIgnoreCase(account.authStatus())) {
            reasons.add("凭证失效");
        }
        return reasons.isEmpty() ? null : String.join(" / ", reasons);
    }

    private static String accountLabel(AccountResponse account) {
        String name = account.displayName() == null ? "" : account.displayName();
        return name.isBlank() ? ("账号 " + account.id()) : name;
    }

    /** 用量快照里今日消耗藏在 metrics JSON 里，平台没提供时显示 -。 */
    private static String metric(JsonNode node, String field) {
        if (node == null) {
            return "-";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "-";
        }
        return value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.asText();
    }

    /** 排序用：累计实际成本优先，缺失时退回累计标准消耗。 */
    private static BigDecimal cost(AccountUsageDashboardResponse usage) {
        if (usage.totalActualCost() != null) {
            return usage.totalActualCost();
        }
        if (usage.totalCost() != null) {
            return usage.totalCost();
        }
        return BigDecimal.ZERO;
    }

    private static String runResult(String status) {
        if (status == null || status.isBlank()) {
            return "未执行";
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> "成功";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 热力图列。
     *
     * <p>保留全部时间桶（只在超过 {@link #MAX_HEAT_COLS} 时才合并相邻桶），
     * 但<strong>不逐列打标签</strong> —— 前端也是每隔几列标一次，
     * 逐列标会把列撑得很宽、热力块反而变小。</p>
     */
    private Buckets buckets(PoolHeatmapResponse heatmap) {
        List<OffsetDateTime> source = heatmap.buckets();
        boolean day = "day".equalsIgnoreCase(heatmap.granularity());
        int step = Math.max(1, (int) Math.ceil(source.size() / (double) MAX_HEAT_COLS));

        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < source.size(); i += step) {
            List<Integer> group = new ArrayList<>();
            for (int j = i; j < Math.min(i + step, source.size()); j++) {
                group.add(j);
            }
            groups.add(group);
        }

        // 大约每 8 列标一次时间，其余列留空表头
        int labelStep = Math.max(1, groups.size() / 8);
        List<String> labels = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            if (i % labelStep != 0) {
                labels.add("");
                continue;
            }
            int bucketIndex = groups.get(i).get(0);
            labels.add((day ? BUCKET_DAY : BUCKET_TIME).format(source.get(bucketIndex)));
        }
        return new Buckets(labels, groups);
    }

    private String label(PoolHeatmapResponse.Row row) {
        String name = row.name() == null || row.name().isBlank() ? ("账号 " + row.externalAccountId()) : row.name();
        String platform = row.platform() == null || row.platform().isBlank() ? "未知平台" : row.platform();
        return platform + " / " + name;
    }

    /** 找到被标记为「号池监控源」的平台；没有就返回 null。 */
    private Integer poolPlatformId() {
        return platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .map(PlatformEntity::getId)
                .findFirst()
                .orElse(null);
    }

    private String normalizeRange(String range) {
        if (range == null) {
            return "24h";
        }
        String normalized = range.trim().toLowerCase(Locale.ROOT);
        return RANGES.contains(normalized) ? normalized : "24h";
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** 首 Token 用秒更好读（页面上也是 6.4s 这种写法）。 */
    private static String seconds(Double millis) {
        return millis == null ? "-" : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String tps(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "-" : value.toString().replace('T', ' ').substring(0, 16);
    }

    private static String humanDuration(long seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0L, seconds));
        if (duration.toMinutes() < 1) {
            return seconds + " 秒";
        }
        if (duration.toHours() < 1) {
            return duration.toMinutes() + " 分钟";
        }
        if (duration.toDays() < 1) {
            return duration.toHours() + " 小时";
        }
        return duration.toDays() + " 天";
    }

    /**
     * 热力图列的分组信息。
     *
     * @param labels 列标题（合并后的时间桶）
     * @param groups 每列对应的原始桶下标
     */
    private record Buckets(List<String> labels, List<List<Integer>> groups) {

        /** 取某一行在每一列的热力值：组内按请求数加权平均缓存率，全无流量时为 null。 */
        List<Double> heatOf(PoolHeatmapResponse.Row row) {
            List<Double> result = new ArrayList<>(groups.size());
            for (List<Integer> group : groups) {
                double weighted = 0;
                long requests = 0;
                for (int index : group) {
                    if (index >= row.cells().size()) {
                        continue;
                    }
                    PoolHeatmapResponse.Metrics cell = row.cells().get(index);
                    if (cell == null || cell.requests() <= 0 || cell.cacheHitRate() == null) {
                        continue;
                    }
                    weighted += cell.cacheHitRate() * cell.requests();
                    requests += cell.requests();
                }
                result.add(requests == 0 ? null : weighted / requests);
            }
            return result;
        }
    }
}
