package com.monitor.platform.bot.error;

import com.monitor.platform.bot.error.Sub2ErrorReader.AccountGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorDetail;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorRow;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorSummary;
import com.monitor.platform.bot.error.Sub2ErrorReader.IngressRejectGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.SystemLogRow;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 机器人错误排障：取数、聚合、诊断结论与报表。
 *
 * <p>不依赖请求 ID。管理员按时间窗/状态码/关键词查全平台；普通用户只能按自己的
 * 用户 ID 查询。机器人自己会在列表里给出“错误编号 #123”，用户可回复该编号查看详情。</p>
 */
@Service
public class BotErrorService {

    private static final Logger log = LoggerFactory.getLogger(BotErrorService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Set<String> RANGES = Set.of(
            "5m", "15m", "30m", "90m", "1h", "6h", "12h", "24h", "7d", "30d");
    private static final String NO_DATABASE = """
            没有配置 Sub2API 只读库连接，错误查询不可用。
            需要设置 SUB2API_DB_URL / SUB2API_DB_USERNAME，并给只读账号授予
            ops_error_logs、ops_system_logs、ops_ingress_reject_aggregates、usage_logs、users、api_keys、accounts、groups 的 SELECT 权限。""";

    private final Sub2ErrorReader reader;
    private final BotUserService userService;

    public BotErrorService(Sub2ErrorReader reader, BotUserService userService) {
        this.reader = reader;
        this.userService = userService;
    }

    // ============================================================ 对外入口

    /** 管理员：整体诊断。 */
    public BotReport diagnoseReport(String rangeArg) {
        Window window = window(rangeArg, "1h");
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        try {
            ErrorSummary summary = reader.summary(window.from(), window.to(), null);
            ErrorFilter filter = ErrorFilter.all(null);
            List<ErrorGroup> groups = reader.topGroups(window.from(), window.to(), filter, 5);
            List<AccountGroup> accounts = reader.topAccounts(window.from(), window.to(), filter, 5);
            Long success = safeSuccessCount(window, null);
            Long ingressRejects = safeIngressRejectCount(window);
            return diagnoseReport(window, summary, success, groups, accounts, ingressRejects);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    /** 管理员：最近错误；参数支持时间窗、状态码、5xx、限流、配额、关键词。 */
    public BotReport recentReport(String arg) {
        ParsedErrors parsed = parseErrors(arg, "1h", false);
        if (parsed.id() != null) {
            return detailReport(parsed.id(), null, true);
        }
        return errorsReport(null, parsed, true, "错误查询");
    }

    /** 管理员：账号错误排行。 */
    public BotReport accountReport(String arg) {
        Window window = parseRange(arg, "1h");
        int limit = parseLimit(arg, 10);
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        try {
            ErrorSummary summary = reader.summary(window.from(), window.to(), null);
            List<AccountGroup> accounts = reader.topAccounts(
                    window.from(), window.to(), ErrorFilter.all(null), limit);
            Long success = safeSuccessCount(window, null);
            return accountReport(window, summary, success, accounts);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    /** 管理员：入口拒绝排行。 */
    public BotReport ingressReport(String arg) {
        Window window = parseRange(arg, "1h");
        int limit = parseLimit(arg, 10);
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        try {
            long total = reader.ingressRejectCount(window.from(), window.to());
            List<IngressRejectGroup> groups = reader.topIngressRejects(window.from(), window.to(), limit);
            return ingressReport(window, total, groups);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    /** 管理员：不依赖请求 ID 的系统日志查询。 */
    public BotReport systemLogReport(String arg) {
        ParsedSystemLog parsed = parseSystemLogArg(arg);
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        try {
            List<SystemLogRow> logs = reader.recentSystemLogs(
                    parsed.window().from(), parsed.window().to(), parsed.level(), parsed.keyword(), parsed.limit());
            return systemLogReport(parsed, logs);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    /** 管理员：指定用户的错误。 */
    public BotReport userReport(String userArg, String restArg) {
        if (userArg == null || userArg.isBlank()) {
            return message("用法：/错误用户 <邮箱或用户ID> [时间窗] [状态码/关键词]");
        }
        Long userId = resolveUser(userArg);
        if (userId == null) {
            return message("没有找到用户「" + userArg + "」。可以先用 /用户余额 或 /订单 查询候选。");
        }
        ParsedErrors parsed = parseErrors(restArg, "1h", false);
        if (parsed.id() != null) {
            return detailReport(parsed.id(), userId, true);
        }
        return errorsReport(userId, parsed, true, "用户错误");
    }

    /** 普通用户：只能查自己。 */
    public BotReport myReport(String email, String arg) {
        if (email == null || email.isBlank()) {
            return message("没有识别到你的邮箱，先发送 /绑定 <邮箱> 绑定。");
        }
        Long userId = userService.resolveUserId(email);
        if (userId == null) {
            return message("在平台上没有找到你的账号，请联系管理员。");
        }
        ParsedErrors parsed = parseErrors(arg, "24h", true);
        if (parsed.id() != null) {
            return detailReport(parsed.id(), userId, false);
        }
        return errorsReport(userId, parsed, false, "我的错误");
    }

    // ============================================================ 报表

    private BotReport diagnoseReport(Window window, ErrorSummary summary, Long success,
                                     List<ErrorGroup> groups, List<AccountGroup> accounts,
                                     Long ingressRejects) {
        long totalRequests = success == null ? 0L : success + summary.totalErrors();
        Double errorRate = success == null || totalRequests <= 0
                ? null : (double) summary.totalErrors() / totalRequests;

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(BotReport.paragraph(null, List.of(
                "结论：" + conclusion(window, summary, groups),
                "建议：" + action(summary, accounts))));
        blocks.add(summaryBlock(window, summary, success, errorRate, ingressRejects));
        if (!groups.isEmpty()) {
            blocks.add(groupsBlock(groups));
        }
        if (!accounts.isEmpty()) {
            blocks.add(accountsBlock(accounts));
        }

        List<String> notes = new ArrayList<>();
        notes.add("默认排除配额/限流等业务限制；回复 /错误 " + window.raw() + " 全部 可包含。");
        if (ingressRejects != null && ingressRejects > 0) {
            notes.add("另有入口拒绝 " + count(ingressRejects) + " 次，回复 /入口拒绝 " + window.raw() + " 查看原因。");
        }
        if (success == null) {
            notes.add("用量表暂不可用，本次未计算错误率。");
        }
        return BotReport.of("错误诊断（" + window.label() + "）", blocks, notes);
    }

    private BotReport errorsReport(Long userId, ParsedErrors parsed, boolean admin, String titlePrefix) {
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        Window window = parsed.window();
        try {
            ErrorFilter filter = new ErrorFilter(
                    userId,
                    parsed.statusCode(),
                    parsed.status5xx(),
                    parsed.rateLimited(),
                    parsed.businessLimitedOnly(),
                    parsed.includeBusinessLimited(),
                    parsed.keyword());
            ErrorSummary summary = reader.summary(window.from(), window.to(), userId);
            Long success = safeSuccessCount(window, userId);
            List<ErrorGroup> groups = reader.topGroups(window.from(), window.to(), filter, 5);
            List<ErrorRow> rows = reader.recent(window.from(), window.to(), filter, parsed.limit());

            long totalRequests = success == null ? 0L : success + summary.totalErrors();
            Double errorRate = success == null || totalRequests <= 0
                    ? null : (double) summary.totalErrors() / totalRequests;

            List<BotReport.Block> blocks = new ArrayList<>();
            blocks.add(summaryBlock(window, summary, success, errorRate, null));
            if (!groups.isEmpty()) {
                blocks.add(groupsBlock(groups));
            }
            if (rows.isEmpty()) {
                blocks.add(BotReport.paragraph("最近错误", List.of("没有找到符合条件的错误。")));
            } else {
                blocks.add(recentBlock(rows, admin));
            }

            List<String> notes = new ArrayList<>();
            if (!parsed.includeBusinessLimited() && !parsed.businessLimitedOnly()) {
                notes.add("默认排除业务限制类错误；回复 /错误 " + window.raw() + " 全部 可包含。");
            }
            notes.add(admin
                    ? "回复 /错误 #编号 查看单条详情。"
                    : "回复 /我的错误 #编号 查看单条详情。");
            return BotReport.of(titlePrefix + "（" + window.label() + "）", blocks, notes);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    private BotReport accountReport(Window window, ErrorSummary summary, Long success,
                                    List<AccountGroup> accounts) {
        long totalRequests = success == null ? 0L : success + summary.totalErrors();
        Double errorRate = success == null || totalRequests <= 0
                ? null : (double) summary.totalErrors() / totalRequests;

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(summaryBlock(window, summary, success, errorRate, null));
        if (accounts.isEmpty()) {
            blocks.add(BotReport.paragraph("账号排行", List.of("没有找到带账号信息的错误。")));
        } else {
            blocks.add(accountsBlock(accounts));
        }
        List<String> notes = new ArrayList<>();
        notes.add("默认排除业务限制类错误；如需包含，回复 /错误 " + window.raw() + " 全部。");
        notes.add("账号错误集中在少数账号时，优先检查该账号的上游状态、凭证和调度。");
        return BotReport.of("错误账号排行（" + window.label() + "）", blocks, notes);
    }

    private BotReport ingressReport(Window window, long total, List<IngressRejectGroup> groups) {
        List<BotReport.Block> blocks = new ArrayList<>();
        List<BotReport.Row> summary = new ArrayList<>();
        summary.add(BotReport.Row.of("时间窗", window.label()));
        summary.add(BotReport.Row.of("入口拒绝", count(total)));
        if (!groups.isEmpty()) {
            summary.add(BotReport.Row.of("主要原因", reasonLabel(groups.get(0).reason())));
        }
        blocks.add(new BotReport.Block("概览", List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), summary, null));

        if (groups.isEmpty()) {
            blocks.add(BotReport.paragraph("拒绝明细", List.of("没有找到入口拒绝记录。")));
        } else {
            List<BotReport.Row> rows = new ArrayList<>();
            for (IngressRejectGroup group : groups) {
                rows.add(BotReport.Row.of(
                        reasonLabel(group.reason()),
                        safe(group.routeFamily()),
                        safe(group.protocol()),
                        safe(group.clientIp()),
                        ingressIdentity(group),
                        count(group.count()),
                        time(group.lastSeen())));
            }
            blocks.add(new BotReport.Block("拒绝明细", List.of(
                    BotReport.Col.left("原因"),
                    BotReport.Col.left("路由"),
                    BotReport.Col.left("协议"),
                    BotReport.Col.left("IP"),
                    BotReport.Col.left("用户/Key"),
                    BotReport.Col.right("次数"),
                    BotReport.Col.left("最近")), rows, null));
        }

        List<String> notes = new ArrayList<>();
        notes.add("入口拒绝发生在鉴权/分组校验阶段，不会出现在普通错误列表。");
        notes.add("常见原因：无效 API Key、Key 禁用、IP 限制、用户禁用、分组禁用或未授权。");
        return BotReport.of("入口拒绝（" + window.label() + "）", blocks, notes);
    }

    private BotReport systemLogReport(ParsedSystemLog parsed, List<SystemLogRow> logs) {
        List<BotReport.Block> blocks = new ArrayList<>();
        List<BotReport.Row> summary = new ArrayList<>();
        summary.add(BotReport.Row.of("时间窗", parsed.window().label()));
        summary.add(BotReport.Row.of("级别", safe(parsed.level()).equals("-") ? "全部" : parsed.level()));
        if (parsed.keyword() != null && !parsed.keyword().isBlank()) {
            summary.add(BotReport.Row.of("关键词", parsed.keyword()));
        }
        summary.add(BotReport.Row.of("显示条数", count(logs.size())));
        blocks.add(new BotReport.Block("查询条件", List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), summary, null));

        if (logs.isEmpty()) {
            blocks.add(BotReport.paragraph("日志明细", List.of("没有找到符合条件的系统日志。")));
        } else {
            List<BotReport.Row> rows = new ArrayList<>();
            for (SystemLogRow log : logs) {
                rows.add(BotReport.Row.of(
                        time(log.createdAt()),
                        safe(log.level()),
                        safe(log.component()),
                        truncate(log.message(), 220)));
            }
            blocks.add(new BotReport.Block("日志明细", List.of(
                    BotReport.Col.left("时间"),
                    BotReport.Col.left("级别"),
                    BotReport.Col.left("组件"),
                    BotReport.Col.left("消息")), rows, null));
        }

        List<String> notes = new ArrayList<>();
        notes.add("不需要请求 ID；可以按时间、级别和关键词直接查系统日志。");
        notes.add("默认索引 warning / error / audit 日志；访问日志是否落库取决于 Sub2API 运行时配置。");
        notes.add("可以加级别或关键词，例如：/错误日志 1h error 529");
        return BotReport.of("错误日志（" + parsed.window().label() + "）", blocks, notes);
    }

    private BotReport detailReport(long id, Long userId, boolean admin) {
        if (!reader.isAvailable()) {
            return message(NO_DATABASE);
        }
        try {
            ErrorDetail detail = reader.detail(id, userId);
            if (detail == null) {
                return message("没有找到错误编号 #" + id + "，或它不属于当前账号。");
            }
            ErrorRow row = detail.row();
            List<BotReport.Block> blocks = new ArrayList<>();
            blocks.add(detailBlock(detail, admin));

            String cause = firstNonBlank(
                    detail.upstreamErrorMessage(),
                    detail.upstreamErrorDetail(),
                    row.message());
            if (!cause.isBlank()) {
                blocks.add(BotReport.paragraph("错误信息", List.of(truncate(cause, 600))));
            }
            if (admin && !detail.errorBody().isBlank()) {
                blocks.add(BotReport.paragraph("错误正文", List.of(truncate(detail.errorBody(), 600))));
            }
            if (admin && !detail.upstreamErrors().isBlank()) {
                blocks.add(BotReport.paragraph("上游尝试", List.of(truncate(detail.upstreamErrors(), 900))));
            }
            blocks.add(BotReport.paragraph("建议", List.of(suggestion(
                    row.phase(), row.type(), row.statusCode(), row.businessLimited()))));

            if (admin) {
                List<SystemLogRow> logs = reader.systemLogs(row.requestId(), row.clientRequestId(), 5);
                if (!logs.isEmpty()) {
                    List<BotReport.Row> logRows = new ArrayList<>();
                    for (SystemLogRow log : logs) {
                        logRows.add(BotReport.Row.of(
                                time(log.createdAt()),
                                safe(log.level()),
                                safe(log.component()),
                                truncate(log.message(), 160)));
                    }
                    blocks.add(new BotReport.Block("关联系统日志", List.of(
                            BotReport.Col.left("时间"),
                            BotReport.Col.left("级别"),
                            BotReport.Col.left("组件"),
                            BotReport.Col.left("消息")), logRows, null));
                } else {
                    blocks.add(BotReport.paragraph("关联系统日志", List.of("没有找到关联日志。")));
                }
            }

            List<String> notes = new ArrayList<>();
            notes.add("错误编号 #" + id + " 可用于后续追问。");
            return BotReport.of("错误详情 #" + id, blocks, notes);
        } catch (RuntimeException ex) {
            return queryError(ex);
        }
    }

    // ============================================================ blocks

    private BotReport.Block summaryBlock(Window window, ErrorSummary summary, Long success,
                                           Double errorRate, Long ingressRejects) {
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("时间窗", window.label()));
        if (success != null) {
            rows.add(BotReport.Row.of("请求数", count(success + summary.totalErrors())));
        }
        rows.add(BotReport.Row.of("错误数", count(summary.totalErrors())));
        rows.add(BotReport.Row.of("错误率", percent(errorRate)));
        rows.add(BotReport.Row.of("业务限制", count(summary.businessLimited())));
        rows.add(BotReport.Row.of("429 / 529", count(summary.rateLimited())));
        rows.add(BotReport.Row.of("401 / 403", count(summary.authErrors())));
        rows.add(BotReport.Row.of("5xx", count(summary.serverErrors())));
        rows.add(BotReport.Row.of("未解决", count(summary.unresolved())));
        if (ingressRejects != null) {
            rows.add(BotReport.Row.of("入口拒绝", count(ingressRejects)));
        }
        return new BotReport.Block("关键指标", List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.right("值")), rows, null);
    }

    private BotReport.Block groupsBlock(List<ErrorGroup> groups) {
        List<BotReport.Row> rows = new ArrayList<>();
        for (ErrorGroup group : groups) {
            rows.add(BotReport.Row.of(
                    category(group.phase(), group.type()),
                    String.valueOf(group.statusCode()),
                    count(group.count()),
                    time(group.lastSeen())));
        }
        return new BotReport.Block("主要错误", List.of(
                BotReport.Col.left("分类"),
                BotReport.Col.left("状态"),
                BotReport.Col.right("次数"),
                BotReport.Col.left("最近")), rows, null);
    }

    private BotReport.Block accountsBlock(List<AccountGroup> accounts) {
        List<BotReport.Row> rows = new ArrayList<>();
        for (AccountGroup account : accounts) {
            rows.add(BotReport.Row.of(
                    safe(account.accountName()),
                    safe(account.platform()),
                    count(account.count()),
                    count(account.rateLimited()),
                    count(account.authErrors()),
                    count(account.serverErrors()),
                    time(account.lastSeen())));
        }
        return new BotReport.Block("问题账号", List.of(
                BotReport.Col.left("账号"),
                BotReport.Col.left("平台"),
                BotReport.Col.right("错误"),
                BotReport.Col.right("429/529"),
                BotReport.Col.right("401/403"),
                BotReport.Col.right("5xx"),
                BotReport.Col.left("最近")), rows, null);
    }

    private BotReport.Block recentBlock(List<ErrorRow> rows, boolean admin) {
        List<BotReport.Row> data = new ArrayList<>();
        for (ErrorRow row : rows) {
            if (admin) {
                data.add(BotReport.Row.of(
                        "#" + row.id(),
                        time(row.createdAt()),
                        category(row.phase(), row.type()),
                        status(row),
                        model(row),
                        identity(row),
                        truncate(row.message(), 70)));
            } else {
                data.add(BotReport.Row.of(
                        "#" + row.id(),
                        time(row.createdAt()),
                        category(row.phase(), row.type()),
                        status(row),
                        model(row),
                        truncate(row.message(), 90)));
            }
        }
        List<BotReport.Col> cols = admin
                ? List.of(BotReport.Col.left("编号"), BotReport.Col.left("时间"),
                BotReport.Col.left("分类"), BotReport.Col.left("状态"),
                BotReport.Col.left("模型"), BotReport.Col.left("用户/账号"),
                BotReport.Col.left("消息"))
                : List.of(BotReport.Col.left("编号"), BotReport.Col.left("时间"),
                BotReport.Col.left("分类"), BotReport.Col.left("状态"),
                BotReport.Col.left("模型"), BotReport.Col.left("消息"));
        return new BotReport.Block("最近错误", cols, data, null);
    }

    private BotReport.Block detailBlock(ErrorDetail detail, boolean admin) {
        ErrorRow row = detail.row();
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("时间", time(row.createdAt())));
        rows.add(BotReport.Row.of("分类", category(row.phase(), row.type())));
        rows.add(BotReport.Row.of("客户端状态", String.valueOf(row.statusCode())));
        if (row.upstreamStatusCode() != null) {
            rows.add(BotReport.Row.of("上游状态", String.valueOf(row.upstreamStatusCode())));
        }
        rows.add(BotReport.Row.of("模型", model(row)));
        rows.add(BotReport.Row.of("平台", safe(row.platform())));
        rows.add(BotReport.Row.of("入口端点", safe(row.inboundEndpoint())));
        if (row.upstreamEndpoint() != null && !row.upstreamEndpoint().isBlank()) {
            rows.add(BotReport.Row.of("上游端点", row.upstreamEndpoint()));
        }
        rows.add(BotReport.Row.of("耗时", millis(row.durationMs())));
        rows.add(BotReport.Row.of("首 Token", millis(row.timeToFirstTokenMs())));
        rows.add(BotReport.Row.of("业务限制", row.businessLimited() ? "是" : "否"));
        rows.add(BotReport.Row.of("已解决", row.resolved() ? "是" : "否"));
        if (admin) {
            rows.add(BotReport.Row.of("用户", safe(row.userEmail())));
            rows.add(BotReport.Row.of("API Key", safe(row.apiKeyName())));
            if (detail.apiKeyPrefix() != null && !detail.apiKeyPrefix().isBlank()) {
                rows.add(BotReport.Row.of("Key 前缀", detail.apiKeyPrefix()));
            }
            rows.add(BotReport.Row.of("账号", safe(row.accountName())));
            rows.add(BotReport.Row.of("分组", safe(row.groupName())));
            rows.add(BotReport.Row.of("客户端 IP", safe(row.clientIp())));
            rows.add(BotReport.Row.of("User-Agent", truncate(row.userAgent(), 120)));
            rows.add(BotReport.Row.of("请求路径", safe(detail.requestPath())));
        }
        return new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), rows, null);
    }

    // ============================================================ parsing

    private ParsedErrors parseErrors(String arg, String defaultRange, boolean includeBusinessLimitedDefault) {
        String raw = arg == null ? "" : arg.trim();
        if (raw.startsWith("#")) {
            String number = raw.substring(1).trim();
            if (number.matches("\\d+")) {
                try {
                    return new ParsedErrors(Long.parseLong(number), window(defaultRange, defaultRange),
                            null, false, false, false, includeBusinessLimitedDefault, null, 10);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }

        Window window = null;
        Integer statusCode = null;
        boolean status5xx = false;
        boolean rateLimited = false;
        boolean businessLimitedOnly = false;
        boolean includeBusinessLimited = includeBusinessLimitedDefault;
        List<String> keywordParts = new ArrayList<>();

        for (String token : raw.isEmpty() ? new String[0] : raw.split("\\s+")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (window == null && isRange(value)) {
                window = window(value, defaultRange);
                continue;
            }
            if ("全部".equals(value) || "all".equalsIgnoreCase(value)) {
                includeBusinessLimited = true;
                continue;
            }
            if ("配额".equals(value) || "业务限制".equals(value)) {
                businessLimitedOnly = true;
                includeBusinessLimited = true;
                continue;
            }
            if ("限流".equals(value)) {
                rateLimited = true;
                includeBusinessLimited = true;
                continue;
            }
            if ("5xx".equalsIgnoreCase(value) || "5开".equals(value)) {
                status5xx = true;
                continue;
            }
            if (value.matches("\\d{3}")) {
                statusCode = Integer.parseInt(value);
                if (statusCode == 429 || statusCode == 529) {
                    includeBusinessLimited = true;
                }
                continue;
            }
            keywordParts.add(value);
        }

        if (window == null) {
            window = window(defaultRange, defaultRange);
        }
        String keyword = keywordParts.isEmpty() ? null : String.join(" ", keywordParts);
        return new ParsedErrors(null, window, statusCode, status5xx, rateLimited,
                businessLimitedOnly, includeBusinessLimited, keyword, 10);
    }

    private Window parseRange(String arg, String defaultRange) {
        if (arg == null || arg.isBlank()) {
            return window(defaultRange, defaultRange);
        }
        for (String token : arg.trim().split("\\s+")) {
            if (isRange(token)) {
                return window(token, defaultRange);
            }
        }
        return window(defaultRange, defaultRange);
    }

    private int parseLimit(String arg, int fallback) {
        if (arg == null) {
            return fallback;
        }
        for (String token : arg.trim().split("\\s+")) {
            if (token.matches("\\d{1,2}") && !isRange(token)) {
                return Math.max(1, Math.min(Integer.parseInt(token), 20));
            }
        }
        return fallback;
    }

    private ParsedSystemLog parseSystemLogArg(String arg) {
        Window window = null;
        String level = null;
        int limit = 10;
        List<String> keywordParts = new ArrayList<>();
        String raw = arg == null ? "" : arg.trim();
        for (String token : raw.isEmpty() ? new String[0] : raw.split("\\s+")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (window == null && isRange(value)) {
                window = window(value, "1h");
                continue;
            }
            if (level == null && isLogLevel(value)) {
                level = value.toLowerCase(Locale.ROOT);
                continue;
            }
            if (value.matches("\\d{1,2}")) {
                limit = Math.max(1, Math.min(Integer.parseInt(value), 20));
                continue;
            }
            keywordParts.add(value);
        }
        if (window == null) {
            window = window("1h", "1h");
        }
        return new ParsedSystemLog(window, level,
                keywordParts.isEmpty() ? null : String.join(" ", keywordParts), limit);
    }

    private static boolean isLogLevel(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "debug", "info", "warn", "warning", "error", "fatal", "panic", "dpanic" -> true;
            default -> false;
        };
    }

    private Long resolveUser(String userArg) {
        String value = userArg == null ? "" : userArg.trim();
        if (value.matches("\\d+")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return userService.resolveUserId(value);
    }

    private Window window(String raw, String defaultRange) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            value = defaultRange;
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        if ("today".equals(value) || "今天".equals(value)) {
            return new Window(now.toLocalDate().atStartOfDay(ZONE).toOffsetDateTime(), now, "今日", "today");
        }
        if ("week".equals(value) || "7d".equals(value) || "近7天".equals(value)) {
            return new Window(now.minusDays(7), now, "近 7 天", "7d");
        }
        if ("month".equals(value) || "30d".equals(value) || "近30天".equals(value)) {
            return new Window(now.minusDays(30), now, "近 30 天", "30d");
        }
        Duration duration = duration(value);
        if (duration == null) {
            duration = duration(defaultRange);
            value = defaultRange;
        }
        return new Window(now.minus(duration), now, "近 " + value, value);
    }

    private static Duration duration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            char unit = value.charAt(value.length() - 1);
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            if (amount <= 0) {
                return null;
            }
            return switch (unit) {
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> null;
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isRange(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return RANGES.contains(normalized)
                || "today".equals(normalized) || "今天".equals(normalized)
                || "week".equals(normalized) || "近7天".equals(normalized)
                || "month".equals(normalized) || "近30天".equals(normalized);
    }

    // ============================================================ text helpers

    private String conclusion(Window window, ErrorSummary summary, List<ErrorGroup> groups) {
        if (summary.totalErrors() == 0) {
            return window.label() + "没有发现客户端可见错误。";
        }
        if (summary.businessLimited() > 0 && summary.businessLimited() * 2 >= summary.totalErrors()) {
            return "大部分错误是配额/限流等业务限制，不一定是上游故障。";
        }
        if (summary.rateLimited() > 0 && summary.rateLimited() * 2 >= summary.totalErrors()) {
            return "主要问题是上游限流/过载（429/529）。";
        }
        if (summary.authErrors() > 0 && summary.authErrors() * 2 >= summary.totalErrors()) {
            return "鉴权类错误突出，优先检查 Key、用户状态和分组权限。";
        }
        if (summary.serverErrors() > 0 && summary.serverErrors() * 2 >= summary.totalErrors()) {
            return "上游 5xx 突出，优先检查上游账号和供应商状态。";
        }
        if (!groups.isEmpty()) {
            ErrorGroup top = groups.get(0);
            return "主要错误是「" + category(top.phase(), top.type()) + " / " + top.statusCode() + "」。";
        }
        return "存在零散错误，建议查看最近错误列表。";
    }

    private String action(ErrorSummary summary, List<AccountGroup> accounts) {
        if (summary.rateLimited() > 0) {
            String account = accounts.stream()
                    .filter(item -> item.rateLimited() > 0)
                    .map(AccountGroup::accountName)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse("");
            return account.isBlank()
                    ? "先检查上游账号的限流/过载冷却和调度权重。"
                    : "先检查账号「" + account + "」的上游状态、限流冷却和调度权重。";
        }
        if (summary.authErrors() > 0) {
            return "检查 API Key 是否禁用/过期，以及用户状态和分组权限。";
        }
        if (summary.serverErrors() > 0) {
            return "检查上游账号凭证、供应商状态和网络链路。";
        }
        if (summary.businessLimited() > 0) {
            return "检查余额、配额、并发和订阅限制。";
        }
        return "查看最近错误详情，确认具体模型、账号和错误消息。";
    }

    private static String reasonLabel(String reason) {
        return switch (reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT)) {
            case "query_api_key_deprecated" -> "URL 携带 Key";
            case "api_key_required" -> "缺少 API Key";
            case "invalid_api_key" -> "无效 API Key";
            case "invalid_auth_rate_limited" -> "鉴权失败限流";
            case "api_key_auth_overloaded" -> "鉴权过载";
            case "api_key_disabled" -> "API Key 已禁用";
            case "ip_restricted" -> "IP 限制";
            case "user_inactive" -> "用户已禁用";
            case "group_deleted" -> "分组已删除";
            case "group_disabled" -> "分组已禁用";
            case "group_not_allowed" -> "分组未授权";
            case "group_unassigned" -> "未分配分组";
            case "other" -> "其他";
            default -> safe(reason);
        };
    }

    private static String ingressIdentity(IngressRejectGroup group) {
        String user = group.userId() != null && group.userId() > 0
                ? "用户 " + group.userId() : "";
        String key = group.apiKeyId() != null && group.apiKeyId() > 0
                ? "Key " + group.apiKeyId() : "";
        if (!user.isBlank() && !key.isBlank()) {
            return user + " / " + key;
        }
        return !user.isBlank() ? user : (!key.isBlank() ? key : "-");
    }

    private static String category(String phase, String type) {
        String p = phase == null ? "" : phase.toLowerCase(Locale.ROOT);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (p) {
            case "auth" -> "鉴权";
            case "routing" -> "服务不可用";
            case "account_auth", "upstream", "network" -> "上游/网络";
            case "internal" -> "平台内部";
            case "request" -> switch (t) {
                case "rate_limit_error" -> "限流";
                case "billing_error", "subscription_error" -> "配额/订阅";
                case "invalid_request_error" -> "请求参数";
                case "cyber_policy" -> "安全策略";
                default -> "请求";
            };
            default -> "其他";
        };
    }

    private static String suggestion(String phase, String type, int statusCode, boolean businessLimited) {
        if (businessLimited) {
            return "该错误属于业务限制，优先检查余额、配额、并发或订阅状态。";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "优先检查 API Key 状态、用户状态和分组权限。";
        }
        if (statusCode == 429) {
            return "上游限流，检查账号调度、限流冷却和重试策略。";
        }
        if (statusCode == 529) {
            return "上游过载，检查账号状态并考虑临时降权或暂停调度。";
        }
        if (statusCode >= 500) {
            return "上游服务异常，检查账号凭证、供应商状态和网络链路。";
        }
        String p = phase == null ? "" : phase.toLowerCase(Locale.ROOT);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if ("account_auth".equals(p)) {
            return "号池账号凭证可能失效，检查账号授权和凭证有效期。";
        }
        if ("routing".equals(p)) {
            return "没有可用账号或分组调度失败，检查分组绑定和账号可调度状态。";
        }
        if ("network".equals(p)) {
            return "检查网络、代理、DNS/TLS 和上游连通性。";
        }
        if ("internal".equals(p)) {
            return "平台内部异常，查看关联系统日志和后端错误。";
        }
        if ("invalid_request_error".equals(t)) {
            return "检查请求参数、模型名、上下文长度和请求体格式。";
        }
        return "查看错误消息和关联日志，确认具体失败原因。";
    }

    private static String status(ErrorRow row) {
        if (row.upstreamStatusCode() != null && row.upstreamStatusCode() != row.statusCode()) {
            return row.statusCode() + "/" + row.upstreamStatusCode();
        }
        return String.valueOf(row.statusCode());
    }

    private static String model(ErrorRow row) {
        String requested = row.requestedModel();
        String upstream = row.upstreamModel();
        if (requested != null && !requested.isBlank()
                && upstream != null && !upstream.isBlank()
                && !requested.equals(upstream)) {
            return requested + "→" + upstream;
        }
        if (upstream != null && !upstream.isBlank()) {
            return upstream;
        }
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return safe(row.model());
    }

    private static String identity(ErrorRow row) {
        String user = row.userEmail();
        String account = row.accountName();
        if (user != null && !user.isBlank() && account != null && !account.isBlank()) {
            return user + " / " + account;
        }
        if (user != null && !user.isBlank()) {
            return user;
        }
        if (account != null && !account.isBlank()) {
            return account;
        }
        return "-";
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "-" : value.atZoneSameInstant(ZONE).format(TIME);
    }

    private static String millis(Number value) {
        if (value == null) {
            return "-";
        }
        double seconds = value.doubleValue() / 1000.0;
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static BotReport message(String value) {
        return BotReport.fromMarkdown(value);
    }

    private Long safeIngressRejectCount(Window window) {
        try {
            return reader.ingressRejectCount(window.from(), window.to());
        } catch (RuntimeException ex) {
            log.warn("查询入口拒绝数失败: {}", ex.getMessage());
            return null;
        }
    }

    private Long safeSuccessCount(Window window, Long userId) {
        try {
            return reader.successCount(window.from(), window.to(), userId);
        } catch (RuntimeException ex) {
            log.warn("查询成功请求数失败: {}", ex.getMessage());
            return null;
        }
    }

    private BotReport queryError(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String reason = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        log.warn("查询错误数据失败: {}", reason);
        return message("查询错误失败：" + reason
                + "\n请确认只读账号已授予 ops_error_logs、ops_system_logs、usage_logs、users、api_keys、accounts、groups 的 SELECT 权限。");
    }

    private record Window(OffsetDateTime from, OffsetDateTime to, String label, String raw) {
    }

    private record ParsedSystemLog(Window window, String level, String keyword, int limit) {
    }

    private record ParsedErrors(
            Long id,
            Window window,
            Integer statusCode,
            boolean status5xx,
            boolean rateLimited,
            boolean businessLimitedOnly,
            boolean includeBusinessLimited,
            String keyword,
            int limit
    ) {
    }
}
