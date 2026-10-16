package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.ScheduledTaskResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.service.ScheduledTaskAdminService;
import com.monitor.platform.api.service.UpstreamAdminService;
import com.monitor.platform.api.service.UpstreamUsageService;
import com.monitor.platform.bot.pool.PoolReportService;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.ReportFormats;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * <p>只读查询，绝不触发采集、删除等写操作；号池报表的列、配色与抽稀见 {@link PoolReportService}。</p>
 */
@Component
public class MonitorChatTools {

    private final UpstreamAdminService upstreamAdminService;
    private final UpstreamUsageService upstreamUsageService;
    private final PoolReportService poolReports;
    private final ScheduledTaskAdminService scheduledTaskAdminService;

    public MonitorChatTools(UpstreamAdminService upstreamAdminService,
                            UpstreamUsageService upstreamUsageService,
                            PoolReportService poolReports,
                            ScheduledTaskAdminService scheduledTaskAdminService) {
        this.upstreamAdminService = upstreamAdminService;
        this.upstreamUsageService = upstreamUsageService;
        this.poolReports = poolReports;
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
        return poolReports.heatmapDirective(range);
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
            + "倒挂口径只看本平台分组倍率：上游渠道新倍率 > 本平台分组倍率 即为倒挂；"
            + "不要乘账号倍率，也不要因为缺少账号倍率而说无法计算。"
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
     * 号池监控报表：列、配色与抽稀见 {@link PoolReportService#report(String)}。
     */
    public BotReport poolReport(String range) {
        return poolReports.report(range);
    }

    /** 号池直连库增量采集状态。 */
    public BotReport ingestReport() {
        return poolReports.ingestReport();
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
                        ReportFormats.decimal(account.balance()),
                        ReportFormats.decimal(account.usedQuota()),
                        ReportFormats.time(account.lastCollectedAt())));
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
                    : upstreamUsageService.listChanges(platform.id(), size * 4)) {
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

        // 一行一个变化：渠道倍率事件按「本平台账号 × 本平台分组」展开成多行，不做任何合并
        List<BotReport.Row> rows = new ArrayList<>();
        for (PlatformChange item : shown) {
            UpstreamChangeEventResponse event = item.event();
            List<UpstreamChangeEventResponse.ChangeImpact> impacts = event.impacts();
            if (impacts == null || impacts.isEmpty()) {
                rows.add(BotReport.Row.of(
                        ReportFormats.time(event.detectedAt()),
                        levelText(event),
                        item.platform(),
                        blankToDash(event.accountName()),
                        "未匹配到本平台账号",
                        "—",
                        "—",
                        "—",
                        entityName(event),
                        "—",
                        changeText(event)));
                continue;
            }
            for (UpstreamChangeEventResponse.ChangeImpact impact : impacts) {
                rows.add(BotReport.Row.of(
                        ReportFormats.time(event.detectedAt()),
                        levelText(event),
                        item.platform(),
                        blankToDash(event.accountName()),
                        blankToDash(impact.accountName()),
                        blankToDash(impact.upstreamKeyName()),
                        blankToDash(impact.groupName()),
                        ReportFormats.decimal(impact.groupRatio()),
                        entityName(event) + "（" + ReportFormats.decimal(impact.oldRatio())
                                + " → " + ReportFormats.decimal(impact.newRatio()) + "）",
                        impact.affected() ? "是（倒挂）" : "否",
                        changeText(event)));
            }
        }

        BotReport.Block table = new BotReport.Block("最近 " + shown.size() + " 条变化 / " + rows.size() + " 行"
                + (keyword.isEmpty() ? "（警告优先）" : "（筛选：" + filter + "）"), List.of(
                BotReport.Col.left("时间"),
                BotReport.Col.left("级别"),
                BotReport.Col.left("上游平台"),
                BotReport.Col.left("上游账号"),
                BotReport.Col.left("本平台账号"),
                BotReport.Col.left("上游 Key"),
                BotReport.Col.left("本平台分组"),
                BotReport.Col.right("分组倍率"),
                BotReport.Col.left("上游渠道（原 → 新）"),
                BotReport.Col.left("是否受影响"),
                BotReport.Col.left("内容")), rows, null);

        List<String> notes = new ArrayList<>();
        long warnings = matched.stream().filter(item -> warning(item.event())).count();
        if (warnings > 0) {
            notes.add("其中 " + warnings + " 条是警告（倍率上调 / 渠道移除），值得优先看。");
        }
        notes.add("倒挂口径：只看上游新倍率是否大于本平台分组倍率，不乘账号倍率。");
        notes.add("想看渠道涨价可以问「最近有哪些渠道涨价了」，或发 /变更 倍率；只看使用中的渠道发「/变更 使用中」。");
        return BotReport.of("变更记录", List.of(table), notes);
    }

    /** 一条变更 + 它属于哪个平台。 */
    private record PlatformChange(String platform, UpstreamChangeEventResponse event) {
    }

    private static boolean warning(UpstreamChangeEventResponse event) {
        return "WARNING".equalsIgnoreCase(event.severity());
    }

    /** 影响评估里的账号/分组名，供关键字搜索命中（例如搜「pro」）。 */
    private static String impactsText(UpstreamChangeEventResponse event) {
        List<UpstreamChangeEventResponse.ChangeImpact> impacts = event.impacts();
        if (impacts == null || impacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UpstreamChangeEventResponse.ChangeImpact impact : impacts) {
            sb.append(' ').append(impact.accountName()).append(' ')
                    .append(impact.upstreamKeyName()).append(' ').append(impact.groupName());
        }
        return sb.toString();
    }

    /** 级别 + 是否使用中，合成一列（同行不再单开一列「使用中」）。 */
    private static String levelText(UpstreamChangeEventResponse event) {
        String level = warning(event) ? "⚠ 警告" : "信息";
        return inUse(event) ? level + " · 使用中" : level;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /**
     * 变更对象的名字：渠道事件取渠道名，密钥事件取<strong>上游密钥名</strong>。
     *
     * <p>事件里存的是上游 ID（渠道 ID / 密钥 ID），而页面上只有名字 ——
     * 只给 ID 等于让人自己去猜是哪个。名字都是查询时带出来的；取不到时退回 ID。</p>
     */
    private static String entityName(UpstreamChangeEventResponse event) {
        if ("API_KEY".equalsIgnoreCase(event.entityType())
                && event.keyName() != null && !event.keyName().isBlank()) {
            return event.keyName();
        }
        if (event.groupName() != null && !event.groupName().isBlank()) {
            return event.groupName();
        }
        return event.entityKey() == null || event.entityKey().isBlank() ? "—" : event.entityKey();
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
                String.valueOf(event.groupName()),
                String.valueOf(event.keyName()),
                String.valueOf(event.accountName()),
                String.valueOf(event.upstreamKeyNames()),
                String.valueOf(event.localKeyNames()),
                impactsText(event),
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
            for (AccountUsageDashboardResponse usage : upstreamUsageService.listUsageDashboard(platform.id())) {
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
                    ReportFormats.decimal(usage.balance()),
                    metric(usage.metrics(), "today_actual_cost"),
                    ReportFormats.decimal(usage.totalActualCost() != null ? usage.totalActualCost() : usage.totalCost()),
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
            notes.add("数据来自最近一次采集快照（最晚 " + ReportFormats.time(latest) + "）。");
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
                        ReportFormats.decimal(account.balance()),
                        ReportFormats.time(account.lastCollectedAt())));
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
                    ReportFormats.time(task.lastRunAt()),
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
}