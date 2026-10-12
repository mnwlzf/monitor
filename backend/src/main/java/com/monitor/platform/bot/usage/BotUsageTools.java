package com.monitor.platform.bot.usage;

import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.usage.Sub2UserUsageReader.UserConsumption;
import com.monitor.platform.bot.usage.Sub2UserUsageReader.UsageSummary;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端：查看<strong>所有用户</strong>在指定日期的余额消耗。
 *
 * <p>数据来自直连只读库（见 {@link Sub2UserUsageReader}）：上游 {@code /api/v1/admin/usage/stats}
 * 是按单个用户查的，要覆盖所有用户只能逐个调用；直连库一次 {@code GROUP BY user_id} 就够。</p>
 *
 * <p>「余额消耗」的口径是 {@code SUM(actual_cost) WHERE billing_type = 0}，
 * 即只算扣钱包余额的那部分，不含订阅套餐消耗。</p>
 */
@Component
public class BotUsageTools {

    private static final Logger log = LoggerFactory.getLogger(BotUsageTools.class);

    /** 业务时区，与只读库读取侧保持一致。 */
    private static final ZoneId ZONE = BusinessTime.ZONE;

    /** 明细默认条数 / 上限。 */
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;

    private static final DateTimeFormatter SHOW_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String NO_DATABASE = """
            没有配置 Sub2API 只读库连接，用户消耗查询不可用。
            需要设置 SUB2API_DB_URL 与 SUB2API_DB_USERNAME（和号池增量采集共用同一套连接信息）。""";

    private static final String USAGE = "用法：/用户消耗 [日期]，例如 /用户消耗 2026-10-10；"
            + "日期可省略（默认今天），也支持「昨天」「前天」。";

    private final Sub2UserUsageReader reader;

    public BotUsageTools(Sub2UserUsageReader reader) {
        this.reader = reader;
    }

    // ============================================================
    // 大模型工具：返回纯文本
    // ============================================================

    @Tool(description = "查询指定日期所有平台用户的余额消耗排行：每个用户的请求数、Token、"
            + "标准费用与实际消耗（扣钱包余额的部分），以及当日合计。"
            + "date 格式 2026-10-10，留空表示今天，也支持「昨天」「前天」。")
    public String userConsumptionByDate(String date) {
        return userConsumptionReport(date).toPlainText();
    }

    // ============================================================
    // 结构化报表：给确定性命令用
    // ============================================================

    /** 指定日期所有用户的余额消耗。 */
    public BotReport userConsumptionReport(String date) {
        if (!reader.isAvailable()) {
            return BotReport.fromMarkdown(NO_DATABASE);
        }
        LocalDate day = resolveDate(date);
        if (day == null) {
            return BotReport.fromMarkdown(USAGE);
        }

        OffsetDateTime start = day.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        try {
            UsageSummary summary = reader.summarise(start, end);
            List<UserConsumption> rows = reader.byUser(start, end, DEFAULT_LIMIT + 1);
            boolean truncated = rows.size() > DEFAULT_LIMIT;
            if (truncated) {
                rows = rows.subList(0, DEFAULT_LIMIT);
            }

            List<BotReport.Block> blocks = new ArrayList<>();
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("项目"),
                    BotReport.Col.left("值")), List.of(
                    BotReport.Row.of("日期", day.format(SHOW_DATE)),
                    BotReport.Row.of("有消耗的用户", summary.userCount() + " 个"),
                    BotReport.Row.of("请求数", String.valueOf(summary.requestCount())),
                    BotReport.Row.of("标准费用合计", money(summary.standardCost())),
                    BotReport.Row.of("实际消耗合计", money(summary.actualCost()))), null));

            if (rows.isEmpty()) {
                blocks.add(BotReport.paragraph("用户明细", List.of("这一天没有人消耗余额。")));
            } else {
                List<BotReport.Row> detail = new ArrayList<>();
                for (UserConsumption row : rows) {
                    detail.add(BotReport.Row.of(
                            display(row),
                            String.valueOf(row.requestCount()),
                            String.valueOf(row.totalTokens()),
                            money(row.standardCost()),
                            money(row.actualCost())));
                }
                blocks.add(new BotReport.Block("用户明细（按实际消耗排序，前 " + detail.size() + " 个）", List.of(
                        BotReport.Col.left("用户"),
                        BotReport.Col.right("请求数"),
                        BotReport.Col.right("Token"),
                        BotReport.Col.right("标准费用"),
                        BotReport.Col.right("实际消耗")), detail, null));
            }

            List<String> notes = new ArrayList<>();
            notes.add("口径：仅统计扣钱包余额的部分（billing_type=0），不含订阅套餐消耗；"
                    + "按 Asia/Shanghai 的自然日统计。");
            if (summary.userCount() > rows.size()) {
                notes.add("当天有消耗的用户共 " + summary.userCount() + " 个，这里只列出消耗最高的 "
                        + rows.size() + " 个（合计仍是全量）。");
            }
            return BotReport.of("用户余额消耗（" + day.format(SHOW_DATE) + "）", blocks, notes);
        } catch (RuntimeException ex) {
            log.warn("查询用户消耗失败: {}", ex.getMessage());
            return BotReport.fromMarkdown("查询用户消耗失败：" + rootMessage(ex)
                    + "\n如果提示权限不足，需要确认只读账号对 public.usage_logs 与 public.users 有 SELECT 权限。");
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 解析日期；认不出返回 null（调用方回用法）。 */
    private static LocalDate resolveDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return LocalDate.now(ZONE);
        }
        LocalDate today = LocalDate.now(ZONE);
        return switch (value) {
            case "今天", "今日" -> today;
            case "昨天", "昨日" -> today.minusDays(1);
            case "前天" -> today.minusDays(2);
            default -> parseDate(value, today);
        };
    }

    /** 认 {@code 2026-10-10} / {@code 2026/10/10} / {@code 10-10}；认不出返回 null。 */
    private static LocalDate parseDate(String value, LocalDate today) {
        String normalized = value.replace('/', '-').replace('.', '-');
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("uuuu-M-d"));
        } catch (DateTimeParseException ignored) {
            // 再试不带年份的写法
        }
        try {
            return LocalDate.parse(today.getYear() + "-" + normalized, DateTimeFormatter.ofPattern("uuuu-M-d"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String display(UserConsumption row) {
        if (row.email() != null && !row.email().isBlank()) {
            return row.email();
        }
        if (row.username() != null && !row.username().isBlank()) {
            return row.username();
        }
        return "用户 " + row.userId();
    }

    private static String money(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
